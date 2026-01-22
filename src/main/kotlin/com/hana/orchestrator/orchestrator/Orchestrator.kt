package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.layer.LayerFactory
import com.hana.orchestrator.layer.CommonLayerInterface
import com.hana.orchestrator.llm.OllamaLLMClient
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionNode
import com.hana.orchestrator.domain.entity.NodeExecutionResult
import com.hana.orchestrator.domain.entity.NodeStatus
import com.hana.orchestrator.domain.entity.ExecutionContext
import com.hana.orchestrator.domain.entity.ExecutionResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope



class Orchestrator : CommonLayerInterface {
    
    private val layers = mutableListOf<CommonLayerInterface>()
    private val llmClient = OllamaLLMClient()
    
    init {
        initializeDefaultLayers()
    }
    
    private fun initializeDefaultLayers() {
        val defaultLayers = LayerFactory.createDefaultLayers()
        layers.addAll(defaultLayers)
    }
    
    fun registerLayer(layer: CommonLayerInterface) {
        layers.add(layer)
    }
    
    private val cachedDescriptions = mutableSetOf<com.hana.orchestrator.layer.LayerDescription>()
    
    suspend fun getAllLayerDescriptions(): List<com.hana.orchestrator.layer.LayerDescription> {
        if (cachedDescriptions.isEmpty()) {
            cachedDescriptions.addAll(layers.map { it.describe() })
        }
        return cachedDescriptions.toList()
    }
    
    suspend fun executeOnLayer(layerName: String, function: String, args: Map<String, Any> = emptyMap()): String {
        val targetLayer = layers.find { layer ->
            layer.describe().name == layerName
        }
        
        if (targetLayer == null) {
            val availableLayers = layers.map { it.describe().name }
            return "Layer '$layerName' not found. Available layers: $availableLayers"
        }
        
        return targetLayer.execute(function, args)
    }
    
    suspend fun executeOnAllLayers(function: String, args: Map<String, Any> = emptyMap()): List<String> {
        val layerDescriptions = layers.map { it.describe() }
        val sortedLayers = layerDescriptions.map { description ->
            val layer = layers.find { it.describe().name == description.name }!!
            description to layer
        }
        
        return sortedLayers.map { (description, layer) ->
            try {
                val result = layer.execute(function, args)
                "[${description.name}] $result"
            } catch (e: Exception) {
                "[${description.name}] Error: ${e.message}"
            }
        }
    }
    
    override suspend fun describe(): com.hana.orchestrator.layer.LayerDescription {
        val allDescriptions = getAllLayerDescriptions()
        return com.hana.orchestrator.layer.LayerDescription(
            name = "orchestrator",
            description = "등록된 레이어들을 관리하고 실행: ${allDescriptions.map { it.name }}",
            functions = allDescriptions.flatMap { it.functions }
        )
    }
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        // 레거시 호환성을 위해 String 반환 유지
        val query = args["query"] as? String
        if (query != null) {
            val result = executeOrchestration(query)
            return result.result
        }
        
