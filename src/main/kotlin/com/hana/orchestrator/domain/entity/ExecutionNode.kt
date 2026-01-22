package com.hana.orchestrator.domain.entity

data class ExecutionNode(
    val layerName: String,
    val function: String,
    val args: Map<String, Any>,
    val children: List<ExecutionNode> = emptyList(),
    val parallel: Boolean = false,
    val id: String  // 트리에서 고유 식별자 (트리 생성 시점에 부여)
)
