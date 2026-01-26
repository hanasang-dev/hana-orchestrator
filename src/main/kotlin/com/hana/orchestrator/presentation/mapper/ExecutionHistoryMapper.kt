package com.hana.orchestrator.presentation.mapper

import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.presentation.controller.ExecutionState
import com.hana.orchestrator.presentation.model.execution.ExecutionHistoryResponse

/**
 * ExecutionHistory → Presentation Model 변환
 * SRP: 도메인 엔티티를 프레젠테이션 모델로 변환만 담당
 * DRY: 공통 변환 로직을 추출
 */
object ExecutionHistoryMapper {
    /**
     * ExecutionHistory → ExecutionState (WebSocket용)
     */
    fun ExecutionHistory.toExecutionState(): ExecutionState {
        return createExecutionState(this)
    }
    
    /**
     * ExecutionHistory → ExecutionHistoryResponse (HTTP용)
     */
    fun ExecutionHistory.toExecutionHistoryResponse(): ExecutionHistoryResponse {
        val state = createExecutionState(this)
        return ExecutionHistoryResponse(
            id = state.id,
            query = state.query,
            result = state.result,
            error = state.error,
            startTime = state.startTime,
            endTime = state.endTime,
            status = state.status,
            nodeCount = state.nodeCount,
            completedNodes = state.completedNodes,
            failedNodes = state.failedNodes,
            runningNodes = state.runningNodes,
            logs = state.logs
        )
    }
    
    /**
     * 공통 변환 로직 추출
     * DRY: 중복 코드 제거
     */
    private fun createExecutionState(history: ExecutionHistory): ExecutionState {
        val context = history.result.context
        return ExecutionState(
            id = history.id,
            query = history.query,
            result = history.result.result,
            error = history.result.error,
            startTime = history.startTime,
            endTime = history.endTime,
            status = history.status.name,
            nodeCount = context?.totalNodeCount ?: 0,
            completedNodes = context?.completedNodes?.size ?: 0,
            failedNodes = context?.failedNodes?.size ?: 0,
            runningNodes = context?.runningNodes?.size ?: 0,
            logs = history.logs.toList()
        )
    }
}
