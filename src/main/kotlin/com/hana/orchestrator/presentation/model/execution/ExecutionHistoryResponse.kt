package com.hana.orchestrator.presentation.model.execution

import kotlinx.serialization.Serializable

@Serializable
data class ExecutionTreeNodeResponse(
    val layerName: String,
    val function: String,
    val args: Map<String, String> = emptyMap(),
    val children: List<ExecutionTreeNodeResponse> = emptyList(),
    val parallel: Boolean = false,
    val id: String
)

@Serializable
data class ExecutionTreeResponse(
    val rootNode: ExecutionTreeNodeResponse,
    val name: String = "execution_plan"
)

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
    val logs: List<String> = emptyList(),
    val executionTree: ExecutionTreeResponse? = null
)

@Serializable
data class ExecutionHistoryListResponse(
    val history: List<ExecutionHistoryResponse>,
    val current: ExecutionHistoryResponse? = null
)
