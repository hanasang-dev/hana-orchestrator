package com.hana.orchestrator.llm

/**
 * LLM 호출의 모델 tier 를 표시하는 어노테이션.
 * KSP 프로세서가 이를 읽어 ModelSelectionStrategy 구현체 자동 생성.
 *
 * 이름이 "LLMTier" 인 이유: HTN 의 런타임 `Task` 추상과 명목 충돌 회피.
 *
 * 사용 예:
 * ```kotlin
 * @LLMTier(complexity = LLMTaskComplexity.SIMPLE)
 * suspend fun reviewTree(...): TreeReview
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class LLMTier(
    val complexity: LLMTaskComplexity
)
