package com.hana.orchestrator.domain.entity

/**
 * 오케스트레이션 실행 결과
 * 현재는 result만 사용하지만, 나중에 확장 가능
 */
data class ExecutionResult(
    val result: String,
    val executionTree: ExecutionTree? = null,
    val context: ExecutionContext? = null
)
