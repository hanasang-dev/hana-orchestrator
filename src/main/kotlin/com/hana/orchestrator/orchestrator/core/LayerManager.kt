package com.hana.orchestrator.orchestrator.core

import com.hana.orchestrator.layer.CommonLayerInterface
import com.hana.orchestrator.layer.LayerDescription
import com.hana.orchestrator.layer.LayerFactory
import com.hana.orchestrator.layer.LayerInfoLayer
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.orchestrator.ApprovalGate
import com.hana.orchestrator.orchestrator.createOrchestratorLogger

/**
 * 레이어 관리 책임
 * SRP: 레이어 등록, 조회, 설명 관리만 담당
 */
class LayerManager(
    private val modelSelectionStrategy: ModelSelectionStrategy? = null,
    private val approvalGate: ApprovalGate? = null
) {
    private val layers = mutableListOf<CommonLayerInterface>()
    private val cachedDescriptions = mutableSetOf<LayerDescription>()
    private val layerNameMap = mutableMapOf<String, CommonLayerInterface>() // 이름 -> 레이어 매핑
    private var isInitialized = false
    private val logger = createOrchestratorLogger(LayerManager::class.java, null)

    private var reactiveExecutor: ReactiveExecutor? = null
    private var strategyContext: StrategyContext? = null

    /**
     * DevelopLayer의 전략 핫로드에 필요한 의존성을 설정한다.
     * Orchestrator 초기화 완료 후, 첫 요청 전에 호출되어야 한다.
     */
    fun wireReactiveExecutor(executor: ReactiveExecutor, ctx: StrategyContext) {
        reactiveExecutor = executor
        strategyContext = ctx
    }
    
    /**
     * 기본 레이어 초기화 (lazy initialization)
     */
    private suspend fun ensureInitialized() {
        if (!isInitialized) {
            // LayerInfoLayer 먼저 등록 (다른 레이어 정보 조회용)
            val layerInfoLayer = LayerInfoLayer()
            layerInfoLayer.setLayerManager(this)
            val layerInfoDesc = layerInfoLayer.describe()
            layerNameMap[layerInfoDesc.name] = layerInfoLayer
            layers.add(layerInfoLayer)
            logger.debug("  - 레이어 인스턴스 생성됨: LayerInfoLayer")
            
            // 기본 레이어 등록
            val defaultLayers = LayerFactory.createDefaultLayers(modelSelectionStrategy, approvalGate)
            logger.info("🔧 [LayerManager] 기본 레이어 초기화: ${defaultLayers.size}개 레이어 등록")
            defaultLayers.forEach { layer ->
                logger.debug("  - 레이어 인스턴스 생성됨: ${layer::class.simpleName}")
                val desc = layer.describe()
                layerNameMap[desc.name] = layer
            }
            layers.addAll(defaultLayers)

            // DevelopLayer에 LayerManager + ReactiveExecutor + StrategyContext 주입
            defaultLayers.filterIsInstance<com.hana.orchestrator.layer.DevelopLayer>()
                .firstOrNull()?.also { developLayer ->
                    developLayer.setLayerManager(this)
                    reactiveExecutor?.let { developLayer.setReactiveExecutor(it) }
                    strategyContext?.let { developLayer.setStrategyContext(it) }
                }

            // CoreEvaluationLayer에 ReactiveExecutor 주입 (runScenario용)
            defaultLayers.filterIsInstance<com.hana.orchestrator.layer.CoreEvaluationLayer>()
                .firstOrNull()?.also { coreEvalLayer ->
                    reactiveExecutor?.let { coreEvalLayer.setReactiveExecutor(it) }
                }

            // 영속 레지스트리에서 이전에 핫로드된 레이어 복원
            val projectRoot = java.io.File(System.getProperty("user.dir"))
            val persistedLayers = com.hana.orchestrator.layer.LayerRegistry.loadAll(
                projectRoot, LayerManager::class.java.classLoader
            )
            var restoredCount = 0
            for (layer in persistedLayers) {
                val desc = layer.describe()
                if (desc.name !in layerNameMap) {
                    layers.add(layer)
                    layerNameMap[desc.name] = layer
                    restoredCount++
                    logger.info("  - 영속 레이어 복원: ${layer::class.simpleName} (${desc.name})")
                }
            }
            if (restoredCount > 0) {
                logger.info("♻️ [LayerManager] 영속 레지스트리에서 ${restoredCount}개 레이어 복원 완료")
            }

            isInitialized = true
            logger.info("✅ [LayerManager] 총 ${layers.size}개 레이어 등록 완료")
        }
    }
    
    /**
     * 기본 레이어 초기화 (public API)
     */
    suspend fun initializeDefaultLayers() {
        ensureInitialized()
    }
    
    /**
     * 레이어 등록
     */
    suspend fun registerLayer(layer: CommonLayerInterface) {
        ensureInitialized()
        layers.add(layer)
        val desc = layer.describe()
        layerNameMap[desc.name] = layer
        cachedDescriptions.clear()
    }

    /**
     * 레이어 등록 해제 (reloadLayer에서 교체 전 사용)
     * @return 실제로 제거됐으면 true, 등록된 적 없으면 false
     */
    suspend fun unregisterLayer(layerName: String): Boolean {
        ensureInitialized()
        val layer = layerNameMap.remove(layerName) ?: return false
        layers.remove(layer)
        cachedDescriptions.clear()
        return true
    }
    
    /**
     * 레이어 조회 (캐시된 이름 매핑 사용)
     */
    fun findLayerByName(layerName: String): CommonLayerInterface? {
        return layerNameMap[layerName]
    }
    
    /**
     * 모든 레이어 조회
     */
    fun getAllLayers(): List<CommonLayerInterface> {
        return layers.toList()
    }
    
    /**
     * 모든 레이어 설명 조회 (캐시 사용)
     */
    suspend fun getAllLayerDescriptions(): List<LayerDescription> {
        ensureInitialized()
        // 캐시가 비어있거나 레이어 수가 변경되었으면 갱신
        if (cachedDescriptions.isEmpty() || cachedDescriptions.size != layers.size) {
            cachedDescriptions.clear()
            // suspend 함수를 호출해야 하므로 각 레이어에 대해 순차적으로 호출
            val descriptions = mutableListOf<LayerDescription>()
            for (layer in layers) {
                descriptions.add(layer.describe())
            }
            cachedDescriptions.addAll(descriptions)
        }
        return cachedDescriptions.toList()
    }
    
    /**
     * 레이어에서 함수 실행
     */
    suspend fun executeOnLayer(layerName: String, function: String, args: Map<String, Any> = emptyMap()): String {
        ensureInitialized()
        val targetLayer = findLayerByName(layerName)
        
        if (targetLayer == null) {
            val availableLayers = layerNameMap.keys.toList()
            return "Layer '$layerName' not found. Available layers: $availableLayers"
        }
        
        return targetLayer.execute(function, args)
    }
}
