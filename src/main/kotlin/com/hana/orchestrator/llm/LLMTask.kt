package com.hana.orchestrator.llm

/**
 * LLM 작업의 복잡도를 표시하는 어노테이션
 * KSP 프로세서가 이를 읽어서 모델 선택 전략 생성
 * 
 * 사용 예:
 * ```kotlin
 * @LLMTask(complexity = LLMTaskComplexity.SIMPLE)
 * suspend fun validateQueryFeasibility(...): QueryFeasibility
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class LLMTask(
    val complexity: LLMTaskComplexity
)
