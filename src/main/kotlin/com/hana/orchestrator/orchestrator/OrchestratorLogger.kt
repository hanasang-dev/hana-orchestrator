package com.hana.orchestrator.orchestrator

import org.slf4j.LoggerFactory
import org.slf4j.Logger
import com.hana.orchestrator.orchestrator.ExecutionHistoryManager

/**
 * 오케스트레이터 통합 로깅 헬퍼
 * Logger와 ExecutionHistoryManager를 통합하여 중복 제거
 * 3rd 파티 라이브러리 없이 SLF4J Logger 직접 사용
 */
class OrchestratorLogger(
    private val logger: Logger,
    private val historyManager: ExecutionHistoryManager?
) {
    /**
     * DEBUG 레벨 로그 (개발 환경에서만 표시)
     */
    fun debug(message: String) {
        logger.debug(message)
        // DEBUG는 UI에 표시하지 않음 (너무 많음)
    }
    
    /**
     * INFO 레벨 로그 (일반 정보)
     */
    fun info(message: String) {
        logger.info(message)
        historyManager?.addLogToCurrent(message)
    }
    
    /**
     * WARN 레벨 로그 (경고)
     */
    fun warn(message: String) {
        logger.warn(message)
        historyManager?.addLogToCurrent(message)
    }
    
    /**
     * ERROR 레벨 로그 (에러)
     */
    fun error(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            logger.error(message, throwable)
        } else {
            logger.error(message)
        }
        historyManager?.addLogToCurrent(message)
        if (throwable != null) {
            historyManager?.addLogToCurrent("   예외 타입: ${throwable::class.simpleName}")
            historyManager?.addLogToCurrent("   스택 트레이스: ${throwable.stackTraceToString().take(500)}")
        }
    }
    
    /**
     * 성능 측정 로그 (PERF)
     */
    fun perf(message: String) {
        logger.debug(message) // 성능 로그는 DEBUG 레벨
        historyManager?.addLogToCurrent(message)
    }
}

/**
 * 컴포넌트별 Logger 생성 헬퍼
 * SLF4J LoggerFactory를 직접 사용 (3rd 파티 라이브러리 없음)
 */
fun createOrchestratorLogger(
    clazz: Class<*>,
    historyManager: ExecutionHistoryManager? = null
): OrchestratorLogger {
    return OrchestratorLogger(
        logger = LoggerFactory.getLogger(clazz),
        historyManager = historyManager
    )
}
