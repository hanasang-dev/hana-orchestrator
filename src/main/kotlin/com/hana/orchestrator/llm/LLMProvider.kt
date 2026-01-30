package com.hana.orchestrator.llm

/**
 * LLM 프로바이더 타입
 * 
 * 확장 가능한 구조: 향후 클라우드 API 추가 시 이 enum에만 추가하면 됨
 */
enum class LLMProvider {
    /**
     * 로컬 Ollama 인스턴스
     */
    OLLAMA,
    
    /**
     * OpenAI API (향후 지원 예정)
     */
    OPENAI,
    
    /**
     * Anthropic Claude API (향후 지원 예정)
     */
    ANTHROPIC;
    
    companion object {
        /**
         * 문자열에서 Provider 파싱
         * 기본값은 OLLAMA
         */
        fun fromString(value: String?): LLMProvider {
            return when (value?.uppercase()) {
                "OPENAI" -> OPENAI
                "ANTHROPIC" -> ANTHROPIC
                "OLLAMA", null, "" -> OLLAMA
                else -> OLLAMA // 기본값
            }
        }
    }
}