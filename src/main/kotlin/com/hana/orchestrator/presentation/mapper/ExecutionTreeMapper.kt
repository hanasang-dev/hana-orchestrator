package com.hana.orchestrator.presentation.mapper

import com.hana.orchestrator.domain.entity.ExecutionNode
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.presentation.model.execution.ExecutionTreeResponse
import com.hana.orchestrator.presentation.model.execution.ExecutionTreeNodeResponse

/**
 * ExecutionTree → Presentation Model 변환
 * SRP: 트리 변환만 담당
 */
object ExecutionTreeMapper {
    /**
     * ExecutionTree → ExecutionTreeResponse 변환
     */
    fun ExecutionTree.toResponse(): ExecutionTreeResponse {
        return ExecutionTreeResponse(
            rootNodes = rootNodes.map { it.toResponse() },
            name = name
        )
    }

    /**
     * ExecutionTreeResponse → ExecutionTree 변환 (사용자 수정 트리 역직렬화)
     */
    fun ExecutionTreeResponse.toDomain(): ExecutionTree {
        return ExecutionTree(
            rootNodes = rootNodes.map { it.toDomain() },
            name = name
        )
    }

    /**
     * ExecutionNode → ExecutionTreeNodeResponse 변환
     */
    private fun ExecutionNode.toResponse(): ExecutionTreeNodeResponse {
        return ExecutionTreeNodeResponse(
            layerName = layerName,
            function = function,
            args = args.mapValues { it.value.toString() },
            children = children.map { it.toResponse() },
            parallel = parallel,
            id = id
        )
    }

    /**
     * ExecutionTreeNodeResponse → ExecutionNode 변환
     */
    private fun ExecutionTreeNodeResponse.toDomain(): ExecutionNode {
        return ExecutionNode(
            layerName = layerName,
            function = function,
            args = args,
            children = children.map { it.toDomain() },
            parallel = parallel,
            id = id
        )
    }
}
