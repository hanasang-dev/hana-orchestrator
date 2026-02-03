package com.hana.orchestrator.layer

/**
 * 텍스트 생성 레이어
 * 
 * 목적: 단순한 텍스트 생성 및 반복 기능 제공
 * 
 * 주의사항:
 * - 번역, 언어 변환, 다국어 처리 기능은 제공하지 않습니다
 * - 단순히 텍스트를 생성하거나 반복하는 기능만 제공합니다
 * - generate: 기본 텍스트 반환
 * - generateRepeated: 텍스트를 지정된 횟수만큼 반복
 * - generateRandom: 랜덤 문자열 생성
 * 
 * 사용 예시:
 * - generate("Hello") -> "Hello"
 * - generateRepeated("Hi", 3) -> "HiHiHi"
 * - generateRandom(10) -> "aB3dEfG2hI"
 */
@Layer
class TextGeneratorLayer : CommonLayerInterface {
    
    /**
     * 기본 텍스트 생성
     * 입력된 텍스트를 그대로 반환합니다. 번역이나 변환 기능은 없습니다.
     */
    @LayerFunction
    suspend fun generate(text: String = "Hello World"): String {
        return text
    }
    
    /**
     * 반복 텍스트 생성
     * 입력된 텍스트를 지정된 횟수만큼 반복합니다. 언어 변환이나 번역 기능은 없습니다.
     * 예: generateRepeated("Hi", 3) -> "HiHiHi"
     */
    @LayerFunction
    suspend fun generateRepeated(text: String, count: Int = 1): String {
        return text.repeat(count)
    }
    
    /**
     * 랜덤 텍스트 생성
     * 영문자와 숫자로 구성된 랜덤 문자열을 생성합니다. 의미 있는 텍스트나 번역은 생성하지 않습니다.
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
