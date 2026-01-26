package com.hana.orchestrator.layer

/**
 * 텍스트 검증 레이어
 * 테스트용: 부모 레이어의 텍스트를 받아서 검증
 * 실패 시나리오 테스트를 위해 조건부 실패 기능 포함
 */
@Layer
class TextValidatorLayer : CommonLayerInterface {
    
    /**
     * 텍스트 길이 검증
     */
    @LayerFunction
    suspend fun validateLength(text: String, minLength: Int = 0, maxLength: Int = Int.MAX_VALUE): String {
        val length = text.length
        if (length < minLength) {
            throw IllegalArgumentException("Text length $length is less than minimum $minLength")
        }
        if (length > maxLength) {
            throw IllegalArgumentException("Text length $length exceeds maximum $maxLength")
        }
        return "Valid: length=$length"
    }
    
    /**
     * 텍스트 패턴 검증 (간단한 버전)
     */
    @LayerFunction
    suspend fun validatePattern(text: String, pattern: String = ".*"): String {
        val regex = pattern.toRegex()
        if (!regex.matches(text)) {
            throw IllegalArgumentException("Text does not match pattern: $pattern")
        }
        return "Valid: matches pattern=$pattern"
    }
    
    /**
     * 텍스트가 비어있지 않은지 검증
     */
    @LayerFunction
    suspend fun validateNotEmpty(text: String): String {
        if (text.isBlank()) {
            throw IllegalArgumentException("Text is empty or blank")
        }
        return "Valid: text is not empty"
    }
    
    /**
     * 텍스트에 특정 문자열 포함 여부 검증
     */
    @LayerFunction
    suspend fun validateContains(text: String, substring: String): String {
        if (!text.contains(substring)) {
            throw IllegalArgumentException("Text does not contain: $substring")
        }
        return "Valid: contains '$substring'"
    }
    
    /**
     * 조건부 실패 (테스트용)
     * failIf 파라미터가 true이면 실패
     */
    @LayerFunction
    suspend fun conditionalValidate(text: String, failIf: Boolean = false): String {
        if (failIf) {
            throw IllegalStateException("Conditional validation failed: failIf=true")
        }
        return "Valid: text='$text'"
    }

    override suspend fun describe(): LayerDescription {
        return TextValidatorLayer_Description.layerDescription
    }
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "validateLength" -> {
                val text = (args["text"] as? String) ?: ""
                val minLength = (args["minLength"] as? String)?.toIntOrNull() 
                    ?: (args["minLength"] as? Int) ?: 0
                val maxLength = (args["maxLength"] as? String)?.toIntOrNull() 
                    ?: (args["maxLength"] as? Int) ?: Int.MAX_VALUE
                validateLength(text, minLength, maxLength)
            }
            "validatePattern" -> {
                val text = (args["text"] as? String) ?: ""
                val pattern = (args["pattern"] as? String) ?: ".*"
                validatePattern(text, pattern)
            }
            "validateNotEmpty" -> {
                val text = (args["text"] as? String) ?: ""
                validateNotEmpty(text)
            }
            "validateContains" -> {
                val text = (args["text"] as? String) ?: ""
                val substring = (args["substring"] as? String) ?: ""
                validateContains(text, substring)
            }
            "conditionalValidate" -> {
                val text = (args["text"] as? String) ?: ""
                val failIf = (args["failIf"] as? String)?.toBooleanStrictOrNull() 
                    ?: (args["failIf"] as? Boolean) ?: false
                conditionalValidate(text, failIf)
            }
            else -> "Unknown function: $function. Available: validateLength, validatePattern, validateNotEmpty, validateContains, conditionalValidate"
        }
    }
}
