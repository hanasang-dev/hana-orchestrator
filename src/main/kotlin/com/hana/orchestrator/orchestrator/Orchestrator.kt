package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.layer.CommonLayerInterface
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

    /** 파일 쓰기 승인 게이트 (WebSocket 컨트롤러에서 구독, ApprovalController에서 응답) */
    val approvalGate = ApprovalGate()

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
            treeExecutor = treeExecutor
        )

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
    suspend fun executeOrchestration(chatDto: ChatDto): ExecutionResult {
        val query = chatDto.message
        appContextService.updateVolatileFromRequest(chatDto.context)
        appContextService.ensureVolatileServerWorkingDirectory()

        val executionId = java.util.UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        val runningHistory = ExecutionHistory.createRunning(executionId, query, startTime)
        historyManager.setCurrentExecution(runningHistory)
        historyManager.addLogToCurrent("🚀 [Reactive] 실행 시작: $query")
        statePublisher.emitExecutionUpdate(runningHistory)
        statePublisher.emitProgressAsync(executionId, ExecutionPhase.STARTING, "🚀 ReAct 시작", 0, 0)

        val volatileCtx = appContextService.getVolatileStore().snapshot()
        val workingDir = volatileCtx["workingDirectory"] ?: System.getProperty("user.dir") ?: "."
        val kotlinFileIndex = buildKotlinFileIndex(workingDir)
        val projectContext = buildMap {
            put("workingDirectory", workingDir)
            if (kotlinFileIndex.isNotEmpty()) put("kotlinFileIndex", kotlinFileIndex)
        }

        return try {
            val result = reactiveExecutor.execute(query, executionId, startTime, projectContext)
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
                    executionId, query, result.error, startTime, logs = historyManager.getCurrentLogs()
                )
            } else {
                ExecutionHistory.createCompleted(
                    executionId, query, result, startTime,
                    logs = historyManager.getCurrentLogs(),
                    executionTree = reactTree
                )
            }
            historyManager.addHistory(history)
            statePublisher.emitExecutionUpdate(history)
            historyManager.clearCurrentExecution()
            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            statePublisher.emitProgress(executionId, ExecutionPhase.FAILED, "❌ 실패", 100, elapsed)
            val failedHistory = ExecutionHistory.createFailed(
                executionId, query, e.message ?: "실행 실패", startTime, logs = historyManager.getCurrentLogs()
            )
            historyManager.addHistory(failedHistory)
            statePublisher.emitExecutionUpdate(failedHistory)
            historyManager.clearCurrentExecution()
            ExecutionResult(result = "", error = e.message)
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
        historyManager.addLogToCurrent("🚀 커스텀 트리 실행 시작: $query")
        statePublisher.emitExecutionUpdate(runningHistory)
        statePublisher.emitProgressAsync(executionId, ExecutionPhase.TREE_EXECUTION, "⚡ 사용자 트리 실행 중...", 60, 0)

        return try {
            val result = validateAndExecuteTree(tree, query, allDescriptions, executionId, startTime)
            val history = ExecutionHistory.createCompleted(
                executionId, query, result, startTime,
                logs = historyManager.getCurrentLogs(),
                executionTree = result.executionTree?.let { with(ExecutionTreeMapper) { it.toResponse() } }
            )
            historyManager.addHistory(history)
            statePublisher.emitExecutionUpdate(history)
            statePublisher.emitProgress(executionId, ExecutionPhase.COMPLETED, "✅ 완료", 100, System.currentTimeMillis() - startTime)
            historyManager.clearCurrentExecution()
            result
        } catch (e: Exception) {
            saveAndEmitFailedHistory(executionId, query, e.message ?: "실행 실패", startTime)
            historyManager.clearCurrentExecution()
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
        val targetLayer = layerManager.findLayerByName(function)
        return if (targetLayer != null) {
            executeOnLayer(function, "process", args)
        } else {
            val allFunctions = allDescriptions.flatMap { it.functions }
            "Unknown function: $function. Available: ${allFunctions.joinToString(", ")}"
        }
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

        val executionContext = historyManager.getCurrentExecution()!!
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
            logs = historyManager.getCurrentLogs()
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
private fun buildKotlinFileIndex(workingDir: String): String {
    val root = java.io.File(workingDir, "src/main/kotlin")
    if (!root.exists()) return ""
    return root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { file ->
            val rel = file.relativeTo(java.io.File(workingDir)).path
            "${file.nameWithoutExtension}:$rel"
        }
        .sorted()
        .take(60)
        .joinToString(", ")
}
