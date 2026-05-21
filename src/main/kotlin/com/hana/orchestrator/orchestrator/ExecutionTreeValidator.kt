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
        
        // 전체 DAG 사이클 감지 (트리 엣지 + {{nodeId:X}} 크로스 참조 포함)
        val cycleErrors = detectDagCycles(tree)
        errors.addAll(cycleErrors)

        // 각 루트 노드 검증 및 수정
        val fixedRootNodes = tree.rootNodes.mapIndexed { index, rootNode ->
            // 트리 깊이 검증
            val depth = calculateTreeDepth(rootNode)
            if (depth > MAX_TREE_DEPTH) {
                errors.add("루트 노드 #${index + 1}의 트리 깊이($depth)가 최대값($MAX_TREE_DEPTH)을 초과합니다.")
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
     * DAG 전체 사이클 감지.
     * 트리 엣지(부모→자식)와 {{nodeId:X}} 크로스 참조 엣지를 모두 포함한 의존성 그래프에서 DFS로 감지.
     * 엣지 방향: "이 노드가 저 노드에 의존" (저 노드가 먼저 완료되어야 이 노드 실행 가능)
     */
    private fun detectDagCycles(tree: ExecutionTree): List<String> {
        val nodeIdRegex = Regex("""\{\{nodeId:([^}]+)\}\}""")

        // 의존성 그래프 구축: deps[id] = 이 노드가 의존하는 노드 ID 집합
        val deps = mutableMapOf<String, MutableSet<String>>()

        fun collectDeps(node: ExecutionNode, parentId: String?) {
            val myDeps = deps.getOrPut(node.id) { mutableSetOf() }
            if (parentId != null) myDeps.add(parentId)
            node.args.values.filterIsInstance<String>().forEach { v ->
                nodeIdRegex.findAll(v).forEach { mr -> myDeps.add(mr.groupValues[1].trim()) }
            }
            node.children.forEach { collectDeps(it, node.id) }
        }
        tree.rootNodes.forEach { collectDeps(it, null) }

        // DFS 사이클 감지
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()
        val errors = mutableListOf<String>()

        fun dfs(id: String) {
            visited += id; inStack += id
            for (dep in deps[id] ?: emptySet()) {
                if (dep in inStack) errors.add("순환 참조 감지: $id → $dep (사이클)")
                else if (dep !in visited) dfs(dep)
            }
            inStack -= id
        }
        deps.keys.filter { it !in visited }.forEach { dfs(it) }
        return errors
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

        // {{parent}}.propName 패턴 거부 — {{parent}}는 raw text string
        // property accessor 형태로 쓰면 경로가 깨짐: "M src/....propName" 형태로 치환됨
        val parentPropPattern = Regex("""{{parent}}\.\w+""")
        node.args.values.filterIsInstance<String>().forEach { v ->
            if (parentPropPattern.containsMatchIn(v)) {
                errors.add(
                    "노드 ${node.layerName}.${node.function} args에 '{{parent}}.property' 패턴 사용 불가. " +
                    "{{parent}}는 raw text이므로 property 접근자(.)를 붙일 수 없습니다. " +
                    "parent 결과를 직접 사용하거나 별도 파싱 단계를 추가하세요."
                )
            }
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
