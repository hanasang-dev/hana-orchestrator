package com.hana.orchestrator.presentation.mapper

import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.presentation.mapper.ExecutionTreeMapper.toResponse
import com.hana.orchestrator.presentation.model.execution.ExecutionHistoryResponse
import com.hana.orchestrator.presentation.model.execution.ExecutionState
import com.hana.orchestrator.presentation.model.execution.ExecutionTreeResponse
import com.hana.orchestrator.presentation.model.execution.NodeResultState

/**
 * ExecutionHistory → Presentation Model 변환
 * SRP: ExecutionHistory 변환만 담당 (트리 변환은 ExecutionTreeMapper에 위임)
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
        // ReAct: executionTree 직접 저장 (직렬화됨)
        // Tree 모드(레거시): result.executionTree @Transient fallback
        val tree = this.executionTree ?: this.result.executionTree?.toResponse()
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
            logs = state.logs,
            executionTree = tree
        )
    }
    
    /**
     * 공통 변환 로직 추출
     * DRY: 중복 코드 제거
     */
    private fun createExecutionState(history: ExecutionHistory): ExecutionState {
        val context = history.result.context
        val tree = history.executionTree ?: history.result.executionTree?.toResponse()
        val nodeResults = context?.getAllResults()?.mapValues { (_, nr) ->
            NodeResultState(
                nodeId = nr.nodeId,
                status = nr.status.name,
                result = nr.result,
                error = nr.error
            )
        } ?: emptyMap()
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
            logs = history.logs.toList(),
            executionTree = tree,
            nodeResults = nodeResults
        )
    }
}
