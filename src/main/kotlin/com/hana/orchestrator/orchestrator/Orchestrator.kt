package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.layer.CommonLayerInterface
import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.llm.strategy.GeneratedModelSelectionStrategy
import com.hana.orchestrator.llm.factory.LLMClientFactory
import com.hana.orchestrator.llm.factory.DefaultLLMClientFactory
import com.hana.orchestrator.llm.useSuspend
import com.hana.orchestrator.context.AppContextService
import com.hana.orchestrator.domain.dto.ChatDto
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable

/**
 * 트리 생성 벤치마크 결과 (동일 프롬프트·모델 직접 호출 검증용)
 */
@Serializable
data class TreeCreationBenchmarkResult(
    val elapsedMs: Long,
    val success: Boolean,
    val error: String?
)

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
    private val coordinator: OrchestrationCoordinator

    /** 파일 쓰기 승인 게이트 (WebSocket 컨트롤러에서 구독, ApprovalController에서 응답) */
    val approvalGate = ApprovalGate()

    // LLM 관련
    val config: LLMConfig // 외부에서 접근 가능하도록 public
    private val clientFactory: LLMClientFactory
    private val modelSelectionStrategy: ModelSelectionStrategy

    // Logger
    private val logger = createOrchestratorLogger(Orchestrator::class.java, null)

    init {
        // LLM 설정 초기화
        config = llmConfig ?: LLMConfig.fromEnvironment()
        clientFactory = DefaultLLMClientFactory(config)
        modelSelectionStrategy = GeneratedModelSelectionStrategy(clientFactory = clientFactory)

        // 컴포넌트 초기화
        layerManager = LayerManager(modelSelectionStrategy, approvalGate)
        historyManager = ExecutionHistoryManager()
        statePublisher = ExecutionStatePublisher()
        treeExecutor = TreeExecutor(layerManager, statePublisher, historyManager)
        coordinator = OrchestrationCoordinator(
            layerManager = layerManager,
            treeExecutor = treeExecutor,
            historyManager = historyManager,
            statePublisher = statePublisher,
            modelSelectionStrategy = modelSelectionStrategy,
            appContextService = appContextService
        )
        
        logger.info("🚀 [Orchestrator] 초기화 시작...")
        logger.info("ℹ️ [Orchestrator] 레이어 초기화는 첫 실행 시 수행됩니다")
    }
    
    // Public API (Facade)
    
    /**
     * 실행 이력 조회
     */
    fun getExecutionHistory(limit: Int = 50): List<ExecutionHistory> {
        return historyManager.getExecutionHistory(limit)
    }
    
    /**
     * 현재 실행 조회
     */
    fun getCurrentExecution(): ExecutionHistory? {
        return historyManager.getCurrentExecution()
    }
    
    /**
     * 실행 상태 업데이트 Flow
     */
    val executionUpdates: SharedFlow<ExecutionHistory>
        get() = statePublisher.executionUpdates

    /**
     * 진행 상태 업데이트 Flow
     */
    val progressUpdates: SharedFlow<com.hana.orchestrator.presentation.model.execution.ProgressUpdate>
        get() = statePublisher.progressUpdates

    /**
     * 레이어 등록
     */
    suspend fun registerLayer(layer: CommonLayerInterface) {
        layerManager.registerLayer(layer)
    }
    
    /**
     * 모든 레이어 설명 조회
     */
    suspend fun getAllLayerDescriptions(): List<com.hana.orchestrator.layer.LayerDescription> {
        return layerManager.getAllLayerDescriptions()
    }
    
    /**
     * 레이어에서 함수 실행
     */
    suspend fun executeOnLayer(layerName: String, function: String, args: Map<String, Any> = emptyMap()): String {
        return layerManager.executeOnLayer(layerName, function, args)
    }
    
    /**
     * 오케스트레이션 실행 (도메인 모델 반환)
     */
    suspend fun executeOrchestration(chatDto: ChatDto): ExecutionResult {
        return coordinator.executeOrchestration(chatDto)
    }

    /**
     * 사용자가 수정한 트리를 LLM이 검토
     */
    suspend fun reviewTree(query: String, tree: com.hana.orchestrator.domain.entity.ExecutionTree): com.hana.orchestrator.llm.TreeReview {
        val allDescriptions = layerManager.getAllLayerDescriptions()
        return modelSelectionStrategy.selectClientForReviewTree()
            .useSuspend { client -> client.reviewTree(query, tree, allDescriptions) }
    }

    /**
     * 사용자가 수정한 트리를 직접 실행 (트리 생성 단계 건너뜀)
     */
    suspend fun executeCustomTree(query: String, tree: com.hana.orchestrator.domain.entity.ExecutionTree): ExecutionResult {
        return coordinator.executeCustomTree(query, tree)
    }

    /** query만 있을 때 호환용 */
    suspend fun executeOrchestration(query: String): ExecutionResult =
        executeOrchestration(ChatDto(message = query))

    /**
     * 트리 생성만 수행하여 소요 시간 측정 (동일 프롬프트·모델로 직접 호출 검증용)
     * @return elapsedMs(소요 밀리초), success(성공 여부), error(실패 시 메시지)
     */
    suspend fun benchmarkTreeCreation(query: String): TreeCreationBenchmarkResult {
        val allDescriptions = layerManager.getAllLayerDescriptions()
        val start = System.currentTimeMillis()
        return try {
            modelSelectionStrategy.selectClientForTreeCreation()
                .useSuspend { client ->
                    client.createExecutionTree(query, allDescriptions)
                }
            val elapsedMs = System.currentTimeMillis() - start
            logger.info("⏱️ [Benchmark] 트리 생성만 호출: ${elapsedMs}ms (query=\"$query\")")
            TreeCreationBenchmarkResult(elapsedMs = elapsedMs, success = true, error = null)
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - start
            logger.warn("⏱️ [Benchmark] 트리 생성 실패: ${e.message} (${elapsedMs}ms)")
            TreeCreationBenchmarkResult(elapsedMs = elapsedMs, success = false, error = e.message)
        }
    }
    
    // CommonLayerInterface 구현
    
    override suspend fun describe(): com.hana.orchestrator.layer.LayerDescription {
        val allDescriptions = layerManager.getAllLayerDescriptions()
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
            val result = executeOrchestration(ChatDto(message = query))
            return result.result
        }
        
        // query가 없으면 자식 레이어의 함수명으로 위임
        val allDescriptions = layerManager.getAllLayerDescriptions()
        val targetLayer = layerManager.findLayerByName(function)
        return if (targetLayer != null) {
            executeOnLayer(function, "process", args)
        } else {
            val allFunctions = allDescriptions.flatMap { it.functions }
            "Unknown function: $function. Available: ${allFunctions.joinToString(", ")}"
        }
    }
    
    /**
     * 리소스 정리
     * 
     * 현재는 Factory 패턴으로 클라이언트를 필요 시 생성하므로 정리할 고정 인스턴스가 없음
     * 향후 클라이언트 풀링 구현 시 풀 정리 로직 추가 예정
     */
    suspend fun close() {
        // 현재 구현에서는 정리할 리소스가 없음
    }
}
