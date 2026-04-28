package com.hana.orchestrator.orchestrator.core

import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.orchestrator.ExecutionHistoryManager
import com.hana.orchestrator.orchestrator.ExecutionStatePublisher

/**
 * ReAct 전략이 런타임에 필요로 하는 의존성 묶음.
 *
 * 전략을 동적 로드할 때 생성자에 주입된다.
 * [DefaultReActStrategy]와 동일한 생성자 시그니처를 가진 클래스를 핫로드할 수 있다.
 */
data class StrategyContext(
    val layerManager: LayerManager,
    val historyManager: ExecutionHistoryManager,
    val statePublisher: ExecutionStatePublisher,
    val modelSelectionStrategy: ModelSelectionStrategy,
    val treeExecutor: TreeExecutor
)
