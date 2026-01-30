package com.hana.orchestrator.llm

/**
 * JSON 추출 유틸리티
 * SRP: JSON 추출 로직만 담당
 */
internal object JsonExtractor {
    /**
     * LLM 응답에서 JSON 부분만 추출
     * 마크다운 코드 블록이나 설명 텍스트가 포함될 수 있음
     */
    fun extract(response: String): String {
        val trimmed = response.trim()
        
        // 빈 응답 체크
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("LLM 응답이 비어있습니다")
        }
        
        // JSON 코드 블록 찾기 (```json ... ```)
        val jsonBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        jsonBlockRegex.find(trimmed)?.let {
            val extracted = it.groupValues[1].trim()
            if (extracted.isNotEmpty() && (extracted.startsWith("{") || extracted.startsWith("["))) {
                return extracted
            }
        }
        
        // 중괄호로 시작하는 JSON 찾기
        val jsonStart = trimmed.indexOf('{')
        if (jsonStart >= 0) {
            var braceCount = 0
            var jsonEnd = jsonStart
            for (i in jsonStart until trimmed.length) {
                when (trimmed[i]) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            jsonEnd = i
                            break
                        }
                    }
                }
            }
            if (jsonEnd > jsonStart && braceCount == 0) {
                return trimmed.substring(jsonStart, jsonEnd + 1)
            }
        }
        
        // 대괄호로 시작하는 JSON 배열 찾기
        val arrayStart = trimmed.indexOf('[')
        if (arrayStart >= 0) {
            var bracketCount = 0
            var arrayEnd = arrayStart
            for (i in arrayStart until trimmed.length) {
                when (trimmed[i]) {
                    '[' -> bracketCount++
                    ']' -> {
                        bracketCount--
                        if (bracketCount == 0) {
                            arrayEnd = i
                            break
                        }
                    }
                }
            }
            if (arrayEnd > arrayStart && bracketCount == 0) {
                return trimmed.substring(arrayStart, arrayEnd + 1)
            }
        }
        
        // JSON이 아닌 경우 에러
        throw IllegalArgumentException("LLM 응답에서 유효한 JSON을 찾을 수 없습니다. 응답: ${trimmed.take(200)}")
    }
}
