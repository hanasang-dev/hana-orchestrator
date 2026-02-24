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
        
        // 중괄호로 시작하는 JSON 찾기 (문자열 리터럴 내부의 { } 는 무시)
        if (extractedJson == null) {
            val jsonStart = trimmed.indexOf('{')
            if (jsonStart >= 0) {
                extractedJson = extractBalanced(trimmed, jsonStart, '{', '}')
            }
        }

        // 대괄호로 시작하는 JSON 배열 찾기 (문자열 리터럴 내부의 [ ] 는 무시)
        if (extractedJson == null) {
            val arrayStart = trimmed.indexOf('[')
            if (arrayStart >= 0) {
                extractedJson = extractBalanced(trimmed, arrayStart, '[', ']')
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
     * 문자열 리터럴을 인식하며 균형 잡힌 괄호 쌍을 추출
     * open/close 내부의 "..." 문자열 안에 있는 괄호는 카운트하지 않음
     */
    private fun extractBalanced(text: String, startIdx: Int, open: Char, close: Char): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIdx until text.length) {
            val c = text[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\' && inString) { escaped = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return text.substring(startIdx, i + 1)
                }
            }
        }
        return null
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
