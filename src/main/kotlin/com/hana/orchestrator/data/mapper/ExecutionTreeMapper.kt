package com.hana.orchestrator.data.mapper

import com.hana.orchestrator.data.model.response.ExecutionTreeResponse
import com.hana.orchestrator.data.model.response.ExecutionNodeResponse
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Data Response → Domain Entity 변환
 * Data 레이어의 책임: 외부 Remote API 구조를 Domain Entity로 변환
 */
object ExecutionTreeMapper {
    fun toExecutionTree(response: ExecutionTreeResponse): ExecutionTree {
        val rootNodes = response.getActualRootNodes().map { rootNodeResponse ->
            toExecutionNode(rootNodeResponse, parentPath = "")
        }
        return ExecutionTree(rootNodes = rootNodes)
    }
    
    private fun toExecutionNode(response: ExecutionNodeResponse, parentPath: String = ""): ExecutionNode {
        val currentPath = if (parentPath.isEmpty()) response.layerName else "$parentPath/${response.layerName}"
        val nodeId = "node_${currentPath.replace("/", "_")}_${response.function}"
        
        return ExecutionNode(
            layerName = response.layerName,
            function = response.function,
            args = (response.args as? JsonObject)?.mapValues { jsonElementToAny(it.value) } ?: emptyMap(),
            children = response.children.mapIndexed { index, child -> 
                toExecutionNode(child, "$currentPath[$index]")
            },
            parallel = response.parallel,
            id = nodeId
        )
    }
    
    /** LLM이 args 값을 문자열·배열·숫자 등으로 보낼 수 있으므로 JsonElement → Any 변환 */
    private fun jsonElementToAny(e: JsonElement): Any = when (e) {
        is JsonPrimitive -> e.content
        is JsonArray -> e.map { jsonElementToAny(it) }
        is JsonObject -> e.mapValues { jsonElementToAny(it.value) }
        else -> e.toString()
    }
}
