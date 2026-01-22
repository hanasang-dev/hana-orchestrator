package com.hana.orchestrator.domain.entity

/**
 * 실행 컨텍스트 - 전체 실행 상태 추적
 */
class ExecutionContext {
    val nodeResults = mutableMapOf<String, NodeExecutionResult>()
    
    // 완료된 노드 (성공)
    val completedNodes: List<NodeExecutionResult>
        get() = nodeResults.values.filter { it.status == NodeStatus.SUCCESS }
    
    // 실패한 노드
    val failedNodes: List<NodeExecutionResult>
        get() = nodeResults.values.filter { it.status == NodeStatus.FAILED }
    
    // 실행 중인 노드
    val runningNodes: List<NodeExecutionResult>
        get() = nodeResults.values.filter { it.status == NodeStatus.RUNNING }
    
    // 노드 결과 기록
    fun recordResult(result: NodeExecutionResult) {
        nodeResults[result.nodeId] = result
    }
    
    // 특정 노드의 결과 조회
    fun getResult(nodeId: String): NodeExecutionResult? = nodeResults[nodeId]
    
    // 노드의 의존성 체크 (부모 노드가 성공했는지)
    fun canExecute(parentNodeId: String?): Boolean {
        if (parentNodeId == null) return true
        val parentResult = nodeResults[parentNodeId]
        return parentResult?.status == NodeStatus.SUCCESS
    }
    
    // 실패한 노드의 재시도 시작점 찾기
    fun findRetryStartPoint(failedNodeId: String): String? {
        val failedNode = nodeResults[failedNodeId] ?: return null
        return failedNode.parentNodeId
    }
}
