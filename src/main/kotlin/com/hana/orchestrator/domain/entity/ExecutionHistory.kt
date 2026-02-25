package com.hana.orchestrator.domain.entity

import com.hana.orchestrator.presentation.model.execution.ExecutionTreeResponse
import kotlinx.serialization.Serializable

/**
 * 실행 이력
 * SRP: 실행 이력 데이터와 상태 결정만 담당
 * 불변성: data class로 불변 객체 보장
 */
@Serializable
data class ExecutionHistory(
    val id: String,
    val query: String,
    val result: ExecutionResult,
    val startTime: Long,
    val endTime: Long? = null,
    val status: ExecutionStatus,
    val logs: MutableList<String> = mutableListOf(),
    val executionTree: ExecutionTreeResponse? = null   // ReAct 미니트리 체인 (직렬화 가능)
) {
    companion object {
        /**
         * 실행 시작 이력 생성
         */
        fun createRunning(id: String, query: String, startTime: Long = System.currentTimeMillis()): ExecutionHistory {
            return ExecutionHistory(
                id = id,
                query = query,
                result = ExecutionResult(result = ""),
                startTime = startTime,
                status = ExecutionStatus.RUNNING,
                logs = mutableListOf()
            )
        }
        
        /**
         * 실행 완료 이력 생성
         * 상태는 ExecutionResult의 context를 기반으로 자동 결정
         */
        fun createCompleted(
            id: String,
            query: String,
            result: ExecutionResult,
            startTime: Long,
            endTime: Long = System.currentTimeMillis(),
            logs: MutableList<String> = mutableListOf(),
            executionTree: ExecutionTreeResponse? = null
        ): ExecutionHistory {
            val status = determineStatusFromResult(result)
            return ExecutionHistory(
                id = id,
                query = query,
                result = result,
                startTime = startTime,
                endTime = endTime,
                status = status,
                logs = logs,
                executionTree = executionTree
            )
        }
        
        /**
         * 실행 실패 이력 생성
         */
        fun createFailed(
            id: String,
            query: String,
            error: String?,
            startTime: Long,
            endTime: Long = System.currentTimeMillis(),
            logs: MutableList<String> = mutableListOf()
        ): ExecutionHistory {
            return ExecutionHistory(
                id = id,
                query = query,
                result = ExecutionResult(result = "", error = error),
                startTime = startTime,
                endTime = endTime,
                status = ExecutionStatus.FAILED,
                logs = logs
            )
        }

        /**
         * 재시도 예정 이력 생성 (UI에 "재시도 중" 표시용, FAILED로 끝난 게 아님)
         */
        fun createRetrying(
            id: String,
            query: String,
            error: String?,
            startTime: Long,
            attemptNumber: Int,
            logs: MutableList<String> = mutableListOf()
        ): ExecutionHistory {
            val retryingLogs = logs.toMutableList().apply { add("🔄 재시도 #$attemptNumber 준비 중...") }
            return ExecutionHistory(
                id = id,
                query = query,
                result = ExecutionResult(result = "", error = error),
                startTime = startTime,
                endTime = null,
                status = ExecutionStatus.RETRYING,
                logs = retryingLogs
            )
        }
        
        /**
         * ExecutionResult를 기반으로 상태 결정
         * SRP: 상태 결정 로직을 별도 메서드로 분리
         */
        private fun determineStatusFromResult(result: ExecutionResult): ExecutionStatus {
            val hasFailedNodes = result.context?.failedNodes?.isNotEmpty() == true
            return if (hasFailedNodes) ExecutionStatus.FAILED else ExecutionStatus.COMPLETED
        }
    }
    
    /**
     * 실행 시간 계산 (밀리초)
     */
    val duration: Long?
        get() = endTime?.let { it - startTime }
    
    /**
     * 실행 중인지 확인
     */
    val isRunning: Boolean
        get() = status == ExecutionStatus.RUNNING
    
    /**
     * 실행 완료 여부 확인
     */
    val isCompleted: Boolean
        get() = status == ExecutionStatus.COMPLETED
    
    /**
     * 실행 실패 여부 확인
     */
    val isFailed: Boolean
        get() = status == ExecutionStatus.FAILED

    /**
     * 재시도 중 여부 확인
     */
    val isRetrying: Boolean
        get() = status == ExecutionStatus.RETRYING
    
    /**
     * 성공적으로 완료되었는지 확인 (실패가 아닌 완료)
     */
    val isSuccessfullyCompleted: Boolean
        get() = isCompleted && !isFailed
}

enum class ExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    /** 재시도 예정(아직 최종 실패 아님), UI는 계속 대기 */
    RETRYING
}
