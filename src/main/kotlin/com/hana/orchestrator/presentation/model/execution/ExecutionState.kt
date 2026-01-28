package com.hana.orchestrator.presentation.model.execution

import kotlinx.serialization.Serializable

/**
 * WebSocket을 통한 실행 상태 전송용 모델
 * SRP: 실행 상태 데이터만 담당
 */
@Serializable
data class ExecutionState(
    val id: String,
    val query: String,
    val result: String,
    val error: String? = null,
    val startTime: Long,
    val endTime: Long? = null,
    val status: String,
    val nodeCount: Int = 0,
    val completedNodes: Int = 0,
    val failedNodes: Int = 0,
    val runningNodes: Int = 0,
    val logs: List<String> = emptyList(),
    val executionTree: ExecutionTreeResponse? = null
)

@Serializable
data class ExecutionUpdateMessage(
    val history: List<ExecutionState>,
    val current: ExecutionState? = null
)
