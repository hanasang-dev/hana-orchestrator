package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.domain.entity.ExecutionResult

/**
 * 실행 이력 관리 책임
 * SRP: 실행 이력 저장, 조회만 담당
 */
class ExecutionHistoryManager {
    private val executionHistory = mutableListOf<ExecutionHistory>()
    private var currentExecution: ExecutionHistory? = null
    
    /**
     * 현재 실행 설정
     */
    fun setCurrentExecution(history: ExecutionHistory) {
        currentExecution = history
    }
    
    /**
     * 현재 실행 조회
     */
    fun getCurrentExecution(): ExecutionHistory? {
        return currentExecution
    }
    
    /**
     * 현재 실행 초기화
     */
    fun clearCurrentExecution() {
        currentExecution = null
    }
    
    /**
     * 실행 이력 추가
     */
    fun addHistory(history: ExecutionHistory) {
        executionHistory.add(history)
    }
    
    /**
     * 실행 이력 조회
     */
    fun getExecutionHistory(limit: Int = 50): List<ExecutionHistory> {
        return executionHistory.takeLast(limit).reversed()
    }
    
    /**
     * 실행 이력 업데이트 (같은 ID의 이력을 찾아서 교체)
     */
    fun updateHistory(updatedHistory: ExecutionHistory) {
        val index = executionHistory.indexOfFirst { it.id == updatedHistory.id }
        if (index >= 0) {
            executionHistory[index] = updatedHistory
        } else {
            executionHistory.add(updatedHistory)
        }
    }
    
    /**
     * 현재 실행에 로그 추가
     */
    fun addLogToCurrent(message: String) {
        val current = currentExecution ?: return
        val timestamp = System.currentTimeMillis()
        val timeStr = java.time.Instant.ofEpochMilli(timestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        val logMessage = "[$timeStr] $message"
        current.logs.add(logMessage)
    }
    
    /**
     * 현재 실행의 로그 조회
     */
    fun getCurrentLogs(): MutableList<String> {
        return currentExecution?.logs ?: mutableListOf()
    }
}
