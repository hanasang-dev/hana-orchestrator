package com.hana.orchestrator.layer

/**
 * 레이어 정보 조회 레이어
 * 등록된 레이어와 함수 목록을 조회하는 기능 제공
 */
@Layer
class LayerInfoLayer : CommonLayerInterface {
    
    // LayerManager 참조는 런타임에 주입받음
    private var layerManagerRef: com.hana.orchestrator.orchestrator.core.LayerManager? = null
    
    /**
     * LayerManager 참조 설정 (Orchestrator에서 주입)
     */
    fun setLayerManager(layerManager: com.hana.orchestrator.orchestrator.core.LayerManager) {
        layerManagerRef = layerManager
    }
    
    /**
     * 모든 레이어 목록과 함수 설명을 반환
     */
    @LayerFunction
    suspend fun listLayers(): String {
        val layerManager = layerManagerRef ?: return "레이어 매니저가 초기화되지 않았습니다."
        val descriptions = layerManager.getAllLayerDescriptions()
        
        if (descriptions.isEmpty()) {
            return "등록된 레이어가 없습니다."
        }
        
        val result = StringBuilder()
        result.append("등록된 레이어 목록:\n\n")
        
        descriptions.forEach { layerDesc ->
            result.append("📦 ${layerDesc.name}\n")
            result.append("   설명: ${layerDesc.description}\n")
            
            if (layerDesc.functionDetails.isNotEmpty()) {
                result.append("   함수:\n")
                layerDesc.functionDetails.values.forEach { funcDesc ->
                    val params = funcDesc.parameters.entries.joinToString(", ") { (name, param) ->
                        val req = if (param.required) "필수" else "선택"
                        "$name:${param.type}($req)"
                    }
                    result.append("     - ${funcDesc.name}($params)\n")
                    if (funcDesc.description.isNotBlank()) {
                        result.append("       ${funcDesc.description}\n")
                    }
                }
            } else if (layerDesc.functions.isNotEmpty()) {
                result.append("   함수: ${layerDesc.functions.joinToString(", ")}\n")
            }
            
            result.append("\n")
        }
        
        return result.toString().trim()
    }
    
    /**
     * 특정 레이어의 상세 정보 반환
     */
    @LayerFunction
    suspend fun getLayerInfo(layerName: String): String {
        val layerManager = layerManagerRef ?: return "레이어 매니저가 초기화되지 않았습니다."
        val descriptions = layerManager.getAllLayerDescriptions()
        val layerDesc = descriptions.find { it.name == layerName }
        
        if (layerDesc == null) {
            val availableLayers = descriptions.map { it.name }.joinToString(", ")
            return "레이어 '$layerName'을 찾을 수 없습니다. 사용 가능한 레이어: $availableLayers"
        }
        
        val result = StringBuilder()
        result.append("📦 ${layerDesc.name}\n")
        result.append("설명: ${layerDesc.description}\n\n")
        
        if (layerDesc.functionDetails.isNotEmpty()) {
            result.append("함수 목록:\n")
            layerDesc.functionDetails.values.forEach { funcDesc ->
                val params = funcDesc.parameters.entries.joinToString(", ") { (name, param) ->
                    val req = if (param.required) "필수" else "선택"
                    val defaultValue = param.defaultValue?.let { " (기본값: $it)" } ?: ""
                    "$name:${param.type}($req)$defaultValue"
                }
                result.append("\n- ${funcDesc.name}($params)\n")
                if (funcDesc.description.isNotBlank()) {
                    result.append("  ${funcDesc.description}\n")
                }
            }
        } else if (layerDesc.functions.isNotEmpty()) {
            result.append("함수: ${layerDesc.functions.joinToString(", ")}\n")
        }
        
        return result.toString().trim()
    }

    override suspend fun describe(): LayerDescription {
        return LayerInfoLayer_Description.layerDescription
    }
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "listLayers" -> listLayers()
            "getLayerInfo" -> {
                val layerName = (args["layerName"] as? String) ?: ""
                getLayerInfo(layerName)
            }
            else -> "Unknown function: $function. Available: listLayers, getLayerInfo"
        }
    }
}
