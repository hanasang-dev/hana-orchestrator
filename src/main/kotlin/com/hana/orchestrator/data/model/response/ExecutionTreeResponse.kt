package com.hana.orchestrator.data.model.response

import kotlinx.serialization.Serializable

/**
 * LLM API 응답 모델 (Remote API)
 * 외부 API 구조를 그대로 반영
 */
@Serializable
data class ExecutionTreeResponse(
    val rootNode: ExecutionNodeResponse
)

@Serializable
data class ExecutionNodeResponse(
    val layerName: String,
    val function: String,
    val args: Map<String, String> = emptyMap(),
    val children: List<ExecutionNodeResponse> = emptyList(),
    val parallel: Boolean = false
)
