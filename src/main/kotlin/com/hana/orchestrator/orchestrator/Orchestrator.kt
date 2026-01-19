package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.layer.LayerFactory
import com.hana.orchestrator.layer.CommonLayerInterface
import com.hana.orchestrator.llm.OllamaLLMClient

class Orchestrator {
    
    private val layers = mutableListOf<CommonLayerInterface>()
     private val llmClient = OllamaLLMClient() // 임시로 비활성화
    
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
    
    suspend fun getAllLayerDescriptions(): List<com.hana.orchestrator.layer.LayerDescription> {
        return layers.map { it.describe() }.sortedBy { it.layerDepth }
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
        val layerDescriptions = layers.map { it.describe() }.sortedBy { it.layerDepth }
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
    
    /**
     * LLM을 통해 최적의 레이어를 선택하고 실행
     */
    suspend fun intelligentExecute(userQuery: String): List<String> {
        val layerDescriptions = getAllLayerDescriptions()
        val selectionResult = llmClient.selectOptimalLayers(userQuery, layerDescriptions)
        
        val results = mutableListOf<String>()
        results.add("LLM 선택 결과: ${selectionResult.reasoning}")
        results.add("실행 계획: ${selectionResult.executionPlan}")
        results.add("")
        
        for (layerName in selectionResult.selectedLayers) {
            try {
                val result = executeOnLayer(layerName, "echo", mapOf("message" to userQuery))
                results.add("[$layerName] 실행 결과: $result")
            } catch (e: Exception) {
                results.add("[$layerName] 실행 오류: ${e.message}")
            }
        }
        
        return results
    }
}