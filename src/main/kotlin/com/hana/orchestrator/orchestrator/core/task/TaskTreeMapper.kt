package com.hana.orchestrator.orchestrator.core.task

import com.hana.orchestrator.data.model.response.ExecutionNodeResponse
import com.hana.orchestrator.data.model.response.ExecutionTreeResponse
import com.hana.orchestrator.domain.entity.ExecutionNode
import com.hana.orchestrator.domain.entity.ExecutionTree
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Task ↔ ExecutionTree 변환.
 *
 * HTN B3 의 어댑터 계층:
 * - TreeExecutor 가 ExecutionNode/Tree 기반으로 동작하는 동안
 * - 신규 Task 진입점에서 받은 객체를 ExecutionTree 로 안전 변환
 *
 * 매핑 규칙:
 * - [PrimitiveTask] (단독) → ExecutionTree(rootNodes = 1 노드)
 * - [CompoundTask] → subtasks 를 평탄화하여 rootNodes 구성
 *     - subtask 가 [PrimitiveTask] 이면 ExecutionNode 로 직접 변환
 *     - subtask 가 [CompoundTask] 이면 재귀 — 자식의 모든 leaf 를 부모 트리에 평탄화
 *       (subgoal 분리 실행 = 미래 H PR 의 pursue 메타액션 영역)
 *
 * subtasks 가 비어있으면 ExecutionTree 도 비어있음 — caller 가 분기 처리해야 함.
 */
object TaskTreeMapper {

    fun toExecutionTree(task: Task, name: String = "execution_plan"): ExecutionTree {
        val rootNodes = when (task) {
            is PrimitiveTask -> listOf(toNode(task, idPrefix = "node"))
            is CompoundTask -> task.subtasks.flatMapIndexed { idx, sub ->
                when (sub) {
                    is PrimitiveTask -> listOf(toNode(sub, idPrefix = "node_${idx}"))
                    is CompoundTask -> flattenCompound(sub, idPrefix = "node_${idx}")
                }
            }
        }
        return ExecutionTree(rootNodes = rootNodes, name = name)
    }

    /**
     * CompoundTask 의 자식을 부모 트리에 평탄화.
     * 진짜 subgoal 분리(별 ReAct 루프)는 H PR. 지금은 PrimitiveTask leaf 만 추출.
     */
    private fun flattenCompound(task: CompoundTask, idPrefix: String): List<ExecutionNode> =
        task.subtasks.flatMapIndexed { idx, sub ->
            when (sub) {
                is PrimitiveTask -> listOf(toNode(sub, "${idPrefix}_${idx}"))
                is CompoundTask -> flattenCompound(sub, "${idPrefix}_${idx}")
            }
        }

    /** PrimitiveTask → ExecutionNode 재귀 변환 (children 보존 — 데이터 흐름) */
    private fun toNode(call: PrimitiveTask, idPrefix: String): ExecutionNode = ExecutionNode(
        layerName = call.layerName,
        function = call.function,
        args = call.args,
        children = call.children.mapIndexed { i, child ->
            toNode(child, "${idPrefix}[${i}]")
        },
        parallel = call.parallel,
        id = "${idPrefix}_${call.layerName}_${call.function}"
    )

    /**
     * 역방향 — 기존 ExecutionTree 를 CompoundTask 로 wrap.
     * 각 rootNode → PrimitiveTask (children 재귀 보존). 데이터 흐름 그대로 유지.
     */
    fun fromExecutionTree(tree: ExecutionTree, query: String, description: String = "wrapped tree"): CompoundTask {
        val subtasks: List<Task> = tree.rootNodes.map { fromNode(it) }
        return CompoundTask(description = description, query = query, subtasks = subtasks)
    }

    private fun fromNode(node: ExecutionNode): PrimitiveTask = PrimitiveTask(
        description = "${node.layerName}.${node.function}",
        layerName = node.layerName,
        function = node.function,
        args = node.args,
        children = node.children.map { fromNode(it) },
        parallel = node.parallel
    )

    /**
     * LLM 응답 → CompoundTask 직접 변환 (B4).
     *
     * data layer 의 LLM JSON → 도메인 변환 책임을 Task 추상으로 흡수.
     * DefaultReActStrategy 가 LLM 응답 받으면 바로 Task 사용.
     *
     * 자식 노드 (children) 는 부모 PrimitiveTask 의 args 의 metadata 가 아니라
     * CompoundTask 의 subtasks 로 펴진 형태. 데이터 흐름 (`{{parent}}`) 은 args 안 placeholder 그대로.
     */
    fun fromLLMResponse(response: ExecutionTreeResponse, query: String, description: String = "llm-plan"): CompoundTask {
        val subtasks = response.getActualRootNodes().map { toPrimitiveTask(it, parentPath = "") }
        return CompoundTask(description = description, query = query, subtasks = subtasks)
    }

    private fun toPrimitiveTask(response: ExecutionNodeResponse, parentPath: String): PrimitiveTask {
        val currentPath = if (parentPath.isEmpty()) response.layerName else "$parentPath/${response.layerName}"
        return PrimitiveTask(
            description = "${response.layerName}.${response.function}",
            layerName = response.layerName,
            function = response.function,
            args = (response.args as? JsonObject)?.mapValues { jsonElementToAny(it.value) } ?: emptyMap(),
            children = response.children.mapIndexed { idx, child ->
                toPrimitiveTask(child, "$currentPath[$idx]")
            },
            parallel = response.parallel,
            verifier = DefaultVerifierRegistry.pick(response.layerName, response.function)
        )
    }

    private fun jsonElementToAny(e: JsonElement): Any = when (e) {
        is JsonPrimitive -> e.content
        is JsonArray -> e.map { jsonElementToAny(it) }
        is JsonObject -> e.mapValues { jsonElementToAny(it.value) }
        else -> e.toString()
    }
}
