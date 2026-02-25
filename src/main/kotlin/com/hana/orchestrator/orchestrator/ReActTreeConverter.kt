package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.llm.ReActStep
import com.hana.orchestrator.presentation.model.execution.ExecutionTreeResponse

/**
 * ReAct 스텝 히스토리 → ExecutionTreeResponse 변환
 * SRP: 변환 로직만 담당
 *
 * 각 스텝의 미니트리(rootNodes)를 순서대로 펼쳐서 하나의 평탄 트리로 합침.
 * 기존 선형 체인 방식에서 단순 병합으로 변경 (미니트리가 이미 병렬/순차 구조를 포함).
 */
internal object ReActTreeConverter {

    fun convert(steps: List<ReActStep>): ExecutionTreeResponse? {
        val allRootNodes = steps.mapNotNull { it.tree }.flatMap { it.rootNodes }
        if (allRootNodes.isEmpty()) return null
        return ExecutionTreeResponse(rootNodes = allRootNodes)
    }
}
