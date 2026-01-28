package com.hana.orchestrator.orchestrator

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
     */
    suspend fun executeTree(
        tree: ExecutionTree,
        currentExecution: ExecutionHistory
    ): ExecutionResult {
        val context = ExecutionContext()
        val treeStartTime = System.currentTimeMillis()
        logger.info("🌳 [TreeExecutor] 실행 트리 시작: ${tree.name}")
        
        val result = executeNode(tree.rootNode, context, parentNodeId = null, depth = 0)
        
        val treeDuration = System.currentTimeMillis() - treeStartTime
        logger.perf("⏱️ [PERF] executeTree 총 소요 시간: ${treeDuration}ms")
        
        // 실행 중인 경우 현재 실행 상태 업데이트 (노드 레벨 정보 포함)
        val updatedHistory = statePublisher.updateCurrentExecutionWithContext(
            currentExecution, context, tree, result
        )
        historyManager.setCurrentExecution(updatedHistory)
        
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
        
        // 최종 결과: 루트 노드의 최종 결과를 사용
        val resultText = if (result.isSuccess && result.result != null && result.result.isNotEmpty()) {
            result.result
        } else if (context.completedNodes.isNotEmpty()) {
            // 루트 노드 결과가 없으면 fallback으로 모든 성공 노드 결과 결합
            val allResults = context.completedNodes
                .sortedBy { it.depth }
                .mapNotNull { it.result }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            allResults.ifEmpty { result.resultText }
        } else {
            result.resultText
        }
        
        return ExecutionResult(
            result = resultText,
            executionTree = tree,
            context = context
        )
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
        
        val runningResult = context.recordNode(node, NodeStatus.RUNNING, depth, parentNodeId)
        logger.debug("${indent}🎯 [TreeExecutor] 실행 시작: ${node.layerName}.${node.function} (id=$nodeId, depth=$depth, parent=$parentNodeId, children=${node.children.size}, parallel=${node.parallel})")
        
        val layer = layerManager.findLayerByName(node.layerName)
        
        if (layer == null) {
            val failedResult = context.recordNode(
                node, NodeStatus.FAILED, depth, parentNodeId,
                error = "Layer '${node.layerName}' not found"
            )
            logger.error("${indent}❌ [TreeExecutor] 레이어를 찾을 수 없음: ${node.layerName}")
            return failedResult
        }
        
        // 현재 노드 실행
        val executionResult: NodeExecutionResult = try {
            // 원격 레이어인지 확인
            val isRemote = layer is RemoteLayer
            val remoteUrl = if (isRemote) layer.baseUrl else null
            
            val execStartMsg = "${indent}▶️ [TreeExecutor] ${node.layerName}.${node.function} 실행 중...${if (isRemote) " (원격: $remoteUrl)" else ""}"
            logger.info(execStartMsg)
            val nodeStartTime = System.currentTimeMillis()
            val execResult = layer.execute(node.function, node.args)
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
        
        // 실패 시 여기서 재시도 로직 추가 가능 (나중에)
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
            
            // 자식 결과를 부모 결과에 통합
            val allChildrenResults = childrenResults.mapNotNull { it.result }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            
            if (allChildrenResults.isNotEmpty()) {
                val combinedResult = if (executionResult.result != null && executionResult.result.isNotEmpty()) {
                    "${executionResult.result}\n$allChildrenResults"
                } else {
                    allChildrenResults
                }
                
                // 부모 노드 결과 업데이트
                context.recordNode(
                    node, executionResult.status, depth, parentNodeId,
                    result = combinedResult,
                    error = executionResult.error
                )
                
                return NodeExecutionResult(
                    nodeId = node.id,
                    node = node,
                    status = executionResult.status,
                    result = combinedResult,
                    error = executionResult.error,
                    depth = depth,
                    parentNodeId = parentNodeId
                )
            }
        }
        
        return executionResult
    }
}
