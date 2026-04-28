package com.hana.orchestrator.orchestrator.core

import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.orchestrator.ExecutionHistoryManager
import com.hana.orchestrator.orchestrator.ExecutionStatePublisher

/**
 * ReAct 루프 실행기 — [ReActStrategy] 위임자
 *
 * SRP: 실행 요청을 전략 구현체에 위임하는 것만 담당.
 * 실제 루프 로직은 [strategy]가 보유하며, [setStrategy]로 런타임 교체 가능.
 */
class ReactiveExecutor(
    layerManager: LayerManager,
    historyManager: ExecutionHistoryManager,
    statePublisher: ExecutionStatePublisher,
    modelSelectionStrategy: ModelSelectionStrategy,
    treeExecutor: TreeExecutor
) {
    private var strategy: ReActStrategy = DefaultReActStrategy(
        layerManager, historyManager, statePublisher, modelSelectionStrategy, treeExecutor
    )

    /**
     * 실행 전략을 런타임에 교체한다.
     * 진행 중인 실행이 없을 때 호출해야 한다.
     */
    fun setStrategy(newStrategy: ReActStrategy) {
        strategy = newStrategy
    }

    suspend fun execute(
        query: String,
        executionId: String,
        startTime: Long,
        projectContext: Map<String, String> = emptyMap()
    ): ExecutionResult = strategy.execute(query, executionId, startTime, projectContext)
}
