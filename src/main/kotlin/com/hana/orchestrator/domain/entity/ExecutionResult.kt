package com.hana.orchestrator.domain.entity

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 오케스트레이션 실행 결과
 * SRP: 실행 결과 데이터만 담당
 * 불변성: data class로 불변 객체 보장
 */
@Serializable
data class ExecutionResult(
    val result: String,
    val error: String? = null,
    @Transient val executionTree: ExecutionTree? = null,
    @Transient val context: ExecutionContext? = null
) {
    /**
     * 실행이 성공했는지 확인
     */
    val isSuccess: Boolean
        get() = error == null && result.isNotEmpty()
    
    /**
     * 실행이 실패했는지 확인
     */
    val isFailure: Boolean
        get() = error != null
    
    /**
     * 결과가 비어있는지 확인
     */
    val isEmpty: Boolean
        get() = result.isEmpty() && error == null
}
