package com.hana.orchestrator.orchestrator.core.task

import com.hana.orchestrator.llm.VerifyOutcome

/**
 * HTN (Hierarchical Task Network) 의 작업 추상.
 *
 * 모든 작업 = Task. 두 구현:
 *  - [PrimitiveTask]    : atomic — 단일 layer.function 호출 (현재 ExecutionNode 대응)
 *  - [CompoundTask] : decomposable — subtasks 보유 (현재 Goal + subgoal pursue 대응)
 *
 * 통합 추상이라 재귀 자연:
 *   CompoundTask
 *     ├── PrimitiveTask      (leaf)
 *     ├── CompoundTask   (재귀)
 *     │     └── PrimitiveTask
 *     └── PrimitiveTask
 *
 * 외부 caller 인터페이스 (HTTP/MCP) 는 String query 유지 — Orchestrator 가
 * `CompoundTask(description="user query", query=...)` 로 wrap.
 *
 * 도입 단계 (HTN_MIGRATION.md 의 B1):
 *  - 신규 코드만. 기존 ExecutionNode/Tree 와 공존
 *  - B2 에서 Strategy entry 가 Task 받음
 *  - B3 에서 TreeExecutor 가 Task 직접 처리, ExecutionNode 점진 deprecate
 */
sealed interface Task {
    /** 사람·LLM 가독성 위한 한 줄 설명 */
    val description: String

    /** 결과 검증기 — null 이면 strategy 의 fallback verifier 사용 */
    val verifier: TaskVerifier?
}

/**
 * Task 결과 검증기 — 결과 string 받아 충족 여부 판단.
 * 함수형 인터페이스 — SAM 변환으로 lambda 작성 가능.
 *
 * 미래 구현:
 *  - LLMTaskVerifier  : LLM 한테 query vs result 충족 판정 (현 LLMFinishVerifier 와 결합)
 *  - CompileVerifier  : Kotlin 컴파일 통과 여부
 *  - PredicateVerifier: 임의 boolean 함수
 *  - CompositeVerifier: 여러 verifier AND/OR
 */
fun interface TaskVerifier {
    suspend fun verify(result: String): VerifyOutcome
}
