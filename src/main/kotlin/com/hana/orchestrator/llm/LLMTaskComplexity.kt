package com.hana.orchestrator.llm

/**
 * LLM 작업의 복잡도
 * 각 메서드별로 명시적으로 정의 (LLM에게 물어보지 않음)
 */
enum class LLMTaskComplexity {
    /**
     * 간단한 작업: 짧은 프롬프트, 빠른 응답
     * 예: validateQueryFeasibility, extractParameters
     */
    SIMPLE,
    
    /**
     * 중간 작업: 중간 프롬프트
     * 예: evaluateResult, compareExecutions
     */
    MEDIUM,
    
    /**
     * 복잡한 작업: 긴 프롬프트, 많은 컨텍스트
     * 예: createExecutionTree, suggestRetryStrategy
     */
    COMPLEX
}
