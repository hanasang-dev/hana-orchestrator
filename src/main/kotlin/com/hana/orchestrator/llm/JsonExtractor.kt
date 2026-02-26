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
        
        // 임의 텍스트에서 모든 균형 잡힌 JSON 후보를 순서대로 수집
        fun collectAll(text: String): List<String> {
            val result = mutableListOf<String>()
            var pos = 0
            while (pos < text.length) {
                val next = text.indexOfAny(charArrayOf('{', '['), pos)
                if (next < 0) break
                val open = text[next]; val close = if (open == '{') '}' else ']'
                val candidate = extractBalanced(text, next, open, close)
                if (candidate != null) { result.add(candidate); pos = next + candidate.length }
                else pos = next + 1
            }
            return result
        }

        val jsonBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        // 코드블록 내부에서 모든 JSON 후보 수집 (같은 블록 안에 execute_tree + finish 둘 다 있는 경우 처리)
        val blockCandidates = jsonBlockRegex.findAll(trimmed)
            .flatMap { collectAll(it.groupValues[1].trim()) }
            .toList()
        // 코드블록 밖 평문에서도 수집
        val plainCandidates = collectAll(jsonBlockRegex.replace(trimmed, " "))

        // 전체 후보 중 "finish" action 포함 블록 우선 선택
        val allCandidates = blockCandidates + plainCandidates
        extractedJson = allCandidates.firstOrNull { it.contains("\"finish\"") }
            ?: allCandidates.firstOrNull()
        
        // JSON이 아닌 경우 에러
        if (extractedJson == null) {
            throw IllegalArgumentException("LLM 응답에서 유효한 JSON을 찾을 수 없습니다. 응답: ${trimmed.take(200)}")
        }

        // 잘못된 JSON 이스케이프 수정 후 유니코드 이스케이프 정규화
        return normalizeUnicodeEscapes(sanitizeJsonEscapes(extractedJson!!))
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
     * JSON 문자열 내 잘못된 이스케이프 시퀀스 수정
     * 예: `\ @param` → `\\ @param` (backslash + space 는 JSON 불가 → double backslash)
     */
    private fun sanitizeJsonEscapes(json: String): String {
        val sb = StringBuilder(json.length)
        var inString = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (!inString) {
                if (c == '"') inString = true
                sb.append(c)
                i++
                continue
            }
            // inString = true
            if (c == '"') {
                inString = false
                sb.append(c)
                i++
                continue
            }
            if (c == '\\') {
                val next = if (i + 1 < json.length) json[i + 1] else '\u0000'
                when (next) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u' -> {
                        // 유효한 JSON 이스케이프 → \X 두 문자 모두 그대로 출력
                        sb.append('\\')
                        sb.append(next)
                        i += 2
                    }
                    else -> {
                        // 잘못된 이스케이프(예: "\ @param") → \\ 로 교체해서 리터럴 백슬래시로 처리
                        sb.append('\\')
                        sb.append('\\')
                        i++ // \ 만 소비, next 는 다음 루프에서 일반 문자로 처리
                    }
                }
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
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
