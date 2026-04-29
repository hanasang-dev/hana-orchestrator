package com.hana.orchestrator.orchestrator.core

import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionNode
import com.hana.orchestrator.domain.entity.NodeExecutionResult
import com.hana.orchestrator.domain.entity.NodeStatus
import com.hana.orchestrator.domain.entity.ExecutionContext
import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.layer.CommonLayerInterface
import com.hana.orchestrator.layer.RemoteLayer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.hana.orchestrator.orchestrator.ExecutionHistoryManager
import com.hana.orchestrator.orchestrator.ExecutionStatePublisher
import com.hana.orchestrator.orchestrator.createOrchestratorLogger

/**
 * 트리 실행 책임
 * SRP: ExecutionTree 실행만 담당
 */
class TreeExecutor(
    private val layerManager: LayerManager,
    private val statePublisher: ExecutionStatePublisher,
    private val historyManager: ExecutionHistoryManager
) {
    private val logger = createOrchestratorLogger(TreeExecutor::class.java, historyManager)
    /**
     * ExecutionTree를 재귀적으로 실행
     * 다중 루트 노드를 지원: 각 루트는 독립적으로 실행되며 병렬 실행 가능
     */
    suspend fun executeTree(
        tree: ExecutionTree,
        currentExecution: ExecutionHistory
    ): ExecutionResult {
        val context = ExecutionContext()
        val treeStartTime = System.currentTimeMillis()
        logger.info("🌳 [TreeExecutor] 실행 트리 시작: ${tree.name} (루트 노드 ${tree.rootNodes.size}개)")
        
        // 다중 루트 노드 실행 (병렬 실행)
        val rootResults = coroutineScope {
            tree.rootNodes.map { rootNode ->
                async {
                    executeNode(rootNode, context, parentNodeId = null, depth = 0)
                }
            }.awaitAll()
        }
        
        val treeDuration = System.currentTimeMillis() - treeStartTime
        logger.perf("⏱️ [PERF] executeTree 총 소요 시간: ${treeDuration}ms")
        
        // 실행 완료 후 전체 상태 로그 출력
        logger.debug("\n📊 [TreeExecutor] ========== 실행 결과 요약 ==========")
        logger.debug("✅ 성공한 노드: ${context.completedNodes.size}개")
        context.completedNodes.forEach { nodeResult ->
            logger.debug("   - ${nodeResult.nodeId}: ${nodeResult.node.layerName}.${nodeResult.node.function} (depth=${nodeResult.depth})")
        }
        
        logger.debug("❌ 실패한 노드: ${context.failedNodes.size}개")
        context.failedNodes.forEach { nodeResult ->
            logger.debug("   - ${nodeResult.nodeId}: ${nodeResult.node.layerName}.${nodeResult.node.function} (depth=${nodeResult.depth})")
            val errorText = nodeResult.error ?: "Unknown error"
            logger.debug("     에러: $errorText")
        }
        
        val skippedCount = context.countByStatus(NodeStatus.SKIPPED)
        logger.debug("⏭️ 건너뛴 노드: ${skippedCount}개")
        context.getAllResults().values.filter { it.isSkipped }.forEach { nodeResult ->
            logger.debug("   - ${nodeResult.nodeId}: ${nodeResult.node.layerName}.${nodeResult.node.function} (부모 실패로 인해 건너뜀)")
        }
        
        logger.debug("📊 전체 노드 수: ${context.getAllResults().size}개")
        logger.debug("==========================================\n")
        
        // 최종 결과: 모든 루트 노드의 결과를 결합
        val allRootResults = rootResults.mapNotNull { it.result }.filter { it.isNotEmpty() }
        val resultText = if (allRootResults.isNotEmpty()) {
            allRootResults.joinToString("\n")
        } else if (context.completedNodes.isNotEmpty()) {
            // 루트 노드 결과가 없으면 fallback으로 모든 성공 노드 결과 결합
            val allResults = context.completedNodes
                .sortedBy { it.depth }
                .mapNotNull { it.result }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            if (allResults.isNotEmpty()) {
                allResults
            } else {
                "실행 완료 (결과 없음)"
            }
        } else {
            "실행 완료 (결과 없음)"
        }
        
        // 노드 결과 요약 수집 (레이어 단위 메트릭 persist용)
        val nodeResultSummaries = context.getAllResults().values
            .filter { it.status != NodeStatus.RUNNING }
            .map { r ->
                com.hana.orchestrator.domain.entity.NodeResultSummary(
                    layerName = r.node.layerName,
                    function = r.node.function,
                    status = r.status.name,
                    error = r.error
                )
            }

        // 최종 결과 계산
        val finalResult = ExecutionResult(
            result = resultText,
            executionTree = tree,
            context = context,
            nodeResults = nodeResultSummaries
        )
        
        // 실행 중인 경우 현재 실행 상태 업데이트 (노드 레벨 정보 포함)
        val updatedHistory = statePublisher.updateCurrentExecutionWithContext(
            currentExecution, context, tree, finalResult
        )
        historyManager.setCurrentExecution(updatedHistory)
        
        return finalResult
    }
    
    /**
     * ExecutionNode를 재귀적으로 실행 (상태 추적 포함)
     */
    private suspend fun executeNode(
        node: ExecutionNode,
        context: ExecutionContext,
        parentNodeId: String? = null,
        depth: Int = 0
    ): NodeExecutionResult {
        val indent = "  ".repeat(depth)
        val nodeId = node.id
        
        // 의존성 체크
        if (!context.canExecute(parentNodeId)) {
            val skippedResult = context.recordNode(
                node, NodeStatus.SKIPPED, depth, parentNodeId,
                error = "Parent node failed"
            )
            logger.debug("${indent}⏭️ [TreeExecutor] 건너뜀: ${node.layerName}.${node.function} (부모 실패)")
            return skippedResult
        }
        
        // rootNode에서 {{parent}} 사용 불가 검증
        if (parentNodeId == null) {
            val illegalArgs = node.args.entries
                .filter { (_, v) -> v is String && (v as String).contains("{{parent}}") }
                .map { it.key }
            if (illegalArgs.isNotEmpty()) {
                logger.warn("${indent}⚠️ [TreeExecutor] rootNode에서 {{parent}} 사용 불가: ${node.layerName}.${node.function}(args=$illegalArgs)")
                return context.recordNode(
                    node, NodeStatus.FAILED, depth, parentNodeId,
                    error = "ERROR: ${node.layerName}.${node.function} — rootNode는 {{parent}}를 사용할 수 없습니다. 이 노드를 다른 노드의 children에 넣으세요."
                )
            }
        }

        val runningResult = context.recordNode(node, NodeStatus.RUNNING, depth, parentNodeId)
        logger.debug("${indent}🎯 [TreeExecutor] 실행 시작: ${node.layerName}.${node.function} (id=$nodeId, depth=$depth, parent=$parentNodeId, children=${node.children.size}, parallel=${node.parallel})")
        
        // 레이어 함수 실행
        val layer = layerManager.findLayerByName(node.layerName)
        
        if (layer == null) {
            val failedResult = context.recordNode(
                node, NodeStatus.FAILED, depth, parentNodeId,
                error = "Layer '${node.layerName}' not found"
            )
            logger.error("${indent}❌ [TreeExecutor] 레이어를 찾을 수 없음: ${node.layerName}")
            return failedResult
        }
        
        val executionResult: NodeExecutionResult = try {
            // 원격 레이어인지 확인
            val isRemote = layer is RemoteLayer
            val remoteUrl = if (isRemote) layer.baseUrl else null
            
            val execStartMsg = "${indent}▶️ [TreeExecutor] ${node.layerName}.${node.function} 실행 중...${if (isRemote) " (원격: $remoteUrl)" else ""}"
            logger.info(execStartMsg)
            val nodeStartTime = System.currentTimeMillis()
            val resolvedArgs = resolveArgs(node.args, context, parentNodeId)
            val execResult = layerManager.executeOnLayer(node.layerName, node.function, resolvedArgs, node.autoApprove)
            val nodeDuration = System.currentTimeMillis() - nodeStartTime
            val execCompleteMsg = "${indent}✅ [TreeExecutor] ${node.layerName}.${node.function} 완료: ${execResult.take(50)}... (${nodeDuration}ms)"
            logger.info(execCompleteMsg)
            
            context.recordNode(node, NodeStatus.SUCCESS, depth, parentNodeId, result = execResult)
        } catch (e: Exception) {
            logger.error("${indent}❌ [TreeExecutor] ${node.layerName}.${node.function} 에러: ${e.message}", e)
            
            context.recordNode(
                node, NodeStatus.FAILED, depth, parentNodeId,
                error = "Error executing ${node.layerName}.${node.function}: ${e.message}"
            )
        }
        
        // 노드 실패 시 재시도는 ReactiveExecutor(ReAct 루프)에서 처리됨
        if (executionResult.isFailure) {
            val failMsg = "${indent}⚠️ [TreeExecutor] 노드 실패: ${node.layerName}.${node.function} (id=$nodeId, depth=$depth)"
            val retryPointMsg = "${indent}   재시도 시작점: ${context.findRetryStartPoint(nodeId)}"
            logger.warn(failMsg)
            logger.debug(retryPointMsg)
            // 재시도 로직은 다음 단계에서 추가
        } else if (executionResult.isSuccess) {
            val successMsg = "${indent}✅ [TreeExecutor] 노드 성공: ${node.layerName}.${node.function} (id=$nodeId)"
            val previewMsg = "${indent}   결과 미리보기: ${executionResult.resultText.take(100)}"
            logger.debug(successMsg)
            logger.debug(previewMsg)
        }
        
        // 자식 노드 실행
        if (node.children.isNotEmpty()) {
            val childrenResults = if (node.parallel) {
                // 병렬 실행
                coroutineScope {
                    node.children.map { child ->
                        async {
                            executeNode(child, context, nodeId, depth + 1)
                        }
                    }.awaitAll()
                }
            } else {
                // 순차 실행
                node.children.map { child ->
                    executeNode(child, context, nodeId, depth + 1)
                }
            }
            
            // 자식 노드 실행 후, 마지막 자식 노드의 결과를 부모 노드의 최종 결과로 사용
            // (순차 실행의 경우 마지막 결과가 최종 결과, 병렬 실행의 경우 모든 결과 결합)
            val allChildrenResults = childrenResults.mapNotNull { it.result }
                .filter { it.isNotEmpty() }
            
            if (allChildrenResults.isNotEmpty()) {
                // 순차 실행: 마지막 자식 노드의 결과만 사용 (최종 결과)
                // 병렬 실행: 모든 자식 노드 결과 결합
                val finalChildResult = if (node.parallel) {
                    allChildrenResults.joinToString("\n")
                } else {
                    allChildrenResults.last() // 순차 실행이면 마지막 결과만
                }
                
                // 최종 상태 결정: 자식 노드들의 상태를 확인
                val finalStatus = if (childrenResults.any { it.isFailure }) {
                    NodeStatus.FAILED
                } else if (childrenResults.all { it.isSuccess }) {
                    NodeStatus.SUCCESS
                } else {
                    executionResult.status
                }
                
                // 자식 노드 중 실패한 것이 있으면 에러 메시지 수집
                val finalError = if (childrenResults.any { it.isFailure }) {
                    childrenResults.filter { it.isFailure }
                        .mapNotNull { it.error }
                        .joinToString("; ")
                } else {
                    executionResult.error
                }
                
                // 부모 노드 결과를 자식 결과로 업데이트
                context.recordNode(
                    node, finalStatus, depth, parentNodeId,
                    result = finalChildResult,
                    error = finalError
                )
                
                return NodeExecutionResult(
                    nodeId = node.id,
                    node = node,
                    status = finalStatus,
                    result = finalChildResult,
                    error = finalError,
                    depth = depth,
                    parentNodeId = parentNodeId
                )
            }
        }
        
        return executionResult
    }

    /**
     * args 내 {{parent}}, {{nodeId:id}} 플레이스홀더를 실행 컨텍스트 결과로 치환.
     * 자식 노드가 부모/특정 노드 실행 결과를 인자로 쓸 수 있게 함 (예: readFile → llm.analyze(context={{parent}}) → writeFile(content={{parent}})).
     */
    private fun resolveArgs(
        args: Map<String, Any>,
        context: ExecutionContext,
        parentNodeId: String?
    ): Map<String, Any> {
        return args.mapValues { (_, value) -> resolveValue(value, context, parentNodeId) }
    }

    private fun resolveValue(
        value: Any?,
        context: ExecutionContext,
        parentNodeId: String?
    ): Any {
        return when (value) {
            is String -> resolveStringPlaceholders(value, context, parentNodeId)
            is Map<*, *> -> (value as Map<*, *>).mapKeys { it.key?.toString() ?: "" }
                .mapValues { resolveValue(it.value, context, parentNodeId) }
            is List<*> -> value.map { resolveValue(it, context, parentNodeId) }
            else -> value ?: ""
        }
    }

    private fun resolveStringPlaceholders(
        s: String,
        context: ExecutionContext,
        parentNodeId: String?
    ): String {
        val parentResult = parentNodeId?.let { context.getResult(it)?.result }.orEmpty()
        var out = if (parentResult.isEmpty()) {
            s.replace("{{parent}}/", "").replace("{{parent}}", "")
        } else {
            s.replace("{{parent}}", parentResult)
        }
        val nodeIdRegex = Regex("""\{\{nodeId:([^}]+)\}\}""")
        out = nodeIdRegex.replace(out) { mr ->
            context.getResult(mr.groupValues[1])?.result.orEmpty()
        }
        return out
    }
}
