package com.hana.orchestrator.data.model.response

import kotlinx.serialization.Serializable

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
    val args: Map<String, String> = emptyMap(),
    val children: List<ExecutionNodeResponse> = emptyList(),
    val parallel: Boolean = false
)
