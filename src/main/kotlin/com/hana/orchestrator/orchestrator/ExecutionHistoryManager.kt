package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.domain.entity.ExecutionResult

/**
 * 실행 이력 관리 책임
 * SRP: 실행 이력 저장, 조회만 담당
 */
class ExecutionHistoryManager(
    private val historyRepository: HistoryRepository = HistoryRepository()
) {
    private val executionHistory = historyRepository.loadRecent().toMutableList()
    /** executionId → 진행 중인 ExecutionHistory (동시 다중 실행 지원) */
    private val currentExecutions = java.util.concurrent.ConcurrentHashMap<String, ExecutionHistory>()

    /**
     * 현재 실행 설정 (executionId는 history.id에서 추출)
     */
    fun setCurrentExecution(history: ExecutionHistory) {
        currentExecutions[history.id] = history
    }

    /**
     * 현재 실행 조회 — executionId 지정 시 해당 실행, 없으면 임의의 진행 중 실행 반환 (WebSocket 호환)
     */
    fun getCurrentExecution(executionId: String? = null): ExecutionHistory? {
        return if (executionId != null) currentExecutions[executionId]
        else currentExecutions.values.firstOrNull()
    }

    /**
     * 현재 실행 초기화
     */
    fun clearCurrentExecution(executionId: String? = null) {
        if (executionId != null) currentExecutions.remove(executionId)
        else currentExecutions.clear()
    }
    
    /**
     * 실행 이력 추가 (완료/실패 이력은 파일로도 저장)
     */
    fun addHistory(history: ExecutionHistory) {
        executionHistory.add(history)
        if (!history.isRunning && !history.isRetrying) {
            historyRepository.save(history)
        }
    }
    
    /**
     * 실행 이력 조회
     */
    fun getExecutionHistory(limit: Int = 50): List<ExecutionHistory> {
        return executionHistory.takeLast(limit).reversed()
    }
    
    /**
     * 실행 이력 삭제 (메모리 + 파일)
     */
    fun deleteHistory(id: String): Boolean {
        val removed = executionHistory.removeIf { it.id == id }
        historyRepository.delete(id)
        return removed
    }

    /**
     * 실행 이력 업데이트 (같은 ID의 이력을 찾아서 교체, 완료/실패 시 파일 저장)
     */
    fun updateHistory(updatedHistory: ExecutionHistory) {
        val index = executionHistory.indexOfFirst { it.id == updatedHistory.id }
        if (index >= 0) {
            executionHistory[index] = updatedHistory
        } else {
            executionHistory.add(updatedHistory)
        }
        if (!updatedHistory.isRunning && !updatedHistory.isRetrying) {
            historyRepository.save(updatedHistory)
        }
    }
    
    /**
     * 실행 로그 추가 (executionId 지정)
     */
    fun addLogTo(executionId: String, message: String) {
        val current = currentExecutions[executionId] ?: return
        val timeStr = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        current.logs.add("[$timeStr] $message")
    }

    /**
     * 현재 실행에 로그 추가 (executionId 없는 레거시 호출 — OrchestratorLogger 등)
     * 동시 실행 환경에서는 귀속 불가하므로 no-op
     */
    fun addLogToCurrent(message: String) { /* no-op: use addLogTo(executionId, message) */ }

    /**
     * 실행 로그 조회 (executionId 지정)
     */
    fun getLogs(executionId: String): MutableList<String> =
        currentExecutions[executionId]?.logs ?: mutableListOf()

    /**
     * 현재 실행의 로그 조회 (레거시 — executionId 없는 호출처 호환)
     */
    fun getCurrentLogs(): MutableList<String> =
        currentExecutions.values.firstOrNull()?.logs ?: mutableListOf()
}
