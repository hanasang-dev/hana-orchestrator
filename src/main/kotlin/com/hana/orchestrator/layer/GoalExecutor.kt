package com.hana.orchestrator.layer

/**
 * 서브 목표 실행 계약 — AgentLayer의 의존성 역전 핵심.
 *
 * layer 패키지가 인터페이스를 소유하고, orchestrator가 구현체를 제공.
 * AgentLayer는 이 인터페이스만 알고 ReactiveExecutor를 직접 참조하지 않음.
 */
fun interface GoalExecutor {
    /**
     * 주어진 목표를 실행하고 결과 문자열 반환.
     * 구현체가 새 ReAct 루프를 스폰한다.
     */
    suspend fun execute(goal: String): String
}
