package com.hana.orchestrator.domain.entity

import kotlinx.serialization.Serializable

/**
 * 노드 실행 결과 요약 — 실행 이력과 함께 persist되는 경량 모델
 * ExecutionContext(Transient)와 달리 직렬화·저장 가능
 */
@Serializable
data class NodeResultSummary(
    val layerName: String,
    val function: String,
    val status: String,       // NodeStatus.name: "SUCCESS" | "FAILED" | "SKIPPED"
    val error: String? = null
)
