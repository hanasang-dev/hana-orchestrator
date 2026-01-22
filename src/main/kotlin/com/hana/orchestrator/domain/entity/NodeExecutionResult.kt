package com.hana.orchestrator.domain.entity

/**
 * 노드 실행 결과 추적
 */
data class NodeExecutionResult(
    val nodeId: String,
    val node: ExecutionNode,
    val status: NodeStatus,
    val result: String? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val depth: Int = 0,
    val parentNodeId: String? = null
)
