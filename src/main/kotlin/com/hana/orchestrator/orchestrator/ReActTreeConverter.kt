package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.llm.ReActStep
import com.hana.orchestrator.presentation.model.execution.ExecutionTreeNodeResponse
import com.hana.orchestrator.presentation.model.execution.ExecutionTreeResponse

/**
 * ReAct 스텝 히스토리 → ExecutionTreeResponse 변환
 * SRP: 변환 로직만 담당
 *
 * call_parallel 스텝은 개별 호출로 순차 펼침 (V1 단순화).
 * 결과 트리는 선형 체인: node[0] → node[1] → ... → node[n]
 */
internal object ReActTreeConverter {

    fun convert(steps: List<ReActStep>): ExecutionTreeResponse {
        // 각 스텝을 개별 노드 리스트로 펼침
        val flat: List<ExecutionTreeNodeResponse> = steps.flatMap { step ->
            if (step.calls.isEmpty()) {
                listOf(node(step.layerName, step.function, step.args, "react_${step.stepNumber}_0"))
            } else {
                step.calls.mapIndexed { j, call ->
                    node(call.layerName, call.function, call.args, "react_${step.stepNumber}_$j")
                }
            }
        }

        if (flat.isEmpty()) return ExecutionTreeResponse(rootNodes = emptyList())

        // 뒤에서부터 children 체인 빌드: 마지막 노드부터 앞으로 연결
        val chain = flat.foldRight(null as ExecutionTreeNodeResponse?) { n, acc ->
            n.copy(children = if (acc != null) listOf(acc) else emptyList())
        }!!

        return ExecutionTreeResponse(rootNodes = listOf(chain))
    }

    private fun node(
        layerName: String,
        function: String,
        args: Map<String, String>,
        id: String
    ) = ExecutionTreeNodeResponse(
        layerName = layerName,
        function = function,
        args = args,
        children = emptyList(),
        parallel = false,
        id = id
    )
}
