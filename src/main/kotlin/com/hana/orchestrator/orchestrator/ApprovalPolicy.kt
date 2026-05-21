package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.layer.ApprovalKind
import com.hana.orchestrator.layer.CommonLayerInterface

/**
 * 레이어 실행 승인 정책 — AOP Around Advice 패턴
 *
 * SRP: 승인 여부 결정·요청 로직만 담당 (LayerManager에서 분리)
 * 정책 우선순위: 레이어 선언(approvalPreview.kind) > scheduledBypass > 사용자 확인
 *
 * - READ_ONLY → 항상 게이트 스킵 (LLM 설정 무관)
 * - scheduledBypass → 스케줄 작업 무인 실행 시 모든 게이트 스킵
 * - 그 외 → 사용자 승인 대기
 */
class ApprovalPolicy(private val gate: ApprovalGate?) {

    /**
     * 레이어 실행을 승인 정책으로 감싸는 Around Advice.
     * READ_ONLY 선언 레이어는 gate 없이 즉시 action() 호출.
     */
    suspend fun guard(
        layer: CommonLayerInterface,
        layerName: String,
        function: String,
        args: Map<String, Any>,
        action: suspend () -> String
    ): String {
        if (gate == null || gate.scheduledBypass) return action()

        val preview = layer.approvalPreview(function, args)
        if (preview.kind == ApprovalKind.READ_ONLY) return action()

        val displayPath = if (preview.path == function) "$layerName.$function" else preview.path
        val approved = gate.requestApproval(
            path = displayPath,
            oldContent = preview.oldContent,
            newContent = preview.newContent,
            kind = preview.kind
        )
        return if (approved) action()
        else "REJECTED: 사용자가 실행을 거절했습니다: $layerName.$function"
    }
}
