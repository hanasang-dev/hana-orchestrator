package com.hana.orchestrator.presentation.model.execution

import kotlinx.serialization.Serializable

@Serializable
data class ExecutionHistoryResponse(
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
    val logs: List<String> = emptyList()
)

@Serializable
data class ExecutionHistoryListResponse(
    val history: List<ExecutionHistoryResponse>,
    val current: ExecutionHistoryResponse? = null
)
