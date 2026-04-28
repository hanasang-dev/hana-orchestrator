package com.hana.orchestrator.orchestrator.core

import com.hana.orchestrator.domain.entity.ExecutionResult

/**
 * ReAct 루프 실행 전략 인터페이스
 *
 * 구현체를 교체함으로써 ReAct 루프의 의사결정·실행 방식을 런타임에 변경할 수 있다.
 * - [DefaultReActStrategy]: 표준 LLM-guided ReAct 루프
 */
interface ReActStrategy {
    suspend fun execute(
        query: String,
        executionId: String,
        startTime: Long,
        projectContext: Map<String, String> = emptyMap()
    ): ExecutionResult
}
