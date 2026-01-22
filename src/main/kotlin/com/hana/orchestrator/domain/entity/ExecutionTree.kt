package com.hana.orchestrator.domain.entity

data class ExecutionTree(
    val rootNode: ExecutionNode,
    val name: String = "execution_plan"
)
