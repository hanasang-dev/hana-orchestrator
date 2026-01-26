package com.hana.orchestrator.domain.entity

/**
 * 노드 실행 결과 추적
 * SRP: 노드 실행 결과 데이터만 담당
 * 불변성: data class로 불변 객체 보장
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
) {
    companion object {
        /**
         * ExecutionNode로부터 기본 정보를 가진 결과 생성 헬퍼
         */
        fun fromNode(
            node: ExecutionNode,
            status: NodeStatus,
            depth: Int = 0,
            parentNodeId: String? = null,
            result: String? = null,
            error: String? = null
        ): NodeExecutionResult {
            return NodeExecutionResult(
                nodeId = node.id,
                node = node,
                status = status,
                result = result,
                error = error,
                depth = depth,
                parentNodeId = parentNodeId
            )
        }
    }
    
    /**
     * 결과 텍스트 추출 (result 우선, 없으면 error)
     * SRP: 결과 추출 로직을 엔티티에 포함
     */
    val resultText: String
        get() = when {
            !result.isNullOrEmpty() -> result
            !error.isNullOrEmpty() -> error
            else -> ""
        }
    
    /**
     * 성공했는지 확인
     */
    val isSuccess: Boolean
        get() = status == NodeStatus.SUCCESS
    
    /**
     * 실패했는지 확인
     */
    val isFailure: Boolean
        get() = status == NodeStatus.FAILED
    
    /**
     * 건너뛰었는지 확인
     */
    val isSkipped: Boolean
        get() = status == NodeStatus.SKIPPED
    
    /**
     * 실행 중인지 확인
     */
    val isRunning: Boolean
        get() = status == NodeStatus.RUNNING
}
