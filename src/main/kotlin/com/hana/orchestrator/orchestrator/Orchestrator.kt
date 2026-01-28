package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.layer.LayerFactory
import com.hana.orchestrator.layer.CommonLayerInterface
import com.hana.orchestrator.layer.RemoteLayer
import com.hana.orchestrator.llm.LLMClient
import com.hana.orchestrator.llm.OllamaLLMClient
import com.hana.orchestrator.llm.QueryFeasibility
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionNode
import com.hana.orchestrator.domain.entity.NodeExecutionResult
import com.hana.orchestrator.domain.entity.NodeStatus
import com.hana.orchestrator.domain.entity.ExecutionContext
import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.llm.strategy.GeneratedModelSelectionStrategy
import com.hana.orchestrator.llm.factory.LLMClientFactory
import com.hana.orchestrator.llm.factory.DefaultLLMClientFactory
import com.hana.orchestrator.llm.useSuspend
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers



class Orchestrator(
    private val llmConfig: LLMConfig? = null
) : CommonLayerInterface {
    
    private val layers = mutableListOf<CommonLayerInterface>()
    
    // LLM 클라이언트 팩토리 (병렬 처리 및 확장성 지원)
    private val clientFactory: LLMClientFactory
    
    // 모델 선택 전략 (KSP가 자동 생성한 클래스 사용)
    private val modelSelectionStrategy: ModelSelectionStrategy
    
    init {
        // LLM 설정이 있으면 사용, 없으면 기본값 (하위 호환성)
        val config = llmConfig ?: LLMConfig.fromEnvironment()
        
        // Factory 생성 (필요할 때마다 클라이언트 인스턴스 생성)
        clientFactory = DefaultLLMClientFactory(config)
        
        // 전략 인스턴스 생성 (KSP가 생성한 클래스 사용)
        // Factory를 주입하여 필요할 때마다 새로운 클라이언트 생성 가능
        modelSelectionStrategy = GeneratedModelSelectionStrategy(
            clientFactory = clientFactory
        )
    }
    private val executionHistory = mutableListOf<ExecutionHistory>()
    private var currentExecution: ExecutionHistory? = null
    
    // 실시간 업데이트를 위한 Flow
    private val _executionUpdates = MutableSharedFlow<ExecutionHistory>(replay = 1, extraBufferCapacity = 10)
    val executionUpdates: SharedFlow<ExecutionHistory> = _executionUpdates.asSharedFlow()
    
    init {
        println("🚀 [Orchestrator] 초기화 시작...")
        initializeDefaultLayers()
        println("🎯 [Orchestrator] 초기화 완료. 등록된 레이어: ${layers.size}개")
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
     * 현재 실행에 로그 추가
     */
    private fun addLog(message: String) {
        val current = currentExecution ?: return
        val timestamp = System.currentTimeMillis()
        val timeStr = java.time.Instant.ofEpochMilli(timestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        val logMessage = "[$timeStr] $message"
        current.logs.add(logMessage)
        // 로그 추가 시에도 업데이트 전송 (비동기)
        CoroutineScope(Dispatchers.Default).launch {
            emitExecutionUpdate(current)
        }
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
        println("🔧 [Orchestrator] 기본 레이어 초기화: ${defaultLayers.size}개 레이어 등록")
        defaultLayers.forEach { layer ->
            println("  - 레이어 인스턴스 생성됨: ${layer::class.simpleName}")
        }
        layers.addAll(defaultLayers)
        println("✅ [Orchestrator] 총 ${layers.size}개 레이어 등록 완료")
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
        // 모든 레이어의 functionDetails를 병합
        val mergedFunctionDetails = allDescriptions
            .flatMap { it.functionDetails.entries }
            .associate { it.key to it.value }
        
        return com.hana.orchestrator.layer.LayerDescription(
            name = "orchestrator",
            description = "등록된 레이어들을 관리하고 실행: ${allDescriptions.map { it.name }}",
            functions = allDescriptions.flatMap { it.functions },
            functionDetails = mergedFunctionDetails
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
            addLog("🚀 실행 시작: $query")
            emitExecutionUpdate(runningHistory)
            
            var previousHistory: ExecutionHistory? = null
            var previousTree: ExecutionTree? = null
            var attemptCount = 0
            val maxAttempts = 5 // 최대 재시도 횟수 (안전장치)
            
            while (attemptCount < maxAttempts) {
                attemptCount++
                val attemptMsg = "\n🔄 [Orchestrator] 실행 시도 #$attemptCount"
                println(attemptMsg)
                addLog(attemptMsg)
                
                try {
                    // LLM으로 트리 생성
                    val rawTree = if (attemptCount == 1) {
                        val queryMsg = "🔍 [Orchestrator] 사용자 쿼리 수신: $query"
                        println(queryMsg)
                        addLog(queryMsg)
                        
                        // 요구사항 실행 가능성 사전 검증
                        val feasibilityCheckMsg = "🔎 [Orchestrator] 요구사항 실행 가능성 검증 중..."
                        println(feasibilityCheckMsg)
                        addLog(feasibilityCheckMsg)
                        val feasibilityStartTime = System.currentTimeMillis()
                        val feasibility = try {
                            modelSelectionStrategy.selectClientForFeasibilityCheck()
                                .validateQueryFeasibility(query, allDescriptions)
                        } catch (feasibilityException: Exception) {
                            val errorMsg = "⚠️ [Orchestrator] 요구사항 검증 실패: ${feasibilityException.message}, 트리 생성 계속 진행"
                            println(errorMsg)
                            addLog(errorMsg)
                            // 검증 실패해도 트리 생성은 계속 진행 (검증이 실패해도 실행 가능할 수 있음)
                            QueryFeasibility(feasible = true, reason = "검증 실패로 인해 계속 진행")
                        }
                        val feasibilityDuration = System.currentTimeMillis() - feasibilityStartTime
                        val feasibilityPerfMsg = "⏱️ [PERF] 요구사항 검증 완료: ${feasibilityDuration}ms"
                        println(feasibilityPerfMsg)
                        addLog(feasibilityPerfMsg)
                        
                        if (!feasibility.feasible) {
                            val rejectionMsg = "❌ [Orchestrator] 요구사항 실행 불가능: ${feasibility.reason}"
                            println(rejectionMsg)
                            addLog(rejectionMsg)
                            val suggestionMsg = if (feasibility.suggestion != null) {
                                "💡 [Orchestrator] 제안: ${feasibility.suggestion}"
                            } else {
                                null
                            }
                            if (suggestionMsg != null) {
                                println(suggestionMsg)
                                addLog(suggestionMsg)
                            }
                            
                            val errorMessage = if (feasibility.suggestion != null) {
                                "${feasibility.reason}\n\n제안: ${feasibility.suggestion}"
                            } else {
                                feasibility.reason
                            }
                            
                            val failedHistory = ExecutionHistory.createFailed(
                                executionId, query,
                                errorMessage,
                                startTime,
                                logs = currentExecution?.logs ?: mutableListOf()
                            )
                            executionHistory.add(failedHistory)
                            emitExecutionUpdate(failedHistory)
                            currentExecution = null
                            return ExecutionResult(result = "", error = errorMessage)
                        }
                        
                        val feasibleMsg = "✅ [Orchestrator] 요구사항 실행 가능: ${feasibility.reason}"
                        println(feasibleMsg)
                        addLog(feasibleMsg)
                        
                        val treeStartMsg = "🌳 [Orchestrator] 실행 트리 생성 시작..."
                        println(treeStartMsg)
                        addLog(treeStartMsg)
                        
                        val treeStartTime = System.currentTimeMillis()
                        val tree = try {
                            modelSelectionStrategy.selectClientForTreeCreation()
                                .useSuspend { client ->
                                    client.createExecutionTree(query, allDescriptions)
                                }
                        } catch (treeException: Exception) {
                            val errorMsg = "❌ [Orchestrator] 트리 생성 실패: ${treeException.message}"
                            println(errorMsg)
                            addLog(errorMsg)
                            
                            // 로그 복사 (currentExecution이 null이 되기 전에)
                            val logsCopy = currentExecution?.logs?.toMutableList() ?: mutableListOf()
                            
                            val failedHistory = ExecutionHistory.createFailed(
                                executionId, query,
                                "트리 생성 실패: ${treeException.message}",
                                startTime,
                                logs = logsCopy
                            )
                            executionHistory.add(failedHistory)
                            currentExecution = null
                            emitExecutionUpdate(failedHistory)
                            return ExecutionResult(result = "", error = "트리 생성 실패: ${treeException.message}")
                        }
                        val treeDuration = System.currentTimeMillis() - treeStartTime
                        val perfMsg = "⏱️ [PERF] 트리 생성 완료: ${treeDuration}ms"
                        println(perfMsg)
                        addLog(perfMsg)
                        tree
                    } else {
                        // 재처리: LLM이 재처리 방안 제시
                        val retryMsg = "🔧 [Orchestrator] 재처리 방안 요청 중..."
                        println(retryMsg)
                        addLog(retryMsg)
                        val retryStartTime = System.currentTimeMillis()
                        val retryStrategy = try {
                            modelSelectionStrategy.selectClientForRetryStrategy()
                                .useSuspend { client ->
                                    client.suggestRetryStrategy(query, previousHistory!!, allDescriptions)
                                }
                        } catch (retryException: Exception) {
                            val errorMsg = "❌ [Orchestrator] 재처리 방안 요청 실패: ${retryException.message}"
                            println(errorMsg)
                            addLog(errorMsg)
                            val finalHistory = ExecutionHistory.createFailed(
                                executionId, query,
                                "재처리 방안 요청 실패: ${retryException.message}",
                                startTime,
                                logs = currentExecution?.logs ?: mutableListOf()
                            )
                            executionHistory.add(finalHistory)
                            emitExecutionUpdate(finalHistory)
                            currentExecution = null
                            return ExecutionResult(result = "", error = "재처리 방안 요청 실패: ${retryException.message}")
                        }
                        val retryDuration = System.currentTimeMillis() - retryStartTime
                        val perfMsg = "⏱️ [PERF] 재처리 방안 생성: ${retryDuration}ms"
                        println(perfMsg)
                        addLog(perfMsg)
                        
                        if (retryStrategy.shouldStop) {
                            val stopMsg = "🛑 [Orchestrator] LLM 판단: 근본 해결 불가능 - ${retryStrategy.reason}"
                            println(stopMsg)
                            addLog(stopMsg)
                            val finalHistory = ExecutionHistory.createFailed(
                                executionId, query, 
                                "재처리 중단: ${retryStrategy.reason}", 
                                startTime,
                                logs = currentExecution?.logs ?: mutableListOf()
                            )
                            executionHistory.add(finalHistory)
                            currentExecution = null
                            emitExecutionUpdate(finalHistory)
                            return ExecutionResult(result = "", error = retryStrategy.reason)
                        }
                        
                        val successMsg = "✅ [Orchestrator] 재처리 방안 수신: ${retryStrategy.reason}"
                        println(successMsg)
                        addLog(successMsg)
                        retryStrategy.newTree ?: run {
                            val errorMsg = "재처리 트리가 null입니다"
                            addLog("❌ $errorMsg")
                            val finalHistory = ExecutionHistory.createFailed(
                                executionId, query,
                                errorMsg,
                                startTime,
                                logs = currentExecution?.logs ?: mutableListOf()
                            )
                            executionHistory.add(finalHistory)
                            emitExecutionUpdate(finalHistory)
                            currentExecution = null
                            return ExecutionResult(result = "", error = errorMsg)
                        }
                    }
                    
                    val treeMsg = "🌳 [Orchestrator] 실행 트리: rootNode=${rawTree.rootNode.layerName}.${rawTree.rootNode.function}, children=${rawTree.rootNode.children.size}"
                    println(treeMsg)
                    addLog(treeMsg)
                    
                    // 트리 검증 및 자동 수정
                    val validationStartTime = System.currentTimeMillis()
                    val validator = ExecutionTreeValidator(allDescriptions)
                    val validationResult = validator.validateAndFix(rawTree, query)
                    val validationDuration = System.currentTimeMillis() - validationStartTime
                    println("⏱️ [PERF] 트리 검증 완료: ${validationDuration}ms")
                    
                    if (validationResult.errors.isNotEmpty()) {
                        val errorMsg = "❌ [Orchestrator] 트리 검증 실패: ${validationResult.errors.joinToString(", ")}"
                        println(errorMsg)
                        addLog(errorMsg)
                        throw Exception("트리 검증 실패: ${validationResult.errors.joinToString(", ")}")
                    }
                    
                    val treeToExecute = validationResult.fixedTree ?: rawTree
                    
                    if (validationResult.warnings.isNotEmpty()) {
                        val warnMsg = "⚠️ [Orchestrator] 트리 검증 경고: ${validationResult.warnings.joinToString(", ")}"
                        println(warnMsg)
                        addLog(warnMsg)
                    }
                    
                    // 트리 실행
                    val execStartMsg = "🚀 [Orchestrator] 트리 실행 시작..."
                    println(execStartMsg)
                    addLog(execStartMsg)
                    val executionStartTime = System.currentTimeMillis()
                    val result = executeTree(treeToExecute)
                    val executionDuration = System.currentTimeMillis() - executionStartTime
                    val execPerfMsg = "⏱️ [PERF] 트리 실행 완료: ${executionDuration}ms"
                    println(execPerfMsg)
                    addLog(execPerfMsg)
                    val execDoneMsg = "✅ [Orchestrator] 트리 실행 완료"
                    println(execDoneMsg)
                    addLog(execDoneMsg)
                    
                    // 실행 결과 평가 (LLM이 판단)
                    val evalStartMsg = "🤔 [Orchestrator] 실행 결과 평가 중..."
                    println(evalStartMsg)
                    addLog(evalStartMsg)
                    val evaluationStartTime = System.currentTimeMillis()
                    val evaluation = modelSelectionStrategy.selectClientForEvaluation()
                        .evaluateResult(query, result.result, result.context)
                    val evaluationDuration = System.currentTimeMillis() - evaluationStartTime
                    val evalPerfMsg = "⏱️ [PERF] 결과 평가 완료: ${evaluationDuration}ms"
                    println(evalPerfMsg)
                    addLog(evalPerfMsg)
                    val evalResultMsg = "📊 [Orchestrator] 평가 결과: ${if (evaluation.isSatisfactory) "요구사항 부합" else "요구사항 미부합"} - ${evaluation.reason}"
                    println(evalResultMsg)
                    addLog(evalResultMsg)
                    
                    // 요구사항 부합 여부 확인
                    if (evaluation.isSatisfactory && !evaluation.needsRetry) {
                        // 성공: 요구사항 부합하고 재처리 불필요
                        val successMsg = "✅ [Orchestrator] 실행 성공: 요구사항 부합"
                        println(successMsg)
                        addLog(successMsg)
                        
                        // 실행 완료 이력 저장 (성공한 경우만)
                        val history = ExecutionHistory.createCompleted(
                            executionId, query, result, startTime,
                            logs = currentExecution?.logs ?: mutableListOf()
                        )
                        executionHistory.add(history)
                        emitExecutionUpdate(history)
                        currentExecution = null
                        return result
                    }
                    
                    // 재처리 필요 또는 요구사항 미부합
                    if (evaluation.needsRetry) {
                        val retryMsg = "🔄 [Orchestrator] 재처리 필요: ${evaluation.reason}"
                        println(retryMsg)
                        addLog(retryMsg)
                        
                        // 실패한 실행을 이력에 저장 (재처리 전)
                        val failedHistory = ExecutionHistory.createFailed(
                            executionId, query,
                            "요구사항 미부합: ${evaluation.reason}",
                            startTime,
                            logs = currentExecution?.logs ?: mutableListOf()
                        )
                        executionHistory.add(failedHistory)
                        emitExecutionUpdate(failedHistory)
                        
                        // 이전 실행과 비교하여 유의미한 차이 확인
                        if (previousHistory != null && previousTree != null) {
                            val compareMsg = "🔍 [Orchestrator] 이전 실행과 비교 중..."
                            println(compareMsg)
                            addLog(compareMsg)
                            val comparisonStartTime = System.currentTimeMillis()
                            val prevHistory = previousHistory  // 로컬 변수로 복사하여 smart cast 가능하게
                            val prevTree = previousTree
                            val comparison = modelSelectionStrategy.selectClientForComparison()
                                .useSuspend { client ->
                                    client.compareExecutions(
                                        query,
                                        prevTree,
                                        prevHistory.result.result,
                                        treeToExecute,
                                        result.result
                                    )
                                }
                            val comparisonDuration = System.currentTimeMillis() - comparisonStartTime
                            val comparePerfMsg = "⏱️ [PERF] 실행 비교 완료: ${comparisonDuration}ms"
                            println(comparePerfMsg)
                            addLog(comparePerfMsg)
                            
                            if (!comparison.isSignificantlyDifferent) {
                                val noChangeMsg = "⚠️ [Orchestrator] 유의미한 변경 없음: ${comparison.reason}"
                                val stopMsg = "🛑 [Orchestrator] 무한 루프 방지: 재처리 중단"
                                println(noChangeMsg)
                                println(stopMsg)
                                addLog(noChangeMsg)
                                addLog(stopMsg)
                                currentExecution = null
                                return result // 현재 결과 반환
                            }
                            
                            val diffMsg = "✅ [Orchestrator] 유의미한 차이 확인: ${comparison.reason}"
                            println(diffMsg)
                            addLog(diffMsg)
                        }
                        
                        // 재처리 루프 계속 (같은 executionId 사용, 로그는 계속 누적)
                        previousHistory = failedHistory
                        previousTree = treeToExecute
                        val newRunningHistory = ExecutionHistory.createRunning(executionId, query, System.currentTimeMillis())
                        newRunningHistory.logs.addAll(currentExecution?.logs ?: emptyList())
                        currentExecution = newRunningHistory
                        executionHistory.add(newRunningHistory)
                        emitExecutionUpdate(currentExecution!!)
                        continue
                    }
                    
                    // 평가 실패 또는 기타 경우: 현재 결과 반환
                    currentExecution = null
                    return result
                    
                } catch (e: Exception) {
                    val errorMsg = "❌ [Orchestrator] 실행 실패: ${e.message}"
                    val errorTypeMsg = "   예외 타입: ${e::class.simpleName}"
                    println(errorMsg)
                    println(errorTypeMsg)
                    addLog(errorMsg)
                    addLog(errorTypeMsg)
                    e.printStackTrace()
                    
                    // 실패 이력 저장
                    val failedHistory = ExecutionHistory.createFailed(
                        executionId, query, 
                        e.message ?: "알 수 없는 오류", 
                        startTime,
                        logs = currentExecution?.logs ?: mutableListOf()
                    )
                    executionHistory.add(failedHistory)
                    emitExecutionUpdate(failedHistory)
                    
                    // 재처리 가능 여부 확인
                    if (attemptCount >= maxAttempts) {
                        val maxAttemptsMsg = "🛑 [Orchestrator] 최대 재시도 횟수 도달: 중단"
                        println(maxAttemptsMsg)
                        addLog(maxAttemptsMsg)
                        currentExecution = null
                        return ExecutionResult(result = "", error = "최대 재시도 횟수 도달: ${e.message}")
                    }
                    
                    // 재처리 방안 요청 (예외 발생 시에도 currentExecution을 null로 설정)
                    try {
                        if (previousHistory == null) {
                            previousHistory = failedHistory
                        }
                        
                        val retryAnalysisMsg = "🔧 [Orchestrator] 실패 분석 및 재처리 방안 요청 중..."
                        println(retryAnalysisMsg)
                        addLog(retryAnalysisMsg)
                        val prevHistory = previousHistory  // 로컬 변수로 복사하여 smart cast 가능하게
                        val retryStrategy = modelSelectionStrategy.selectClientForRetryStrategy()
                            .useSuspend { client ->
                                client.suggestRetryStrategy(query, prevHistory, allDescriptions)
                            }
                        
                        if (retryStrategy.shouldStop) {
                            val stopMsg = "🛑 [Orchestrator] LLM 판단: 근본 해결 불가능 - ${retryStrategy.reason}"
                            println(stopMsg)
                            addLog(stopMsg)
                            currentExecution = null
                            return ExecutionResult(result = "", error = "재처리 중단: ${retryStrategy.reason}")
                        }
                        
                        val retrySuccessMsg = "✅ [Orchestrator] 재처리 방안 수신: ${retryStrategy.reason}"
                        println(retrySuccessMsg)
                        addLog(retrySuccessMsg)
                        previousHistory = failedHistory
                        previousTree = failedHistory.result.executionTree
                        val newRunningHistory = ExecutionHistory.createRunning(executionId, query, System.currentTimeMillis())
                        newRunningHistory.logs.addAll(currentExecution?.logs ?: emptyList())
                        currentExecution = newRunningHistory
                        emitExecutionUpdate(currentExecution!!)
                        continue
                    } catch (retryException: Exception) {
                        val retryErrorMsg = "❌ [Orchestrator] 재처리 방안 요청 실패: ${retryException.message}"
                        println(retryErrorMsg)
                        addLog(retryErrorMsg)
                        currentExecution = null
                        return ExecutionResult(result = "", error = "재처리 방안 요청 실패: ${retryException.message}")
                    }
                }
            }
            
            // 최대 재시도 횟수 도달
            val maxAttemptsMsg = "🛑 [Orchestrator] 최대 재시도 횟수 도달"
            println(maxAttemptsMsg)
            addLog(maxAttemptsMsg)
            val finalFailedHistory = ExecutionHistory.createFailed(
                executionId, query, 
                "최대 재시도 횟수 도달", 
                startTime,
                logs = currentExecution?.logs ?: mutableListOf()
            )
            executionHistory.add(finalFailedHistory)
            emitExecutionUpdate(finalFailedHistory)
            currentExecution = null
            return ExecutionResult(result = "", error = "최대 재시도 횟수 도달")
            
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
        val treeStartTime = System.currentTimeMillis()
        val treeStartMsg = "🌳 [executeTree] 실행 트리 시작: ${tree.name}"
        println(treeStartMsg)
        addLog(treeStartMsg)
        
        val result = executeNode(tree.rootNode, context, parentNodeId = null, depth = 0)
        
        val treeDuration = System.currentTimeMillis() - treeStartTime
        val treePerfMsg = "⏱️ [PERF] executeTree 총 소요 시간: ${treeDuration}ms"
        println(treePerfMsg)
        addLog(treePerfMsg)
        
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
        
        // 최종 결과: 루트 노드의 최종 결과를 사용 (이미 모든 자식 결과가 포함되어 있음)
        // executeNode에서 부모+자식 결과를 합쳐서 저장하므로, 루트 노드의 결과만 사용하면 됨
        val resultText = if (result.isSuccess && result.result != null && result.result.isNotEmpty()) {
            result.result
        } else if (context.completedNodes.isNotEmpty()) {
            // 루트 노드 결과가 없으면 fallback으로 모든 성공 노드 결과 결합
            val allResults = context.completedNodes
                .sortedBy { it.depth }
                .mapNotNull { it.result }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            allResults.ifEmpty { extractResultText(result) }
        } else {
            extractResultText(result)
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
            
            val execStartMsg = "${indent}▶️ [executeNode] ${node.layerName}.${node.function} 실행 중...${if (isRemote) " (원격: $remoteUrl)" else ""}"
            println(execStartMsg)
            addLog(execStartMsg)
            val nodeStartTime = System.currentTimeMillis()
            val execResult = layer.execute(node.function, node.args)
            val nodeDuration = System.currentTimeMillis() - nodeStartTime
            val execCompleteMsg = "${indent}✅ [executeNode] ${node.layerName}.${node.function} 완료: ${execResult.take(50)}... (${nodeDuration}ms)"
            println(execCompleteMsg)
            addLog(execCompleteMsg)
            
            context.recordNode(node, NodeStatus.SUCCESS, depth, parentNodeId, result = execResult)
        } catch (e: Exception) {
            val execErrorMsg = "${indent}❌ [executeNode] ${node.layerName}.${node.function} 에러: ${e.message}"
            println(execErrorMsg)
            addLog(execErrorMsg)
            
            context.recordNode(
                node, NodeStatus.FAILED, depth, parentNodeId,
                error = "Error executing ${node.layerName}.${node.function}: ${e.message}"
            )
        }
        
        // 실패 시 여기서 재시도 로직 추가 가능 (나중에)
        if (executionResult.isFailure) {
            val failMsg = "${indent}⚠️ [executeNode] 노드 실패: ${node.layerName}.${node.function} (id=$nodeId, depth=$depth)"
            val retryPointMsg = "${indent}   재시도 시작점: ${context.findRetryStartPoint(nodeId)}"
            println(failMsg)
            println(retryPointMsg)
            addLog(failMsg)
            addLog(retryPointMsg)
            // 재시도 로직은 다음 단계에서 추가
        } else if (executionResult.isSuccess) {
            val successMsg = "${indent}✅ [executeNode] 노드 성공: ${node.layerName}.${node.function} (id=$nodeId)"
            val previewMsg = "${indent}   결과 미리보기: ${executionResult.resultText.take(100)}"
            println(successMsg)
            println(previewMsg)
            addLog(successMsg)
            addLog(previewMsg)
        }
        
        // 자식 노드 실행
        if (node.children.isEmpty()) {
            return executionResult
        }
        
        val childrenMsg = "${indent}📦 [executeNode] 자식 노드 ${node.children.size}개 실행 (parallel=${node.parallel})"
        println(childrenMsg)
        addLog(childrenMsg)
        
        // 부모 결과를 자식 노드에 전달
        val parentResult = if (executionResult.isSuccess && executionResult.result != null) {
            executionResult.result
        } else null
        
        val childResults = if (node.parallel) {
            // 병렬 실행
            coroutineScope {
                node.children.map { child ->
                    async {
                        executeNodeWithParentResult(child, context, nodeId, depth + 1, parentResult)
                    }
                }.awaitAll()
            }
        } else {
            // 순차 실행 (이전 자식 결과를 다음 자식에게 전달)
            var previousResult = parentResult
            node.children.map { child ->
                val result = executeNodeWithParentResult(child, context, nodeId, depth + 1, previousResult)
                previousResult = result.result // 다음 자식에게 전달
                result
            }
        }
        
        // 자식 노드 실패 체크
        val failedChildren = childResults.filter { it.isFailure }
        if (failedChildren.isNotEmpty() && executionResult.isSuccess) {
            // 부모는 성공했지만 자식이 실패한 경우
            println("${indent}⚠️ [executeNode] 자식 노드 실패: ${failedChildren.size}개")
        }
        
        // 결과 결합: 부모 결과와 자식 결과를 모두 포함하여 LLM 평가에 충분한 정보 제공
        // 단, 검증 레이어(validator)의 결과는 최종 결과에서 제외 (검증은 통과/실패만 알려주면 됨)
        val successfulResults = childResults.filter { it.isSuccess }
        val finalResultText = if (successfulResults.isNotEmpty()) {
            // 자식이 있으면 부모 결과와 자식 결과를 모두 포함
            val parentResultText = executionResult.result?.takeIf { it.isNotEmpty() }
            val childResultsText = successfulResults
                .mapNotNull { it.result }
                .filter { it.isNotEmpty() }
                .filter { result -> 
                    // 검증 레이어의 결과는 제외 (validate로 시작하는 함수의 결과)
                    val node = childResults.find { it.result == result }?.node
                    node?.function?.startsWith("validate") != true
                }
            
            val allResults = listOfNotNull(parentResultText) + childResultsText
            allResults.joinToString("\n")
        } else {
            // 자식이 없으면 부모 결과만 반환
            executionResult.result ?: ""
        }
        
        println("${indent}🏁 [executeNode] ${node.layerName} 최종 결과: ${finalResultText.take(100)}...")
        
        // 최종 결과 업데이트 (부모 + 자식 결과 모두 포함)
        val finalResult = executionResult.copy(result = finalResultText)
        context.recordResult(finalResult)
        return finalResult
    }
    
    /**
     * 부모 결과를 받아서 자식 노드 실행
     * 부모 결과를 LLM이 자식 함수의 파라미터로 변환하여 전달
     */
    private suspend fun executeNodeWithParentResult(
        node: ExecutionNode,
        context: ExecutionContext,
        parentNodeId: String?,
        depth: Int,
        parentResult: String?
    ): NodeExecutionResult {
        // 부모 결과가 있고, 자식 노드가 부모 결과를 사용할 수 있는 경우
        val enrichedArgs = if (parentResult != null && parentNodeId != null) {
            try {
                val childLayer = layers.find { it.describe().name == node.layerName }
                val childLayerDesc = childLayer?.describe()
                val childFunctionDesc = childLayerDesc?.functionDetails?.get(node.function)
                
                if (childFunctionDesc != null && childLayerDesc != null) {
                    // LLM이 부모 결과를 자식 함수 파라미터로 변환
                    val extractStartTime = System.currentTimeMillis()
                    val extractedParams = modelSelectionStrategy.selectClientForParameterExtraction()
                        .useSuspend { client ->
                            client.extractParameters(
                                parentResult = parentResult,
                                childLayerName = node.layerName,
                                childFunctionName = node.function,
                                childFunctionDetails = childFunctionDesc,
                                layerDescriptions = getAllLayerDescriptions()
                            )
                        }
                    val extractDuration = System.currentTimeMillis() - extractStartTime
                    val extractPerfMsg = "  ⏱️ [PERF] 파라미터 추출 완료: ${extractDuration}ms (${node.layerName}.${node.function})"
                    println(extractPerfMsg)
                    addLog(extractPerfMsg)
                    
                    // 기존 args와 추출된 파라미터 병합 (추출된 파라미터 우선)
                    node.args + extractedParams
                } else {
                    // functionDetails가 없으면 부모 결과를 "input" 또는 첫 번째 파라미터로 전달
                    val firstParamName = node.args.keys.firstOrNull() ?: "input"
                    node.args + (firstParamName to parentResult)
                }
            } catch (e: Exception) {
                println("⚠️ [executeNodeWithParentResult] 파라미터 추출 실패: ${e.message}, 기존 args 사용")
                // 실패 시 기존 args 사용
                node.args
            }
        } else {
            // 부모 결과가 없으면 기존 args 사용
            node.args
        }
        
        // args가 변경된 노드로 실행
        val enrichedNode = node.copy(args = enrichedArgs)
        return executeNode(enrichedNode, context, parentNodeId, depth)
    }
    
    /**
     * 리소스 정리 (메모리 누수 방지)
     * 
     * 주의: Factory 패턴으로 변경되면서 더 이상 고정된 클라이언트 인스턴스가 없음
     * 각 클라이언트는 사용 후 즉시 정리되거나, 향후 풀링 전략에서 관리됨
     * 현재는 각 클라이언트가 독립적으로 생성/소멸되므로 여기서는 특별한 정리 작업 불필요
     */
    suspend fun close() {
        // Factory 패턴으로 변경되어 고정된 클라이언트 인스턴스가 없음
        // 향후 클라이언트 풀링을 구현하면 여기서 풀 정리 로직 추가
    }
    
}