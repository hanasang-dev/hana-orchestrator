package com.hana.orchestrator.llm

import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.data.model.response.ExecutionTreeResponse as LLMTreeResponse
import com.hana.orchestrator.presentation.model.execution.ExecutionTreeResponse as PresentationTreeResponse
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
    val newTree: LLMTreeResponse? = null  // shouldStop=true일 때는 null 가능
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
 * tree: 이 스텝에서 실행한 미니트리 (presentation 포맷 — id 포함, UI 표시/저장용)
 */
@Serializable
data class ReActStep(
    val stepNumber: Int,
    val reasoning: String,
    val tree: PresentationTreeResponse? = null,
    val result: String
)

/**
 * LLM이 결정한 다음 ReAct 액션
 * action == "execute_tree" : tree (미니트리) 실행 — TreeExecutor 위임
 * action == "finish"       : result 사용 (최종 답변)
 */
@Serializable
data class ReActDecision(
    val action: String,                        // "execute_tree" | "finish"
    val tree: LLMTreeResponse? = null,         // execute_tree 전용 미니트리 (LLM JSON 파싱용)
    val result: String = "",                   // finish 전용 최종 결과
    val reasoning: String = ""
)
