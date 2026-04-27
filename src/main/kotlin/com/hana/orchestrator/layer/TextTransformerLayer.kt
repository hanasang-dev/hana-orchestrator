package com.hana.orchestrator.layer

/**
 * 문자열 유틸리티 레이어 — 이미 완성된 문자열을 단순 변환(대소문자·접두어·자르기 등)하는 용도.
 * 코드 생성·수정·작성이 필요하면 이 레이어가 아닌 llm.analyze 를 사용하세요.
 */
@Layer
class TextTransformerLayer : CommonLayerInterface {

    /**
     * 문자열 전체를 대문자로 변환합니다. 소스코드나 파일 내용을 수정하는 데 사용하지 마세요.
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
