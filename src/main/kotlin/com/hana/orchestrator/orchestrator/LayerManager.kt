package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.layer.CommonLayerInterface
import com.hana.orchestrator.layer.LayerDescription
import com.hana.orchestrator.layer.LayerFactory
import com.hana.orchestrator.layer.LayerInfoLayer
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy

/**
 * 레이어 관리 책임
 * SRP: 레이어 등록, 조회, 설명 관리만 담당
 */
class LayerManager(
    private val modelSelectionStrategy: ModelSelectionStrategy? = null
) {
    private val layers = mutableListOf<CommonLayerInterface>()
    private val cachedDescriptions = mutableSetOf<LayerDescription>()
    private val layerNameMap = mutableMapOf<String, CommonLayerInterface>() // 이름 -> 레이어 매핑
    private var isInitialized = false
    private val logger = createOrchestratorLogger(LayerManager::class.java, null)
    
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
            val defaultLayers = LayerFactory.createDefaultLayers(modelSelectionStrategy)
            logger.info("🔧 [LayerManager] 기본 레이어 초기화: ${defaultLayers.size}개 레이어 등록")
            defaultLayers.forEach { layer ->
                logger.debug("  - 레이어 인스턴스 생성됨: ${layer::class.simpleName}")
                val desc = layer.describe()
                layerNameMap[desc.name] = layer
            }
            layers.addAll(defaultLayers)
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
        // 레이어 등록 시 캐시 무효화
        cachedDescriptions.clear()
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
