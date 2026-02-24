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

/**
 * LLM 직접 답변 가능 여부 확인 결과
 */
@Serializable
data class LLMDirectAnswerCapability(
    val canAnswer: Boolean,
    val reason: String = ""
)

/**
 * 사용자가 수정한 트리에 대한 LLM 검토 결과
 */
@Serializable
data class TreeReview(
    val approved: Boolean,
    val reason: String
)

/**
 * ReAct 루프의 단일 스텝 히스토리
 */
@Serializable
data class ReActStep(
    val stepNumber: Int,
    val reasoning: String,
    val layerName: String,
    val function: String,
    val args: Map<String, String> = emptyMap(),
    val calls: List<LayerCall> = emptyList(),  // call_parallel 스텝일 때 채워짐
    val result: String
)

/**
 * call_parallel 액션에서 사용하는 단일 레이어 호출 명세
 */
@Serializable
data class LayerCall(
    val layerName: String,
    val function: String,
    val args: Map<String, String> = emptyMap()
)

/**
 * LLM이 결정한 다음 ReAct 액션
 * action == "call_layer"    : layerName/function/args 사용 (단일 순차 실행)
 * action == "call_parallel" : calls 사용 (여러 레이어 동시 실행)
 * action == "finish"        : result 사용 (최종 답변)
 */
@Serializable
data class ReActDecision(
    val action: String,                        // "call_layer" | "call_parallel" | "finish"
    val layerName: String = "",                // call_layer 전용
    val function: String = "",                 // call_layer 전용
    val args: Map<String, String> = emptyMap(), // call_layer 전용
    val calls: List<LayerCall> = emptyList(),   // call_parallel 전용
    val result: String = "",                   // finish 전용 최종 결과
    val reasoning: String = ""
)
