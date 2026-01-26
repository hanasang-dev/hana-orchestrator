package com.hana.orchestrator.llm

import com.hana.orchestrator.domain.entity.ExecutionTree

/**
 * 폴백 트리 팩토리
 * SRP: 폴백 트리 생성만 담당
 */
internal class FallbackTreeFactory {
    /**
     * 폴백 트리 생성 (LLM 실패 시)
     */
    fun createFallbackTree(
        userQuery: String,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): ExecutionTree {
        val firstLayer = layerDescriptions.firstOrNull()
        return if (firstLayer != null) {
            createTreeFromLayer(firstLayer, userQuery, "fallback_root")
        } else {
            createUnknownTree(userQuery)
        }
    }
    
    /**
     * 레이어로부터 트리 생성
     */
    private fun createTreeFromLayer(
        layer: com.hana.orchestrator.layer.LayerDescription,
        userQuery: String,
        nodeId: String
    ): ExecutionTree {
        return ExecutionTree(
            rootNode = com.hana.orchestrator.domain.entity.ExecutionNode(
                layerName = layer.name,
                function = layer.functions.firstOrNull() ?: "execute",
                args = mapOf("query" to userQuery),
                children = emptyList(),
                parallel = false,
                id = nodeId
            )
        )
    }
    
    /**
     * 알 수 없는 레이어용 트리 생성
     */
    private fun createUnknownTree(userQuery: String): ExecutionTree {
        return ExecutionTree(
            rootNode = com.hana.orchestrator.domain.entity.ExecutionNode(
                layerName = "unknown",
                function = "execute",
                args = mapOf("query" to userQuery),
                children = emptyList(),
                parallel = false,
                id = "fallback_unknown"
            )
        )
    }
}
