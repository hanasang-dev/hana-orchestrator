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
    val executionTree: ExecutionTreeResponse? = null,
    val nodeResults: Map<String, NodeResultState> = emptyMap()
)

@Serializable
data class ExecutionUpdateMessage(
    val history: List<ExecutionState>,
    val current: ExecutionState? = null
)

/**
 * 실시간 진행 상태 표시용 모델
 */
@Serializable
data class ProgressUpdate(
    val executionId: String,
    val phase: ExecutionPhase,
    val message: String,
    val progress: Int = 0,  // 0-100
    val elapsedMs: Long = 0
)

@Serializable
enum class ExecutionPhase {
    STARTING,           // 시작
    TREE_VALIDATION,    // 트리 검증
    TREE_EXECUTION,     // 트리 실행
    COMPLETED,          // 완료
    FAILED,             // 실패
    CANCELLED           // 사용자 취소
}
