package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.orchestrator.core.LayerManager
import com.hana.orchestrator.orchestrator.core.ReactiveExecutor
import com.hana.orchestrator.orchestrator.core.StrategyContext
import com.hana.orchestrator.orchestrator.core.TreeExecutor
import com.hana.orchestrator.layer.CommonLayerInterface
import com.hana.orchestrator.layer.ContextLayer
import com.hana.orchestrator.layer.LayerDescription
import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.llm.strategy.GeneratedModelSelectionStrategy
import com.hana.orchestrator.llm.factory.LLMClientFactory
import com.hana.orchestrator.llm.factory.DefaultLLMClientFactory
import com.hana.orchestrator.llm.useSuspend
import com.hana.orchestrator.context.AppContextService
import com.hana.orchestrator.domain.dto.ChatDto
import com.hana.orchestrator.presentation.model.metrics.OrchestratorMetrics
import com.hana.orchestrator.presentation.model.execution.ExecutionPhase
import com.hana.orchestrator.presentation.mapper.ExecutionTreeMapper
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive

/**
 * 오케스트레이터 Facade
 * SRP: 각 책임을 담당하는 컴포넌트들을 조합하여 제공
 * Facade 패턴: 복잡한 서브시스템을 단순한 인터페이스로 제공
 */
