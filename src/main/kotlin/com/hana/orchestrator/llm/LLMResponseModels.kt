package com.hana.orchestrator.llm

import com.hana.orchestrator.data.model.response.ExecutionTreeResponse as LLMTreeResponse
import com.hana.orchestrator.presentation.model.execution.ExecutionTreeResponse as PresentationTreeResponse
import kotlinx.serialization.Serializable

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
    val result: String,
    val successfulFunctions: List<String> = emptyList()
)

/**
 * LLM이 결정한 다음 ReAct 액션
 * action == "execute_tree" : tree (미니트리) 실행 — TreeExecutor 위임
 * action == "finish"       : result 사용 (최종 답변)
 * action == "ask"          : 사용자에게 추가 정보 요청 — question 필드 사용
 */
@Serializable
data class ReActDecision(
    val action: String,                        // "execute_tree" | "finish" | "ask"
    val tree: LLMTreeResponse? = null,         // execute_tree 전용 미니트리 (LLM JSON 파싱용)
    val result: String = "",                   // finish 전용 최종 결과
    val reasoning: String = "",
    val question: String = ""                  // ask 전용: 사용자에게 할 질문
)
