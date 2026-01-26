package com.hana.orchestrator.layer

/**
 * 텍스트 변환 레이어
 * 테스트용: 부모 레이어의 텍스트를 받아서 변환
 */
@Layer
class TextTransformerLayer : CommonLayerInterface {
    
    /**
     * 텍스트를 대문자로 변환
     */
    @LayerFunction
    suspend fun toUpperCase(text: String): String {
        return text.uppercase()
    }
    
    /**
     * 텍스트를 소문자로 변환
     */
    @LayerFunction
    suspend fun toLowerCase(text: String): String {
        return text.lowercase()
    }
    
    /**
     * 텍스트를 반전
     */
    @LayerFunction
    suspend fun reverse(text: String): String {
        return text.reversed()
    }
    
    /**
     * 텍스트에 접두사 추가
     */
    @LayerFunction
    suspend fun addPrefix(text: String, prefix: String = "PREFIX_"): String {
        return "$prefix$text"
    }
    
    /**
     * 텍스트에 접미사 추가
     */
    @LayerFunction
    suspend fun addSuffix(text: String, suffix: String = "_SUFFIX"): String {
        return "$text$suffix"
    }
    
    /**
     * 텍스트 길이 제한
     */
    @LayerFunction
    suspend fun truncate(text: String, maxLength: Int = 10): String {
        return if (text.length > maxLength) {
            text.take(maxLength) + "..."
        } else {
            text
        }
    }

    override suspend fun describe(): LayerDescription {
        return TextTransformerLayer_Description.layerDescription
    }
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "toUpperCase" -> {
                val text = (args["text"] as? String) ?: ""
                toUpperCase(text)
            }
            "toLowerCase" -> {
                val text = (args["text"] as? String) ?: ""
                toLowerCase(text)
            }
            "reverse" -> {
                val text = (args["text"] as? String) ?: ""
                reverse(text)
            }
            "addPrefix" -> {
                val text = (args["text"] as? String) ?: ""
                val prefix = (args["prefix"] as? String) ?: "PREFIX_"
                addPrefix(text, prefix)
            }
            "addSuffix" -> {
                val text = (args["text"] as? String) ?: ""
                val suffix = (args["suffix"] as? String) ?: "_SUFFIX"
                addSuffix(text, suffix)
            }
            "truncate" -> {
                val text = (args["text"] as? String) ?: ""
                val maxLength = (args["maxLength"] as? String)?.toIntOrNull() 
                    ?: (args["maxLength"] as? Int) ?: 10
                truncate(text, maxLength)
            }
            else -> "Unknown function: $function. Available: toUpperCase, toLowerCase, reverse, addPrefix, addSuffix, truncate"
        }
    }
}