class Orchestrator(
    private val llmConfig: LLMConfig? = null,
    private val appContextService: AppContextService
) : CommonLayerInterface {

    // 컴포넌트들 (의존성 주입)
    private val layerManager: LayerManager
    private val historyManager: ExecutionHistoryManager
    private val statePublisher: ExecutionStatePublisher
    private val treeExecutor: TreeExecutor
    private val reactiveExecutor: ReactiveExecutor

    /** ReAct 루프를 독립 코루틴으로 실행하는 스코프 (취소 지원) */
    private val orchestratorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 실행 중인 ReAct Job 맵 (executionId → Deferred, 취소용) */
    private val runningJobs = java.util.concurrent.ConcurrentHashMap<String, Deferred<com.hana.orchestrator.domain.entity.ExecutionResult>>()

    /** 파일 쓰기 승인 게이트 (WebSocket 컨트롤러에서 구독, ApprovalController에서 응답) */
    val approvalGate = ApprovalGate()

    /** 사용자 질문 게이트 (WebSocket 컨트롤러에서 구독, ClarificationController에서 응답) */
    val clarificationGate = ClarificationGate()

    // LLM 관련
    val config: LLMConfig
    private val clientFactory: LLMClientFactory
    private val modelSelectionStrategy: ModelSelectionStrategy

    // Logger
    private val logger = createOrchestratorLogger(Orchestrator::class.java, null)

    init {
        config = llmConfig ?: LLMConfig.fromEnvironment()
        clientFactory = DefaultLLMClientFactory(config)
        modelSelectionStrategy = GeneratedModelSelectionStrategy(clientFactory = clientFactory)

        layerManager = LayerManager(modelSelectionStrategy, approvalGate)
        historyManager = ExecutionHistoryManager()
        statePublisher = ExecutionStatePublisher()
        treeExecutor = TreeExecutor(layerManager, statePublisher, historyManager)
        reactiveExecutor = ReactiveExecutor(
            layerManager = layerManager,
            historyManager = historyManager,
            statePublisher = statePublisher,
            modelSelectionStrategy = modelSelectionStrategy,
            treeExecutor = treeExecutor,
            clarificationGate = clarificationGate
        )

        // DevelopLayer에서 전략 핫로드 가능하도록 의존성 주입
        layerManager.wireReactiveExecutor(
            reactiveExecutor,
            StrategyContext(layerManager, historyManager, statePublisher, modelSelectionStrategy, treeExecutor, clarificationGate)
        )
        layerManager.wireLlmClientFactory(clientFactory)

        logger.info("🚀 [Orchestrator] 초기화 시작...")
        logger.info("ℹ️ [Orchestrator] 레이어 초기화는 첫 실행 시 수행됩니다")
    }

    // Public API (Facade)

    fun getExecutionHistory(limit: Int = 50): List<ExecutionHistory> {
        return historyManager.getExecutionHistory(limit)
    }

    fun computeMetrics(): OrchestratorMetrics {
        return MetricsService().compute(historyManager.getExecutionHistory(limit = 200))
    }

    fun deleteExecution(id: String): Boolean {
        return historyManager.deleteHistory(id)
    }

    /**
     * 현재 실행 중인 ReAct 루프를 취소한다.
     * @return 취소 요청이 전달되면 true, 실행 중이 아니면 false
     */
    fun cancelCurrentExecution(executionId: String? = null): Boolean {
        if (executionId != null) {
            val job = runningJobs[executionId] ?: return false
            return if (job.isActive) { job.cancel(); true } else false
        }
        // executionId 없으면 전체 취소
        val active = runningJobs.values.filter { it.isActive }
        active.forEach { it.cancel() }
        return active.isNotEmpty()
    }

    fun getCurrentExecution(): ExecutionHistory? {
        return historyManager.getCurrentExecution()
    }

    val executionUpdates: SharedFlow<ExecutionHistory>
        get() = statePublisher.executionUpdates

    val progressUpdates: SharedFlow<com.hana.orchestrator.presentation.model.execution.ProgressUpdate>
        get() = statePublisher.progressUpdates

    suspend fun registerLayer(layer: CommonLayerInterface) {
        layerManager.registerLayer(layer)
    }

    suspend fun getAllLayerDescriptions(): List<LayerDescription> {
        return layerManager.getAllLayerDescriptions()
    }

    suspend fun executeOnLayer(layerName: String, function: String, args: Map<String, Any> = emptyMap()): String {
        return layerManager.executeOnLayer(layerName, function, args)
    }

    /**
     * 오케스트레이션 실행 — ReAct 루프
     */
    suspend fun executeOrchestration(
        chatDto: ChatDto,
        isScheduled: Boolean = false,
        onStart: ((String) -> Unit)? = null
    ): ExecutionResult {
        val query = chatDto.message
        appContextService.updateVolatileFromRequest(chatDto.context)
        appContextService.ensureVolatileServerWorkingDirectory()

        val executionId = java.util.UUID.randomUUID().toString()
        onStart?.invoke(executionId)
        val startTime = System.currentTimeMillis()

        val runningHistory = ExecutionHistory.createRunning(executionId, query, startTime)
        historyManager.setCurrentExecution(runningHistory)
        logger.info("🚀 [Orchestrator] 실행 시작 executionId=$executionId query=$query")
        historyManager.addLogTo(executionId, "🚀 [Reactive] 실행 시작: $query")
        statePublisher.emitExecutionUpdate(runningHistory)
        statePublisher.emitProgressAsync(executionId, ExecutionPhase.STARTING, "🚀 ReAct 시작", 0, 0, query)

        val volatileCtx = appContextService.getVolatileStore().snapshot()
        val workingDir = volatileCtx["workingDirectory"] ?: System.getProperty("user.dir") ?: "."
        val projectContext = buildMap {
            put("workingDirectory", workingDir)
        }

        val deferred = orchestratorScope.async {
            reactiveExecutor.execute(query, executionId, startTime, projectContext, isScheduled)
        }
        runningJobs[executionId] = deferred

        return try {
            val result = deferred.await()
            val elapsed = System.currentTimeMillis() - startTime
            // 안전망: ReactiveExecutor가 어떤 경로로 종료되든 항상 최종 상태를 emit
            if (result.error != null && result.result.isEmpty()) {
                statePublisher.emitProgress(executionId, ExecutionPhase.FAILED, "❌ 실패", 100, elapsed)
            } else {
                statePublisher.emitProgress(executionId, ExecutionPhase.COMPLETED, "✅ 완료", 100, elapsed)
            }
            val reactTree = ReActTreeConverter.convert(result.stepHistory)
            val history = if (result.error != null && result.result.isEmpty()) {
                ExecutionHistory.createFailed(
                    executionId, query, result.error, startTime, logs = historyManager.getLogs(executionId)
                )
            } else {
                ExecutionHistory.createCompleted(
                    executionId, query, result, startTime,
                    logs = historyManager.getLogs(executionId),
                    executionTree = reactTree
                )
            }
            historyManager.addHistory(history)
            statePublisher.emitExecutionUpdate(history)
            ContextLayer.clearExecution(executionId)
            historyManager.clearCurrentExecution(executionId)
            // 세션에 실행 추가 (best-effort)
            try {
                layerManager.executeOnLayerInternal("session", "addExecution",
                    mapOf("executionId" to executionId, "query" to query))
            } catch (e: Exception) {
                logger.warn("⚠️ [Orchestrator] 세션 추가 실패 (무시): ${e.message}")
            }
            result
        } catch (e: CancellationException) {
            // deferred가 취소됐지만 현재 코루틴(Ktor 핸들러)은 살아있는 경우 → 사용자 취소
            deferred.cancel()
            val elapsed = System.currentTimeMillis() - startTime
            statePublisher.emitProgress(executionId, ExecutionPhase.CANCELLED, "🚫 취소됨", 100, elapsed)
            val cancelledHistory = ExecutionHistory.createCancelled(
                executionId, query, startTime, logs = historyManager.getLogs(executionId)
            )
            historyManager.addHistory(cancelledHistory)
            statePublisher.emitExecutionUpdate(cancelledHistory)
            ContextLayer.clearExecution(executionId)
            historyManager.clearCurrentExecution(executionId)
            ExecutionResult(result = "", error = "사용자가 실행을 취소했습니다")
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            statePublisher.emitProgress(executionId, ExecutionPhase.FAILED, "❌ 실패", 100, elapsed)
            val failedHistory = ExecutionHistory.createFailed(
                executionId, query, e.message ?: "실행 실패", startTime, logs = historyManager.getLogs(executionId)
            )
            historyManager.addHistory(failedHistory)
            statePublisher.emitExecutionUpdate(failedHistory)
            ContextLayer.clearExecution(executionId)
            historyManager.clearCurrentExecution(executionId)
            ExecutionResult(result = "", error = e.message)
        } finally {
            runningJobs.remove(executionId)
        }
    }

    /** query만 있을 때 호환용 */
    suspend fun executeOrchestration(query: String): ExecutionResult =
        executeOrchestration(ChatDto(message = query))

    /**
     * 사용자가 수정한 트리를 LLM이 검토
     */
    suspend fun reviewTree(query: String, tree: ExecutionTree): com.hana.orchestrator.llm.TreeReview {
        val allDescriptions = layerManager.getAllLayerDescriptions()
        return modelSelectionStrategy.selectClientForReviewTree()
            .useSuspend { client -> client.reviewTree(query, tree, allDescriptions) }
    }

    /**
     * 사용자가 수정한 트리를 직접 실행 (트리 생성 단계 건너뜀)
     */
    suspend fun executeCustomTree(query: String, tree: ExecutionTree): ExecutionResult {
        val allDescriptions = layerManager.getAllLayerDescriptions()
        val executionId = java.util.UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        val runningHistory = ExecutionHistory.createRunning(executionId, query, startTime)
        historyManager.setCurrentExecution(runningHistory)
        historyManager.addLogTo(executionId, "🚀 커스텀 트리 실행 시작: $query")
        statePublisher.emitExecutionUpdate(runningHistory)
        statePublisher.emitProgressAsync(executionId, ExecutionPhase.TREE_EXECUTION, "⚡ 사용자 트리 실행 중...", 60, 0, query)

        return try {
            val result = validateAndExecuteTree(tree, query, allDescriptions, executionId, startTime)
            val history = ExecutionHistory.createCompleted(
                executionId, query, result, startTime,
                logs = historyManager.getLogs(executionId),
                executionTree = result.executionTree?.let { with(ExecutionTreeMapper) { it.toResponse() } }
            )
            historyManager.addHistory(history)
            statePublisher.emitExecutionUpdate(history)
            statePublisher.emitProgress(executionId, ExecutionPhase.COMPLETED, "✅ 완료", 100, System.currentTimeMillis() - startTime)
            ContextLayer.clearExecution(executionId)
            historyManager.clearCurrentExecution(executionId)
            result
        } catch (e: Exception) {
            saveAndEmitFailedHistory(executionId, query, e.message ?: "실행 실패", startTime)
            ContextLayer.clearExecution(executionId)
            historyManager.clearCurrentExecution(executionId)
            ExecutionResult(result = "", error = e.message)
        }
    }

    // CommonLayerInterface 구현

    override suspend fun describe(): LayerDescription {
        val allDescriptions = layerManager.getAllLayerDescriptions()
        val mergedFunctionDetails = allDescriptions
            .flatMap { it.functionDetails.entries }
            .associate { it.key to it.value }

        return LayerDescription(
            name = "orchestrator",
            description = "등록된 레이어들을 관리하고 실행: ${allDescriptions.map { it.name }}",
            functions = allDescriptions.flatMap { it.functions },
            functionDetails = mergedFunctionDetails
        )
    }

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        val query = args["query"] as? String
        if (query != null) {
            val result = executeOrchestration(ChatDto(message = query))
            return result.result
        }

        val allDescriptions = layerManager.getAllLayerDescriptions()
        val allFunctions = allDescriptions.flatMap { it.functions }
        return "Unknown function: $function. Available: ${allFunctions.joinToString(", ")}"
    }

    suspend fun close() {
        // 현재 구현에서는 정리할 리소스가 없음
    }

    // Private helpers

    private suspend fun validateAndExecuteTree(
        rawTree: ExecutionTree,
        query: String,
        allDescriptions: List<LayerDescription>,
        executionId: String,
        startTime: Long
    ): ExecutionResult {
        val rootNodesInfo = rawTree.rootNodes.joinToString(", ") { "${it.layerName}.${it.function}" }
        logger.info("🌳 [Orchestrator] 실행 트리: 루트 노드 ${rawTree.rootNodes.size}개 [$rootNodesInfo]")
        statePublisher.emitProgressAsync(executionId, ExecutionPhase.TREE_VALIDATION, "🔍 실행 계획 검증 중...", 50, System.currentTimeMillis() - startTime)

        val validator = ExecutionTreeValidator(allDescriptions)
        val validationResult = validator.validateAndFix(rawTree, query)

        if (validationResult.errors.isNotEmpty()) {
            val errorMsg = "❌ [Orchestrator] 트리 검증 실패: ${validationResult.errors.joinToString(", ")}"
            logger.error(errorMsg)
            throw Exception("트리 검증 실패: ${validationResult.errors.joinToString(", ")}")
        }

        val treeToExecute = validationResult.fixedTree ?: rawTree

        if (validationResult.warnings.isNotEmpty()) {
            val warningsText = if (validationResult.warnings.size > 3) {
                validationResult.warnings.take(3).joinToString(", ") + " 외 ${validationResult.warnings.size - 3}개"
            } else {
                validationResult.warnings.joinToString(", ")
            }
            logger.warn("⚠️ [Orchestrator] 트리 검증 경고: $warningsText")
        }

        logger.info("🚀 [Orchestrator] 트리 실행 시작...")
        statePublisher.emitProgressAsync(executionId, ExecutionPhase.TREE_EXECUTION, "⚡ 작업 실행 중...", 60, System.currentTimeMillis() - startTime)

        val executionContext = historyManager.getCurrentExecution(executionId)!!
        val result = treeExecutor.executeTree(treeToExecute, executionContext)

        logger.info("✅ [Orchestrator] 트리 실행 완료")
        return result
    }

    private suspend fun saveAndEmitFailedHistory(
        executionId: String,
        query: String,
        error: String,
        startTime: Long
    ): ExecutionHistory {
        val failedHistory = ExecutionHistory.createFailed(
            executionId, query, error, startTime,
            logs = historyManager.getLogs(executionId)
        )
        historyManager.addHistory(failedHistory)
        statePublisher.emitExecutionUpdate(failedHistory)
        return failedHistory
    }
}

/**
 * src/main/kotlin 아래 .kt 파일을 스캔하여 "ClassName -> relative/path/to/ClassName.kt" 인덱스를 반환.
 * LLM이 경로를 추측하지 않아도 되도록 projectContext에 주입.
 */
