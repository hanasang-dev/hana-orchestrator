package com.hana.orchestrator.presentation.model.chat

import com.hana.orchestrator.presentation.model.execution.ExecutionTreeResponse
import kotlinx.serialization.Serializable

@Serializable
data class ChatResponse(
    val results: List<String>,
    val tree: ExecutionTreeResponse? = null  // ReAct 히스토리 기반 트리 (사용자가 명시적으로 저장 가능)
)
