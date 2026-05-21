package com.hana.orchestrator.orchestrator.core

import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.orchestrator.ExecutionHistoryManager
import com.hana.orchestrator.orchestrator.ExecutionStatePublisher
import com.hana.orchestrator.orchestrator.core.task.CompoundTask
import com.hana.orchestrator.orchestrator.core.task.Task

/**
 * ReAct 루프 실행기 — [ReActStrategy] 위임자
 *
 * SRP: 실행 요청을 전략 구현체에 위임하는 것만 담당.
 * 실제 루프 로직은 [strategy]가 보유하며, [setStrategy]로 런타임 교체 가능.
 *
 * HTN B2: 외부 caller 는 여전히 String query 사용. 내부에서 [CompoundTask] 로 wrap.
 */
class ReactiveExecutor(
    layerManager: LayerManager,
    historyManager: ExecutionHistoryManager,
    statePublisher: ExecutionStatePublisher,
    modelSelectionStrategy: ModelSelectionStrategy,
    treeExecutor: TreeExecutor,
    clarificationGate: com.hana.orchestrator.orchestrator.ClarificationGate? = null
) {
    private var strategy: ReActStrategy = DefaultReActStrategy(
        layerManager, historyManager, statePublisher, modelSelectionStrategy, treeExecutor, clarificationGate
    )

    /**
     * 실행 전략을 런타임에 교체한다.
     * 진행 중인 실행이 없을 때 호출해야 한다.
     */
    fun setStrategy(newStrategy: ReActStrategy) {
        strategy = newStrategy
    }

    /** Legacy entry — String query 받아 CompoundTask 로 wrap 후 strategy 위임 */
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

    /** Primary entry — Task 직접 받음. 외부 caller 가 Task 만들 때 사용 */
    suspend fun execute(
        task: Task,
        executionId: String,
        startTime: Long,
        projectContext: Map<String, String> = emptyMap(),
        isScheduled: Boolean = false
    ): ExecutionResult = strategy.execute(task, executionId, startTime, projectContext, isScheduled)
}
