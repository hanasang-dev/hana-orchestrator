package com.hana.orchestrator.orchestrator.core.task

/**
 * Decomposable Task — subtasks 보유. 현재 Goal + subgoal 의 통합 표현.
 *
 * 두 가지 사용 패턴:
 *  1. Top-level (Orchestrator wrap):
 *     - caller 가 String query 보내면 Orchestrator 가
 *       `CompoundTask(description="user query", query=...)` 로 wrap
 *     - subtasks 는 ReAct 루프가 동적으로 채움 (LLM 결정 기반)
 *
 *  2. Subgoal (미래 H PR, pursue 메타액션):
 *     - LLM 이 큰 작업 중 일부를 별도 작업으로 분해
 *     - 부모 CompoundTask 의 subtasks 에 새 CompoundTask 추가
 *     - 재귀 ReAct 루프 spawn
 *
 * @property description 사람·LLM 가독성용 한 줄
 * @property query LLM 한테 노출되는 의도 (현 시스템의 `query: String` 자리)
 * @property subtasks 분해된 자식. 동적으로 채워짐. ReAct 가 매 step 트리 만들면서 추가
 * @property verifier 골 충족 검증기. null = LLMFinishVerifier 폴백
 * @property retryBudget 이 task 의 재시도 횟수 한도. 미래 H PR 시 활용
 */
data class CompoundTask(
    override val description: String,
    val query: String,
    val subtasks: List<Task> = emptyList(),
    override val verifier: TaskVerifier? = null,
    val retryBudget: Int = Int.MAX_VALUE
) : Task
