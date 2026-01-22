package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.layer.LayerDescription
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionNode

/**
 * ExecutionTree의 유효성을 검증하고 자동으로 수정하는 클래스
 */
class ExecutionTreeValidator(
    private val availableLayers: List<LayerDescription>
) {
    private val layerMap = availableLayers.associateBy { it.name }
    
    /**
     * 트리 유효성 검증 결과
     */
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val fixedTree: ExecutionTree? = null
    )
    
    /**
     * 트리 구조를 검증하고 필요시 수정
     */
    fun validateAndFix(tree: ExecutionTree, userQuery: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // 트리 깊이 검증
        val depth = calculateTreeDepth(tree.rootNode)
        if (depth > MAX_TREE_DEPTH) {
            errors.add("트리 깊이($depth)가 최대값($MAX_TREE_DEPTH)을 초과합니다.")
        }
        
        // 순환 참조 검증
        val cycleDetected = detectCycle(tree.rootNode)
        if (cycleDetected) {
            errors.add("트리 구조에 순환 참조가 감지되었습니다.")
        }
        
        // 루트 노드 검증 및 수정
        val fixedRoot = validateAndFixNode(tree.rootNode, userQuery, errors, warnings, depth = 0)
        
        return if (errors.isEmpty()) {
            ValidationResult(
                isValid = true,
                warnings = warnings,
                fixedTree = ExecutionTree(rootNode = fixedRoot, name = tree.name)
            )
        } else {
            // 에러가 있으면 기본 트리 생성
            ValidationResult(
                isValid = false,
                errors = errors,
                warnings = warnings,
                fixedTree = createFallbackTree(userQuery)
            )
        }
    }
    
    /**
     * 트리 깊이 계산
     */
    private fun calculateTreeDepth(node: ExecutionNode, currentDepth: Int = 0): Int {
        if (node.children.isEmpty()) {
            return currentDepth
        }
        return node.children.maxOfOrNull { calculateTreeDepth(it, currentDepth + 1) } ?: currentDepth
    }
    
    /**
     * 순환 참조 감지
     */
    private fun detectCycle(node: ExecutionNode, visited: MutableSet<String> = mutableSetOf()): Boolean {
        val nodeKey = "${node.layerName}:${node.function}"
        if (visited.contains(nodeKey)) {
            return true
        }
        visited.add(nodeKey)
        
        for (child in node.children) {
            if (detectCycle(child, visited.toMutableSet())) {
                return true
            }
        }
        
        return false
    }
    
    companion object {
        private const val MAX_TREE_DEPTH = 10
    }
    
    /**
     * 노드를 재귀적으로 검증하고 수정
     */
    private fun validateAndFixNode(
        node: ExecutionNode,
        userQuery: String,
        errors: MutableList<String>,
        warnings: MutableList<String>,
        depth: Int = 0
    ): ExecutionNode {
        // 깊이 제한 체크
        if (depth > MAX_TREE_DEPTH) {
            errors.add("노드 깊이가 최대값을 초과했습니다. 자식 노드들을 제거합니다.")
            return node.copy(children = emptyList())
        }
        // 레이어명 검증
        val layerDescription = layerMap[node.layerName]
        if (layerDescription == null) {
            errors.add("레이어 '${node.layerName}'가 존재하지 않습니다. 사용 가능한 레이어: ${layerMap.keys.joinToString(", ")}")
            // 가장 유사한 레이어 찾기 (간단한 문자열 매칭)
            val similarLayer = findSimilarLayer(node.layerName)
            if (similarLayer != null) {
                warnings.add("레이어 '${node.layerName}'를 '${similarLayer.name}'로 자동 수정합니다.")
                return validateAndFixNode(
                    node.copy(layerName = similarLayer.name),
                    userQuery,
                    errors,
                    warnings
                )
            } else {
                // 유사한 레이어가 없으면 첫 번째 레이어 사용
                val fallbackLayer = availableLayers.firstOrNull()
                if (fallbackLayer != null) {
                    warnings.add("레이어 '${node.layerName}'를 기본 레이어 '${fallbackLayer.name}'로 대체합니다.")
                    return validateAndFixNode(
                        node.copy(layerName = fallbackLayer.name),
                        userQuery,
                        errors,
                        warnings
                    )
                }
            }
        }
        
        // 함수명 검증
        val layerDesc = layerMap[node.layerName] ?: return node
        if (!layerDesc.functions.contains(node.function)) {
            warnings.add("레이어 '${node.layerName}'에 함수 '${node.function}'가 없습니다. 사용 가능한 함수: ${layerDesc.functions.joinToString(", ")}")
            // 첫 번째 함수로 대체
            val fallbackFunction = layerDesc.functions.firstOrNull() ?: "execute"
            warnings.add("함수 '${node.function}'를 '${fallbackFunction}'로 자동 수정합니다.")
            return validateAndFixNode(
                node.copy(function = fallbackFunction),
                userQuery,
                errors,
                warnings
            )
        }
        
        // args에 query가 없으면 추가
        val fixedArgs = if (!node.args.containsKey("query")) {
            warnings.add("노드에 'query' 인자가 없어 자동으로 추가합니다.")
            node.args + ("query" to userQuery)
        } else {
            node.args
        }
        
        // 자식 노드들 재귀적으로 검증 및 수정
        val fixedChildren = node.children.map { child ->
            validateAndFixNode(child, userQuery, errors, warnings, depth + 1)
        }
        
        return ExecutionNode(
            layerName = node.layerName,
            function = node.function,
            args = fixedArgs,
            children = fixedChildren,
            parallel = node.parallel,
            id = node.id
        )
    }
    
    /**
     * 유사한 레이어명 찾기 (간단한 문자열 매칭)
     */
    private fun findSimilarLayer(layerName: String): LayerDescription? {
        // 정확히 일치하는 경우
        layerMap[layerName]?.let { return it }
        
        // 대소문자 무시 매칭
        layerMap.keys.firstOrNull { it.equals(layerName, ignoreCase = true) }?.let {
            return layerMap[it]
        }
        
        // 부분 문자열 매칭
        layerMap.keys.firstOrNull { 
            it.contains(layerName, ignoreCase = true) || layerName.contains(it, ignoreCase = true)
        }?.let {
            return layerMap[it]
        }
        
        return null
    }
    
    /**
     * 폴백 트리 생성 (검증 실패 시)
     */
    private fun createFallbackTree(userQuery: String): ExecutionTree {
        val firstLayer = availableLayers.firstOrNull()
        return if (firstLayer != null) {
            ExecutionTree(
                rootNode = ExecutionNode(
                    layerName = firstLayer.name,
                    function = firstLayer.functions.firstOrNull() ?: "execute",
                    args = mapOf("query" to userQuery),
                    children = emptyList(),
                    parallel = false,
                    id = "validator_fallback_${firstLayer.name}"
                )
            )
        } else {
            ExecutionTree(
                rootNode = ExecutionNode(
                    layerName = "unknown",
                    function = "execute",
                    args = mapOf("query" to userQuery),
                    children = emptyList(),
                    parallel = false,
                    id = "validator_fallback_unknown"
                )
            )
        }
    }
}
