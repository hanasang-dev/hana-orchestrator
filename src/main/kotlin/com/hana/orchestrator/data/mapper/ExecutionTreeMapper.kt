package com.hana.orchestrator.data.mapper

import com.hana.orchestrator.data.model.response.ExecutionTreeResponse
import com.hana.orchestrator.data.model.response.ExecutionNodeResponse
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionNode

/**
 * Data Response → Domain Entity 변환
 * Data 레이어의 책임: 외부 Remote API 구조를 Domain Entity로 변환
 */
object ExecutionTreeMapper {
    fun toExecutionTree(response: ExecutionTreeResponse): ExecutionTree {
        return ExecutionTree(
            rootNode = toExecutionNode(response.rootNode, parentPath = "")
        )
    }
    
    private fun toExecutionNode(response: ExecutionNodeResponse, parentPath: String = ""): ExecutionNode {
        val currentPath = if (parentPath.isEmpty()) response.layerName else "$parentPath/${response.layerName}"
        val nodeId = "node_${currentPath.replace("/", "_")}_${response.function}"
        
        return ExecutionNode(
            layerName = response.layerName,
            function = response.function,
            args = response.args.mapValues { it.value as Any },
            children = response.children.mapIndexed { index, child -> 
                toExecutionNode(child, "$currentPath[$index]")
            },
            parallel = response.parallel,
            id = nodeId
        )
    }
}
