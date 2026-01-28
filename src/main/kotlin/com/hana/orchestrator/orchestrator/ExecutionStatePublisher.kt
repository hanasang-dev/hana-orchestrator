package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionContext
import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.domain.entity.NodeExecutionResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * 실행 상태 발행 책임
 * SRP: Flow 업데이트 및 상태 발행만 담당
 */
class ExecutionStatePublisher {
    // 실시간 업데이트를 위한 Flow
    private val _executionUpdates = MutableSharedFlow<ExecutionHistory>(replay = 1, extraBufferCapacity = 10)
    val executionUpdates: SharedFlow<ExecutionHistory> = _executionUpdates.asSharedFlow()
    
    /**
     * 실행 상태 업데이트를 Flow에 emit
     */
    suspend fun emitExecutionUpdate(history: ExecutionHistory) {
        _executionUpdates.emit(history)
    }
    
    /**
     * 비동기로 실행 상태 업데이트 (로깅 등에서 사용)
     */
    fun emitExecutionUpdateAsync(history: ExecutionHistory) {
        CoroutineScope(Dispatchers.Default).launch {
            _executionUpdates.emit(history)
        }
    }
    
    /**
     * 현재 실행 상태를 컨텍스트 정보로 업데이트
     */
    suspend fun updateCurrentExecutionWithContext(
        currentExecution: ExecutionHistory,
        context: ExecutionContext,
        tree: ExecutionTree,
        nodeResult: NodeExecutionResult
    ): ExecutionHistory {
        val updatedHistory = currentExecution.copy(
            result = ExecutionResult(
                result = nodeResult.resultText,
                executionTree = tree,
                context = context
            )
        )
        emitExecutionUpdate(updatedHistory)
        return updatedHistory
    }
}
