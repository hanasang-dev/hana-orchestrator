package com.hana.orchestrator.orchestrator.core

import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.orchestrator.core.task.CompoundTask
import com.hana.orchestrator.orchestrator.core.task.Task

/**
 * ReAct 루프 실행 전략 인터페이스
 *
 * 구현체를 교체함으로써 ReAct 루프의 의사결정·실행 방식을 런타임에 변경할 수 있다.
 * - [DefaultReActStrategy]: 표준 LLM-guided ReAct 루프
 *
 * HTN 마이그레이션 (B2): primary 진입점 = [Task] 기반. 기존 String query 진입점은
 * legacy default 로 보존되며 자동으로 [CompoundTask] 로 wrap 된다.
 */
interface ReActStrategy {
    /** Primary entry — Task 기반. 구현체가 override 한다. */
    suspend fun execute(
        task: Task,
        executionId: String,
        startTime: Long,
        projectContext: Map<String, String> = emptyMap(),
        isScheduled: Boolean = false
    ): ExecutionResult

    /**
     * Legacy entry — String query 받는 호환 경로.
     * 자동으로 [CompoundTask] 로 wrap 해서 primary execute 위임.
     * 핫로드된 구버전 strategy 후보가 이 시그니처를 override 해도 동작하도록 default 제공.
     */
    suspend fun execute(
        query: String,
        executionId: String,
        startTime: Long,
        projectContext: Map<String, String> = emptyMap(),
        isScheduled: Boolean = false
    ): ExecutionResult = execute(
        task = CompoundTask(description = "user query", query = query),
        executionId = executionId,
        startTime = startTime,
        projectContext = projectContext,
        isScheduled = isScheduled
    )
}
