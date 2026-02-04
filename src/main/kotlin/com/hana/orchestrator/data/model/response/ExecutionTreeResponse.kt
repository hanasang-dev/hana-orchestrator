package com.hana.orchestrator.data.model.response

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * LLM API 응답 모델 (Remote API)
 * 외부 API 구조를 그대로 반영
 * 다중 루트 노드를 지원: rootNodes 배열 또는 단일 rootNode (호환성)
 */
@Serializable
data class ExecutionTreeResponse(
    val rootNodes: List<ExecutionNodeResponse>? = null,  // 다중 루트 (우선)
    val rootNode: ExecutionNodeResponse? = null  // 단일 루트 (호환성)
) {
    init {
        require(rootNodes != null || rootNode != null) {
            "ExecutionTreeResponse must have either rootNodes or rootNode"
        }
    }
    
    /**
     * 실제 루트 노드 목록 반환 (호환성 처리)
     */
    fun getActualRootNodes(): List<ExecutionNodeResponse> {
        return rootNodes ?: (rootNode?.let { listOf(it) } ?: emptyList())
    }
}

@Serializable
data class ExecutionNodeResponse(
    val layerName: String,
    val function: String,
    /** LLM이 문자열·배열·숫자 등 혼합으로 보낼 수 있으므로 JsonElement로 받고, 매퍼에서 Map<String, Any>로 변환 */
    val args: JsonElement = buildJsonObject { },
    val children: List<ExecutionNodeResponse> = emptyList(),
    val parallel: Boolean = false
)
