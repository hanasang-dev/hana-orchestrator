package com.hana.orchestrator.domain.entity

/**
 * 실행 트리 - 다중 루트 노드를 지원
 * 병렬 실행이 필요한 경우 여러 독립적인 작업을 각각 루트로 표현
 */
data class ExecutionTree(
    val rootNodes: List<ExecutionNode>,
    val name: String = "execution_plan"
) {
    /**
     * 단일 루트 노드 생성 (기존 코드 호환성)
     */
    constructor(rootNode: ExecutionNode, name: String = "execution_plan") : this(
        rootNodes = listOf(rootNode),
        name = name
    )
    
    /**
     * 첫 번째 루트 노드 (기존 코드 호환성)
     */
    val rootNode: ExecutionNode
        get() = rootNodes.firstOrNull() 
            ?: throw IllegalStateException("ExecutionTree must have at least one root node")
    
    /**
     * 루트 노드가 하나인지 확인
     */
    val isSingleRoot: Boolean
        get() = rootNodes.size == 1
}
