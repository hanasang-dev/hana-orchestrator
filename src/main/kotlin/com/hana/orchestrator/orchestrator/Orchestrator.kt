package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.layer.LayerFactory
import com.hana.orchestrator.layer.CommonLayerInterface
import com.hana.orchestrator.layer.RemoteLayer
import com.hana.orchestrator.llm.OllamaLLMClient
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionNode
import com.hana.orchestrator.domain.entity.NodeExecutionResult
import com.hana.orchestrator.domain.entity.NodeStatus
import com.hana.orchestrator.domain.entity.ExecutionContext
import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.domain.entity.ExecutionHistory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow



class Orchestrator : CommonLayerInterface {
    
    private val layers = mutableListOf<CommonLayerInterface>()
    private val llmClient = OllamaLLMClient()
    private val executionHistory = mutableListOf<ExecutionHistory>()
    private var currentExecution: ExecutionHistory? = null
    
    // 실시간 업데이트를 위한 Flow
    private val _executionUpdates = MutableSharedFlow<ExecutionHistory>(replay = 1, extraBufferCapacity = 10)
    val executionUpdates: SharedFlow<ExecutionHistory> = _executionUpdates.asSharedFlow()
    
    init {
        initializeDefaultLayers()
    }
    
    fun getExecutionHistory(limit: Int = 50): List<ExecutionHistory> {
        return executionHistory.takeLast(limit).reversed()
    }
    
    fun getCurrentExecution(): ExecutionHistory? {
        return currentExecution
    }
    
    /**
     * 실행 상태 업데이트를 Flow에 emit
     */
    private suspend fun emitExecutionUpdate(history: ExecutionHistory) {
        _executionUpdates.emit(history)
    }
    
    /**
     * 현재 실행 상태를 컨텍스트 정보로 업데이트
     * SRP: 상태 업데이트 로직 분리
     */
    private suspend fun updateCurrentExecutionWithContext(
        context: ExecutionContext,
        tree: ExecutionTree,
        nodeResult: NodeExecutionResult
    ) {
        val current = currentExecution ?: return
        
        val resultText = extractResultText(nodeResult)
        val updatedHistory = current.copy(
            result = ExecutionResult(
                result = resultText,
                executionTree = tree,
                context = context
            )
        )
        currentExecution = updatedHistory
        emitExecutionUpdate(updatedHistory)
    }
    
    /**
     * 노드 결과에서 텍스트 추출
     * SRP: 결과 추출 로직 분리 (NodeExecutionResult의 resultText 사용)
     */
    private fun extractResultText(nodeResult: NodeExecutionResult): String {
        return nodeResult.resultText
    }
    
    private fun initializeDefaultLayers() {
        val defaultLayers = LayerFactory.createDefaultLayers()
        layers.addAll(defaultLayers)
    }
    
    fun registerLayer(layer: CommonLayerInterface) {
        layers.add(layer)
        // 레이어 등록 시 캐시 무효화
        cachedDescriptions.clear()
    }
    
    private val cachedDescriptions = mutableSetOf<com.hana.orchestrator.layer.LayerDescription>()
    
