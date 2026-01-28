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
     * 다중 루트 노드를 지원: 각 루트는 독립적으로 검증됨
     */
    fun validateAndFix(tree: ExecutionTree, userQuery: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // 루트 노드가 없는 경우 에러
        if (tree.rootNodes.isEmpty()) {
            errors.add("실행 트리에 루트 노드가 없습니다.")
            return ValidationResult(
                isValid = false,
                errors = errors,
                warnings = warnings,
                fixedTree = null
            )
        }
        
        // 각 루트 노드 검증 및 수정
        val fixedRootNodes = tree.rootNodes.mapIndexed { index, rootNode ->
            // 트리 깊이 검증
            val depth = calculateTreeDepth(rootNode)
            if (depth > MAX_TREE_DEPTH) {
                errors.add("루트 노드 #${index + 1}의 트리 깊이($depth)가 최대값($MAX_TREE_DEPTH)을 초과합니다.")
            }
            
            // 순환 참조 검증
            val cycleDetected = detectCycle(rootNode)
            if (cycleDetected) {
                errors.add("루트 노드 #${index + 1}의 트리 구조에 순환 참조가 감지되었습니다.")
            }
            
            // 노드 검증 및 수정
            validateAndFixNode(rootNode, userQuery, errors, warnings, depth = 0)
        }
        
        return if (errors.isEmpty()) {
            ValidationResult(
                isValid = true,
                warnings = warnings,
                fixedTree = ExecutionTree(rootNodes = fixedRootNodes, name = tree.name)
            )
        } else {
            // 에러가 있으면 null 반환 (폴백 없이 실패 처리)
            ValidationResult(
                isValid = false,
                errors = errors,
                warnings = warnings,
                fixedTree = null
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
            warnings.add(
                "레이어 '${node.layerName}'에 함수 '${node.function}'가 없습니다. 사용 가능한 함수: ${
                    layerDesc.functions.joinToString(
                        ", "
                    )
                }"
            )
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

        // args는 그대로 사용 (query는 더 이상 필수가 아님)
        val fixedArgs = node.args

        // 병렬 실행 검증: parallel=true인데 children이 1개면 경고
        if (node.parallel && node.children.size < 2) {
            warnings.add("병렬 실행(parallel=true)인데 자식 노드가 ${node.children.size}개입니다. 병렬 실행은 자식 노드가 2개 이상일 때 의미가 있습니다.")
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
}
