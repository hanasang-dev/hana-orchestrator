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
        // JSON 코드 블록 찾기 (```json ... ```)
        val jsonBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        jsonBlockRegex.find(response)?.let {
            return it.groupValues[1].trim()
        }
        
        // 중괄호로 시작하는 JSON 찾기
        val jsonStart = response.indexOf('{')
        val jsonEnd = response.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1)
        }
        
        // 그 외에는 전체 응답 반환
        return response.trim()
    }
}
