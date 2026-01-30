package com.hana.orchestrator.llm

import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.data.model.response.ExecutionTreeResponse
import kotlinx.serialization.Serializable

/**
 * 결과 평가 응답
 */
@Serializable
data class ResultEvaluation(
    val isSatisfactory: Boolean,
    val reason: String,
    val needsRetry: Boolean
)

/**
 * 재처리 방안 응답
 * ExecutionTree는 @Transient이므로 직렬화하지 않음
 */
data class RetryStrategy(
    val shouldStop: Boolean,
    val reason: String,
    val newTree: ExecutionTree?
)

/**
 * 재처리 방안 JSON 응답 (내부용)
 */
@Serializable
internal data class RetryStrategyResponse(
    val shouldStop: Boolean,
    val reason: String = "",  // 기본값 제공 (LLM이 누락할 수 있음)
    val newTree: ExecutionTreeResponse? = null  // shouldStop=true일 때는 null 가능
)

/**
 * 실행 비교 결과
 */
@Serializable
data class ComparisonResult(
    val isSignificantlyDifferent: Boolean,
    val reason: String
)

/**
 * 요구사항 실행 가능성 검증 결과
 */
@Serializable
data class QueryFeasibility(
    val feasible: Boolean,
    val reason: String = "",  // 기본값 제공 (LLM이 null이나 누락할 수 있음)
    val suggestion: String? = null
)
