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
        
        var extractedJson: String? = null
        
        // JSON 코드 블록 찾기 (```json ... ```)
        val jsonBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        jsonBlockRegex.find(trimmed)?.let {
            val extracted = it.groupValues[1].trim()
            if (extracted.isNotEmpty() && (extracted.startsWith("{") || extracted.startsWith("["))) {
                extractedJson = extracted
            }
        }
        
        // 중괄호로 시작하는 JSON 찾기
        if (extractedJson == null) {
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
                    extractedJson = trimmed.substring(jsonStart, jsonEnd + 1)
                }
            }
        }
        
        // 대괄호로 시작하는 JSON 배열 찾기
        if (extractedJson == null) {
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
                    extractedJson = trimmed.substring(arrayStart, arrayEnd + 1)
                }
            }
        }
        
        // JSON이 아닌 경우 에러
        if (extractedJson == null) {
            throw IllegalArgumentException("LLM 응답에서 유효한 JSON을 찾을 수 없습니다. 응답: ${trimmed.take(200)}")
        }
        
        // 유니코드 이스케이프 정규화: \u{...} 형식을 일반 문자열로 변환
        return normalizeUnicodeEscapes(extractedJson)
    }
    
    /**
     * 유니코드 이스케이프를 일반 문자열로 변환
     * 예: \u{c548}\u{d55c} -> 안녕
     * 잘못된 형식(\u{C5AC번 등)도 처리
     */
    private fun normalizeUnicodeEscapes(json: String): String {
        // \u{XXXX} 또는 \u{XXXX문자...} 형식 모두 처리
        val unicodeRegex = Regex("\\\\u\\{([0-9a-fA-F]+)([^}]*)\\}")
        
        return unicodeRegex.replace(json) { matchResult ->
            val hexCode = matchResult.groupValues[1]
            val trailingChars = matchResult.groupValues[2]
            
            try {
                val codePoint = hexCode.toInt(16)
                if (codePoint in 0..0x10FFFF) {
                    String(Character.toChars(codePoint)) + trailingChars
                } else {
                    matchResult.value // 변환 실패 시 원본 유지
                }
            } catch (e: Exception) {
                matchResult.value // 변환 실패 시 원본 유지
            }
        }
    }
}
