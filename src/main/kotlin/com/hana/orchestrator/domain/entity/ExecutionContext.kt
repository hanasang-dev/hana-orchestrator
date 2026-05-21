package com.hana.orchestrator.domain.entity

import kotlinx.coroutines.CompletableDeferred

/**
 * 실행 컨텍스트 - 전체 실행 상태 추적
 * SRP: 노드 실행 상태 추적 및 조회만 담당
 *
 * OOP 원칙:
 * - 캡슐화: nodeResults는 private으로 보호
 * - SRP: 상태 추적과 조회만 담당
 * - DRY: 캐싱 로직을 공통화
 */
class ExecutionContext {
    private val nodeResults = mutableMapOf<String, NodeExecutionResult>()

    /** {{nodeId:X}} 대기 지원 — 노드 완료 시 결과 전달 */
    private val nodeDeferreds = mutableMapOf<String, CompletableDeferred<String>>()

    /** 실행 전 모든 노드 ID 등록. {{nodeId:X}} 참조 노드가 완료될 때까지 대기 가능 */
    fun registerNodes(nodeIds: Iterable<String>) {
        nodeIds.forEach { id -> nodeDeferreds.getOrPut(id) { CompletableDeferred() } }
    }

    /** 노드 완료(성공/실패/스킵) 시 호출 — 대기 중인 참조자에게 결과 전달 */
    fun completeNodeDeferred(nodeId: String, result: String) {
        nodeDeferreds[nodeId]?.complete(result)
    }

    /** {{nodeId:X}} 치환 시 X가 완료될 때까지 코루틴 대기. 미등록 ID면 즉시 빈 문자열 반환 */
    suspend fun awaitNodeResult(nodeId: String): String =
        nodeDeferreds[nodeId]?.await() ?: ""
    
    // 캐싱된 계산 프로퍼티들
    private var _completedNodes: List<NodeExecutionResult>? = null
    private var _failedNodes: List<NodeExecutionResult>? = null
    private var _runningNodes: List<NodeExecutionResult>? = null
    
    /**
     * 완료된 노드 (성공) - 캐싱으로 성능 최적화
     */
    val completedNodes: List<NodeExecutionResult>
        get() = getCachedOrCompute(
            NodeStatus.SUCCESS,
            getCache = { _completedNodes },
            setCache = { _completedNodes = it }
        )
    
    /**
     * 실패한 노드 - 캐싱으로 성능 최적화
     */
    val failedNodes: List<NodeExecutionResult>
        get() = getCachedOrCompute(
            NodeStatus.FAILED,
            getCache = { _failedNodes },
            setCache = { _failedNodes = it }
        )
    
    /**
     * 실행 중인 노드 - 캐싱으로 성능 최적화
     */
    val runningNodes: List<NodeExecutionResult>
        get() = getCachedOrCompute(
            NodeStatus.RUNNING,
            getCache = { _runningNodes },
            setCache = { _runningNodes = it }
        )
    
    /**
     * 캐시된 값이 있으면 반환, 없으면 계산 후 캐싱
     * DRY: 반복되는 캐싱 로직을 공통화
     */
    private fun getCachedOrCompute(
        status: NodeStatus,
        getCache: () -> List<NodeExecutionResult>?,
        setCache: (List<NodeExecutionResult>) -> Unit
    ): List<NodeExecutionResult> {
        val cached = getCache()
        return if (cached != null) {
            cached
        } else {
            val computed = nodeResults.values.filter { it.status == status }
            setCache(computed)
            computed
        }
    }
    
    /**
     * 노드 결과 기록 (캐시 무효화)
     */
    fun recordResult(result: NodeExecutionResult) {
        nodeResults[result.nodeId] = result
        invalidateCache()
    }
    
    /**
     * 노드 실행 결과를 생성하고 자동 기록
     * SRP: 결과 생성과 기록을 분리하지 않고 편의 메서드로 제공
     */
    fun recordNode(
        node: ExecutionNode,
        status: NodeStatus,
        depth: Int = 0,
        parentNodeId: String? = null,
        result: String? = null,
        error: String? = null
    ): NodeExecutionResult {
        val nodeResult = NodeExecutionResult.fromNode(node, status, depth, parentNodeId, result, error)
        recordResult(nodeResult)
        return nodeResult
    }
    
    /**
     * 캐시 무효화 (상태 변경 시 호출)
     */
    private fun invalidateCache() {
        _completedNodes = null
        _failedNodes = null
        _runningNodes = null
    }
    
    /**
     * 특정 노드의 결과 조회
     */
    fun getResult(nodeId: String): NodeExecutionResult? = nodeResults[nodeId]
    
    /**
     * 모든 노드 결과 조회 (읽기 전용)
     * 캡슐화: 내부 상태를 보호하면서 읽기 전용 접근 제공
     */
    fun getAllResults(): Map<String, NodeExecutionResult> = nodeResults.toMap()
    
    /**
     * 노드의 의존성 체크 (부모 노드가 성공했는지)
     */
    fun canExecute(parentNodeId: String?): Boolean {
        if (parentNodeId == null) return true
        val parentResult = getResult(parentNodeId)
        return parentResult?.isSuccess == true
    }
    
    /**
     * 실패한 노드의 재시도 시작점 찾기
     */
    fun findRetryStartPoint(failedNodeId: String): String? {
        val failedNode = getResult(failedNodeId) ?: return null
        return failedNode.parentNodeId
    }
    
    /**
     * 특정 상태의 노드 개수 조회
     */
    fun countByStatus(status: NodeStatus): Int {
        return nodeResults.values.count { it.status == status }
    }
    
    /**
     * 전체 노드 개수 조회
     */
    val totalNodeCount: Int
        get() = nodeResults.size
}