        // query가 없으면 자식 레이어의 함수명으로 위임
        val allDescriptions = getAllLayerDescriptions()
        val targetLayer = layers.find { it.describe().name == function }
        return if (targetLayer != null) {
            executeOnLayer(function, "process", args)
        } else {
            val allFunctions = allDescriptions.flatMap { it.functions }
            "Unknown function: $function. Available: ${allFunctions.joinToString(", ")}"
        }
    }
    
    /**
     * 오케스트레이션 실행 (도메인 모델 반환)
     */
    suspend fun executeOrchestration(query: String): ExecutionResult {
        val allDescriptions = getAllLayerDescriptions()
        
        return if (query.isNotEmpty()) {
            // 사용자 요청이 있으면 LLM으로 트리 생성 후 검증 및 실행
            println("🔍 [Orchestrator] 사용자 쿼리 수신: $query")
            val rawTree = llmClient.createExecutionTree(query, allDescriptions)
            println("🌳 [Orchestrator] LLM 트리 생성 완료: rootNode=${rawTree.rootNode.layerName}.${rawTree.rootNode.function}, children=${rawTree.rootNode.children.size}")
            
            // 트리 검증 및 자동 수정
            val validator = ExecutionTreeValidator(allDescriptions)
            val validationResult = validator.validateAndFix(rawTree, query)
            
            // 검증된 트리 실행 (에러가 있으면 수정된 트리 사용)
            val treeToExecute = validationResult.fixedTree ?: rawTree
            
            // 경고가 있으면 로그 출력
            if (validationResult.warnings.isNotEmpty()) {
                println("⚠️ [Orchestrator] 트리 검증 경고:")
                validationResult.warnings.forEach { println("  - $it") }
            }
            
            // 에러가 있으면 로그 출력
            if (validationResult.errors.isNotEmpty()) {
                println("❌ [Orchestrator] 트리 검증 에러:")
                validationResult.errors.forEach { println("  - $it") }
                println("📝 [Orchestrator] 수정된 트리로 실행합니다.")
            } else {
                println("✅ [Orchestrator] 트리 검증 통과")
            }
            
            println("🚀 [Orchestrator] 트리 실행 시작...")
            val result = executeTree(treeToExecute)
            println("✅ [Orchestrator] 트리 실행 완료")
            result
        } else {
            // 빈 쿼리인 경우 기본 결과 반환
            ExecutionResult(result = "Empty query")
        }
    }
    
    /**
     * ExecutionTree를 재귀적으로 실행
     */
    private suspend fun executeTree(tree: ExecutionTree): ExecutionResult {
        val context = ExecutionContext()
        println("🌳 [executeTree] 실행 트리 시작: ${tree.name}")
        
        val result = executeNode(tree.rootNode, context, parentNodeId = null, depth = 0)
        
        // 실행 완료 후 전체 상태 로그 출력
        println("\n📊 [executeTree] ========== 실행 결과 요약 ==========")
        println("✅ 성공한 노드: ${context.completedNodes.size}개")
        context.completedNodes.forEach { nodeResult ->
            println("   - ${nodeResult.nodeId}: ${nodeResult.node.layerName}.${nodeResult.node.function} (depth=${nodeResult.depth})")
        }
        
        println("❌ 실패한 노드: ${context.failedNodes.size}개")
        context.failedNodes.forEach { nodeResult ->
            println("   - ${nodeResult.nodeId}: ${nodeResult.node.layerName}.${nodeResult.node.function} (depth=${nodeResult.depth})")
            println("     에러: ${nodeResult.error}")
        }
        
        println("⏭️ 건너뛴 노드: ${context.nodeResults.values.filter { it.status == NodeStatus.SKIPPED }.size}개")
        context.nodeResults.values.filter { it.status == NodeStatus.SKIPPED }.forEach { nodeResult ->
            println("   - ${nodeResult.nodeId}: ${nodeResult.node.layerName}.${nodeResult.node.function} (부모 실패로 인해 건너뜀)")
        }
        
        println("📊 전체 노드 수: ${context.nodeResults.size}개")
        println("==========================================\n")
        
        return ExecutionResult(
            result = result.result ?: result.error ?: "Unknown error",
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
            val skippedResult = NodeExecutionResult(
                nodeId = nodeId,
                node = node,
                status = NodeStatus.SKIPPED,
                error = "Parent node failed",
                depth = depth,
                parentNodeId = parentNodeId
            )
            context.recordResult(skippedResult)
            println("${indent}⏭️ [executeNode] 건너뜀: ${node.layerName}.${node.function} (부모 실패)")
            return skippedResult
        }
        
        // 실행 시작
        val runningResult = NodeExecutionResult(
            nodeId = nodeId,
            node = node,
            status = NodeStatus.RUNNING,
            depth = depth,
            parentNodeId = parentNodeId
        )
        context.recordResult(runningResult)
        println("${indent}🎯 [executeNode] 실행 시작: ${node.layerName}.${node.function} (id=$nodeId, depth=$depth, parent=$parentNodeId, children=${node.children.size}, parallel=${node.parallel})")
        
        val layer = layers.find { it.describe().name == node.layerName }
        
        if (layer == null) {
            val failedResult = NodeExecutionResult(
                nodeId = nodeId,
                node = node,
                status = NodeStatus.FAILED,
                error = "Layer '${node.layerName}' not found",
                depth = depth,
                parentNodeId = parentNodeId
            )
            context.recordResult(failedResult)
            println("${indent}❌ [executeNode] 레이어를 찾을 수 없음: ${node.layerName}")
            return failedResult
        }
        
        // 현재 노드 실행
        val executionResult = try {
            println("${indent}▶️ [executeNode] ${node.layerName}.${node.function} 실행 중...")
            val execResult = layer.execute(node.function, node.args)
            println("${indent}✅ [executeNode] ${node.layerName}.${node.function} 완료: ${execResult.take(50)}...")
            
            NodeExecutionResult(
                nodeId = nodeId,
                node = node,
                status = NodeStatus.SUCCESS,
                result = execResult,
                depth = depth,
                parentNodeId = parentNodeId
            )
        } catch (e: Exception) {
            println("${indent}❌ [executeNode] ${node.layerName}.${node.function} 에러: ${e.message}")
            
            NodeExecutionResult(
                nodeId = nodeId,
                node = node,
                status = NodeStatus.FAILED,
                error = "Error executing ${node.layerName}.${node.function}: ${e.message}",
                depth = depth,
                parentNodeId = parentNodeId
            )
        }
        
        context.recordResult(executionResult)
        
        // 실패 시 여기서 재시도 로직 추가 가능 (나중에)
        if (executionResult.status == NodeStatus.FAILED) {
            println("${indent}⚠️ [executeNode] 노드 실패: ${node.layerName}.${node.function} (id=$nodeId, depth=$depth)")
            println("${indent}   재시도 시작점: ${context.findRetryStartPoint(nodeId)}")
            // 재시도 로직은 다음 단계에서 추가
        } else if (executionResult.status == NodeStatus.SUCCESS) {
            println("${indent}✅ [executeNode] 노드 성공: ${node.layerName}.${node.function} (id=$nodeId)")
            println("${indent}   결과 미리보기: ${executionResult.result?.take(100) ?: "null"}")
        }
        
        // 자식 노드 실행
        if (node.children.isEmpty()) {
            return executionResult
        }
        
        println("${indent}📦 [executeNode] 자식 노드 ${node.children.size}개 실행 (parallel=${node.parallel})")
        val childResults = if (node.parallel) {
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
            node.children.map { executeNode(it, context, nodeId, depth + 1) }
        }
        
        // 자식 노드 실패 체크
        val failedChildren = childResults.filter { it.status == NodeStatus.FAILED }
        if (failedChildren.isNotEmpty() && executionResult.status == NodeStatus.SUCCESS) {
            // 부모는 성공했지만 자식이 실패한 경우
            println("${indent}⚠️ [executeNode] 자식 노드 실패: ${failedChildren.size}개")
        }
        
        // 결과 결합 (성공한 자식들의 결과만)
        val successfulResults = childResults.filter { it.status == NodeStatus.SUCCESS }
        val finalResultText = (listOfNotNull(executionResult.result) + successfulResults.mapNotNull { it.result })
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        
        println("${indent}🏁 [executeNode] ${node.layerName} 최종 결과: ${finalResultText.take(50)}...")
        
        // 최종 결과 업데이트 (자식 결과 포함)
        val finalResult = executionResult.copy(result = finalResultText)
        context.recordResult(finalResult)
        return finalResult
    }
    
    /**
     * 리소스 정리 (메모리 누수 방지)
     */
    suspend fun close() {
        llmClient.close()
    }
    
}