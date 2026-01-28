package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.layer.CommonLayerInterface
import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.llm.strategy.GeneratedModelSelectionStrategy
import com.hana.orchestrator.llm.factory.LLMClientFactory
import com.hana.orchestrator.llm.factory.DefaultLLMClientFactory
import kotlinx.coroutines.flow.SharedFlow

/**
 * 오케스트레이터 Facade
 * SRP: 각 책임을 담당하는 컴포넌트들을 조합하여 제공
 * Facade 패턴: 복잡한 서브시스템을 단순한 인터페이스로 제공
 */
class Orchestrator(
    private val llmConfig: LLMConfig? = null
) : CommonLayerInterface {
    
    // 컴포넌트들 (의존성 주입)
    private val layerManager: LayerManager
    private val historyManager: ExecutionHistoryManager
    private val statePublisher: ExecutionStatePublisher
    private val treeExecutor: TreeExecutor
    private val coordinator: OrchestrationCoordinator
    
    // LLM 관련
    private val clientFactory: LLMClientFactory
    private val modelSelectionStrategy: ModelSelectionStrategy
    
    // Logger
    private val logger = createOrchestratorLogger(Orchestrator::class.java, null)
    
    init {
        // LLM 설정 초기화
        val config = llmConfig ?: LLMConfig.fromEnvironment()
        clientFactory = DefaultLLMClientFactory(config)
        modelSelectionStrategy = GeneratedModelSelectionStrategy(clientFactory = clientFactory)
        
        // 컴포넌트 초기화
        layerManager = LayerManager()
        historyManager = ExecutionHistoryManager()
        statePublisher = ExecutionStatePublisher()
        treeExecutor = TreeExecutor(layerManager, statePublisher, historyManager)
        coordinator = OrchestrationCoordinator(
            layerManager = layerManager,
            treeExecutor = treeExecutor,
            historyManager = historyManager,
            statePublisher = statePublisher,
            modelSelectionStrategy = modelSelectionStrategy
        )
        
        // 기본 레이어 초기화는 suspend 함수이므로 init 블록에서는 할 수 없음
        // 대신 첫 실행 시 초기화하도록 변경 필요하지만, 일단은 나중에 처리
        logger.info("🚀 [Orchestrator] 초기화 시작...")
        logger.info("⚠️ [Orchestrator] 레이어 초기화는 첫 실행 시 수행됩니다")
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
    suspend fun executeOrchestration(query: String): ExecutionResult {
        return coordinator.executeOrchestration(query)
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
            val result = executeOrchestration(query)
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
     */
    suspend fun close() {
        // Factory 패턴으로 변경되어 고정된 클라이언트 인스턴스가 없음
        // 향후 클라이언트 풀링을 구현하면 여기서 풀 정리 로직 추가
    }
}