    suspend fun getAllLayerDescriptions(): List<com.hana.orchestrator.layer.LayerDescription> {
        // 캐시가 비어있거나 레이어 수가 변경되었으면 갱신
        if (cachedDescriptions.isEmpty() || cachedDescriptions.size != layers.size) {
            cachedDescriptions.clear()
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
     * LLM 기반 자동 재처리 루프 포함
     */
    suspend fun executeOrchestration(query: String): ExecutionResult {
        val allDescriptions = getAllLayerDescriptions()
        
        return if (query.isNotEmpty()) {
            val executionId = java.util.UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis()
            
            // 실행 이력 생성 및 Flow에 emit
            val runningHistory = ExecutionHistory.createRunning(executionId, query, startTime)
            currentExecution = runningHistory
            emitExecutionUpdate(runningHistory)
            
            var previousHistory: ExecutionHistory? = null
            var previousTree: ExecutionTree? = null
            var attemptCount = 0
            val maxAttempts = 5 // 최대 재시도 횟수 (안전장치)
            
            while (attemptCount < maxAttempts) {
                attemptCount++
                println("\n🔄 [Orchestrator] 실행 시도 #$attemptCount")
                
                try {
                    // LLM으로 트리 생성
                    val rawTree = if (attemptCount == 1) {
                        println("🔍 [Orchestrator] 사용자 쿼리 수신: $query")
                        llmClient.createExecutionTree(query, allDescriptions)
                    } else {
                        // 재처리: LLM이 재처리 방안 제시
                        println("🔧 [Orchestrator] 재처리 방안 요청 중...")
                        val retryStrategy = llmClient.suggestRetryStrategy(query, previousHistory!!, allDescriptions)
                        
                        if (retryStrategy.shouldStop) {
                            println("🛑 [Orchestrator] LLM 판단: 근본 해결 불가능 - ${retryStrategy.reason}")
                            val finalHistory = ExecutionHistory.createFailed(
                                executionId, query, 
                                "재처리 중단: ${retryStrategy.reason}", 
                                startTime
                            )
                            executionHistory.add(finalHistory)
                            currentExecution = null
                            emitExecutionUpdate(finalHistory)
                            return ExecutionResult(result = "", error = retryStrategy.reason)
                        }
                        
                        println("✅ [Orchestrator] 재처리 방안 수신: ${retryStrategy.reason}")
                        retryStrategy.newTree ?: throw IllegalStateException("재처리 트리가 null입니다")
                    }
                    
                    println("🌳 [Orchestrator] 실행 트리: rootNode=${rawTree.rootNode.layerName}.${rawTree.rootNode.function}, children=${rawTree.rootNode.children.size}")
                    
                    // 트리 검증 및 자동 수정
                    val validator = ExecutionTreeValidator(allDescriptions)
                    val validationResult = validator.validateAndFix(rawTree, query)
                    
                    val treeToExecute = validationResult.fixedTree ?: rawTree
                    
                    if (validationResult.warnings.isNotEmpty()) {
                        println("⚠️ [Orchestrator] 트리 검증 경고:")
                        validationResult.warnings.forEach { println("  - $it") }
                    }
                    
                    if (validationResult.errors.isNotEmpty()) {
                        println("❌ [Orchestrator] 트리 검증 에러:")
                        validationResult.errors.forEach { println("  - $it") }
                        println("📝 [Orchestrator] 수정된 트리로 실행합니다.")
                    }
                    
                    // 트리 실행
                    println("🚀 [Orchestrator] 트리 실행 시작...")
                    val result = executeTree(treeToExecute)
                    println("✅ [Orchestrator] 트리 실행 완료")
                    
                    // 실행 결과 평가 (LLM이 판단)
                    println("🤔 [Orchestrator] 실행 결과 평가 중...")
                    val evaluation = llmClient.evaluateResult(query, result.result, result.context)
                    println("📊 [Orchestrator] 평가 결과: ${if (evaluation.isSatisfactory) "요구사항 부합" else "요구사항 미부합"} - ${evaluation.reason}")
                    
                    // 실행 완료 이력 저장
                    val history = ExecutionHistory.createCompleted(executionId, query, result, startTime)
                    executionHistory.add(history)
                    emitExecutionUpdate(history)
                    
                    // 요구사항 부합 여부 확인
                    if (evaluation.isSatisfactory && !evaluation.needsRetry) {
                        // 성공: 요구사항 부합하고 재처리 불필요
                        println("✅ [Orchestrator] 실행 성공: 요구사항 부합")
                        currentExecution = null
                        return result
                    }
                    
                    // 재처리 필요 또는 요구사항 미부합
                    if (evaluation.needsRetry) {
                        println("🔄 [Orchestrator] 재처리 필요: ${evaluation.reason}")
                        
                        // 이전 실행과 비교하여 유의미한 차이 확인
                        if (previousHistory != null && previousTree != null) {
                            println("🔍 [Orchestrator] 이전 실행과 비교 중...")
                            val comparison = llmClient.compareExecutions(
                                query,
                                previousTree,
                                previousHistory.result.result,
                                treeToExecute,
                                result.result
                            )
                            
                            if (!comparison.isSignificantlyDifferent) {
                                println("⚠️ [Orchestrator] 유의미한 변경 없음: ${comparison.reason}")
                                println("🛑 [Orchestrator] 무한 루프 방지: 재처리 중단")
                                currentExecution = null
                                return result // 현재 결과 반환
                            }
                            
                            println("✅ [Orchestrator] 유의미한 차이 확인: ${comparison.reason}")
                        }
                        
                        // 재처리 루프 계속
                        previousHistory = history
                        previousTree = treeToExecute
                        currentExecution = ExecutionHistory.createRunning(executionId, query, System.currentTimeMillis())
                        emitExecutionUpdate(currentExecution!!)
                        continue
                    }
                    
                    // 평가 실패 또는 기타 경우: 현재 결과 반환
                    currentExecution = null
                    return result
                    
                } catch (e: Exception) {
                    println("❌ [Orchestrator] 실행 실패: ${e.message}")
                    
                    // 실패 이력 저장
                    val failedHistory = ExecutionHistory.createFailed(executionId, query, e.message, startTime)
                    executionHistory.add(failedHistory)
                    emitExecutionUpdate(failedHistory)
                    
                    // 재처리 가능 여부 확인
                    if (attemptCount >= maxAttempts) {
                        println("🛑 [Orchestrator] 최대 재시도 횟수 도달: 중단")
                        currentExecution = null
                        throw e
                    }
                    
                    // 재처리 방안 요청
                    if (previousHistory == null) {
                        previousHistory = failedHistory
                    }
                    
                    println("🔧 [Orchestrator] 실패 분석 및 재처리 방안 요청 중...")
                    val retryStrategy = llmClient.suggestRetryStrategy(query, previousHistory, allDescriptions)
                    
                    if (retryStrategy.shouldStop) {
                        println("🛑 [Orchestrator] LLM 판단: 근본 해결 불가능 - ${retryStrategy.reason}")
                        currentExecution = null
                        throw Exception("재처리 중단: ${retryStrategy.reason}")
                    }
                    
                    println("✅ [Orchestrator] 재처리 방안 수신: ${retryStrategy.reason}")
                    previousHistory = failedHistory
                    previousTree = failedHistory.result.executionTree
                    currentExecution = ExecutionHistory.createRunning(executionId, query, System.currentTimeMillis())
                    emitExecutionUpdate(currentExecution!!)
                    continue
                }
            }
            
            // 최대 재시도 횟수 도달
            println("🛑 [Orchestrator] 최대 재시도 횟수 도달")
            currentExecution = null
            ExecutionResult(result = "", error = "최대 재시도 횟수 도달")
            
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
        
        // 실행 중인 경우 현재 실행 상태 업데이트 (노드 레벨 정보 포함)
        updateCurrentExecutionWithContext(context, tree, result)
        
        // 실행 완료 후 전체 상태 로그 출력
        println("\n📊 [executeTree] ========== 실행 결과 요약 ==========")
        println("✅ 성공한 노드: ${context.completedNodes.size}개")
        context.completedNodes.forEach { nodeResult ->
            println("   - ${nodeResult.nodeId}: ${nodeResult.node.layerName}.${nodeResult.node.function} (depth=${nodeResult.depth})")
        }
        
        println("❌ 실패한 노드: ${context.failedNodes.size}개")
        context.failedNodes.forEach { nodeResult ->
            println("   - ${nodeResult.nodeId}: ${nodeResult.node.layerName}.${nodeResult.node.function} (depth=${nodeResult.depth})")
            val errorText = nodeResult.error ?: "Unknown error"
            println("     에러: $errorText")
        }
        
        val skippedCount = context.countByStatus(NodeStatus.SKIPPED)
        println("⏭️ 건너뛴 노드: ${skippedCount}개")
        context.getAllResults().values.filter { it.isSkipped }.forEach { nodeResult ->
            println("   - ${nodeResult.nodeId}: ${nodeResult.node.layerName}.${nodeResult.node.function} (부모 실패로 인해 건너뜀)")
        }
        
        println("📊 전체 노드 수: ${context.getAllResults().size}개")
        println("==========================================\n")
        
        val resultText = extractResultText(result)
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
            println("${indent}⏭️ [executeNode] 건너뜀: ${node.layerName}.${node.function} (부모 실패)")
            return skippedResult
        }
        
        val runningResult = context.recordNode(node, NodeStatus.RUNNING, depth, parentNodeId)
        println("${indent}🎯 [executeNode] 실행 시작: ${node.layerName}.${node.function} (id=$nodeId, depth=$depth, parent=$parentNodeId, children=${node.children.size}, parallel=${node.parallel})")
        
        val layer = layers.find { it.describe().name == node.layerName }
        
        if (layer == null) {
            val failedResult = context.recordNode(
                node, NodeStatus.FAILED, depth, parentNodeId,
                error = "Layer '${node.layerName}' not found"
            )
            println("${indent}❌ [executeNode] 레이어를 찾을 수 없음: ${node.layerName}")
            return failedResult
        }
        
        // 현재 노드 실행
        val executionResult: NodeExecutionResult = try {
            // 원격 레이어인지 확인
            val isRemote = layer is RemoteLayer
            val remoteUrl = if (isRemote) layer.baseUrl else null
            
            println("${indent}▶️ [executeNode] ${node.layerName}.${node.function} 실행 중...${if (isRemote) " (원격: $remoteUrl)" else ""}")
            val execResult = layer.execute(node.function, node.args)
            println("${indent}✅ [executeNode] ${node.layerName}.${node.function} 완료: ${execResult.take(50)}...")
            
            context.recordNode(node, NodeStatus.SUCCESS, depth, parentNodeId, result = execResult)
        } catch (e: Exception) {
            println("${indent}❌ [executeNode] ${node.layerName}.${node.function} 에러: ${e.message}")
            
            context.recordNode(
                node, NodeStatus.FAILED, depth, parentNodeId,
                error = "Error executing ${node.layerName}.${node.function}: ${e.message}"
            )
        }
        
        // 실패 시 여기서 재시도 로직 추가 가능 (나중에)
        if (executionResult.isFailure) {
            println("${indent}⚠️ [executeNode] 노드 실패: ${node.layerName}.${node.function} (id=$nodeId, depth=$depth)")
            println("${indent}   재시도 시작점: ${context.findRetryStartPoint(nodeId)}")
            // 재시도 로직은 다음 단계에서 추가
        } else if (executionResult.isSuccess) {
            println("${indent}✅ [executeNode] 노드 성공: ${node.layerName}.${node.function} (id=$nodeId)")
            println("${indent}   결과 미리보기: ${executionResult.resultText.take(100)}")
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
        val failedChildren = childResults.filter { it.isFailure }
        if (failedChildren.isNotEmpty() && executionResult.isSuccess) {
            // 부모는 성공했지만 자식이 실패한 경우
            println("${indent}⚠️ [executeNode] 자식 노드 실패: ${failedChildren.size}개")
        }
        
        // 결과 결합 (성공한 자식들의 결과만)
        val successfulResults = childResults.filter { it.isSuccess }
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