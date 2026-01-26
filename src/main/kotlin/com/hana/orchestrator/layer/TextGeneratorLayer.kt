package com.hana.orchestrator.layer

/**
 * 텍스트 생성 레이어
 * 테스트용: 간단한 텍스트를 생성하여 다음 레이어로 전달
 */
@Layer
class TextGeneratorLayer : CommonLayerInterface {
    
    /**
     * 기본 텍스트 생성
     */
    @LayerFunction
    suspend fun generate(text: String = "Hello World"): String {
        return text
    }
    
    /**
     * 반복 텍스트 생성
     */
    @LayerFunction
    suspend fun generateRepeated(text: String, count: Int = 1): String {
        return text.repeat(count)
    }
    
    /**
     * 랜덤 텍스트 생성 (간단한 버전)
     */
    @LayerFunction
    suspend fun generateRandom(length: Int = 10): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }

    override suspend fun describe(): LayerDescription {
        return TextGeneratorLayer_Description.layerDescription
    }
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "generate" -> {
                val text = (args["text"] as? String) ?: "Hello World"
                generate(text)
            }
            "generateRepeated" -> {
                val text = (args["text"] as? String) ?: "test"
                val count = (args["count"] as? String)?.toIntOrNull() 
                    ?: (args["count"] as? Int) ?: 1
                generateRepeated(text, count)
            }
            "generateRandom" -> {
                val length = (args["length"] as? String)?.toIntOrNull() 
                    ?: (args["length"] as? Int) ?: 10
                generateRandom(length)
            }
            else -> "Unknown function: $function. Available: generate, generateRepeated, generateRandom"
        }
    }
}
