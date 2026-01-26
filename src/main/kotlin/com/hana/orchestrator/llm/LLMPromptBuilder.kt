package com.hana.orchestrator.llm

import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionHistory

/**
 * LLM 프롬프트 생성기
 * SRP: 프롬프트 생성만 담당
 * DRY: 공통 프롬프트 구조 추출
 */
internal class LLMPromptBuilder {
    /**
     * 실행 트리 생성 프롬프트
     */
    fun buildExecutionTreePrompt(
        userQuery: String,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        val layersInfo = formatLayerDescriptions(layerDescriptions)
        
        return """
        당신은 AI 오케스트레이터입니다. 사용자의 요청에 가장 적절한 레이어와 실행 순서를 트리 구조로 계획해주세요.

        사용자 요청: "$userQuery"

        사용 가능한 레이어들:
        $layersInfo

        다음 JSON 형식으로 응답해주세요:
        {
            "rootNode": {
                "layerName": "레이어이름",
                "function": "함수명",
                "args": {"key": "value"},
                "parallel": false,
                "children": []
            }
        }
        
        주의사항:
        1. rootNode는 반드시 하나여야 합니다
        2. children 배열로 하위 실행 순서를 정의하세요
        3. parallel이 true면 children들이 병렬 실행됩니다
        4. args에는 "query" 키에 사용자 요청을 포함하세요
        5. JSON 형식만 출력하고 다른 설명은 넣지 마세요
        """.trimIndent()
    }
    
    /**
     * 결과 평가 프롬프트
     */
    fun buildEvaluationPrompt(
        userQuery: String,
        executionResult: String,
        executionContext: com.hana.orchestrator.domain.entity.ExecutionContext?
    ): String {
        val contextInfo = formatExecutionContext(executionContext)
        
        return """
        당신은 실행 결과 평가자입니다. 사용자의 요구사항과 실제 실행 결과를 비교하여 평가해주세요.

        사용자 요구사항: "$userQuery"
        
        실행 결과: "$executionResult"
        
        $contextInfo
        
        다음 JSON 형식으로 응답해주세요:
        {
            "isSatisfactory": true/false,
            "reason": "판단 이유를 자세히 설명",
            "needsRetry": true/false
        }
        
        평가 기준:
        1. isSatisfactory: 결과가 요구사항을 충족하는지 여부
        2. reason: 왜 그렇게 판단했는지 구체적인 이유
        3. needsRetry: 재처리가 필요한지 여부 (결과가 부족하거나 방향이 틀렸을 때 true)
        
        JSON 형식만 출력하고 다른 설명은 넣지 마세요.
        """.trimIndent()
    }
    
    /**
     * 재처리 방안 프롬프트
     */
    fun buildRetryStrategyPrompt(
        userQuery: String,
        previousHistory: ExecutionHistory,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        val layersInfo = formatLayerDescriptions(layerDescriptions)
        val failedNodesInfo = formatFailedNodes(previousHistory.result.context?.failedNodes)
        val previousTreeInfo = formatExecutionTree(previousHistory.result.executionTree)
        
        return """
        당신은 실행 실패 분석가입니다. 실패한 실행을 분석하고 재처리 방안을 제시해주세요.

        사용자 요구사항: "$userQuery"
        
        이전 실행 결과:
        - 상태: ${previousHistory.status}
        - 결과: "${previousHistory.result.result}"
        - 에러: ${previousHistory.result.error ?: "없음"}
        
        $previousTreeInfo
        
        실패한 노드들:
        $failedNodesInfo
        
        사용 가능한 레이어들:
        $layersInfo
        
        다음 JSON 형식으로 응답해주세요:
        {
            "shouldStop": true/false,
            "reason": "중단 여부 판단 이유",
            "newTree": {
                "rootNode": {
                    "layerName": "레이어이름",
                    "function": "함수명",
                    "args": {"key": "value"},
                    "parallel": false,
                    "children": []
                }
            }
        }
        
        판단 기준:
        1. shouldStop: 근본적으로 해결이 불가능한 경우 true (예: 요구사항 자체가 모순, 필요한 레이어가 없음)
        2. reason: 왜 중단하거나 재처리하는지 구체적인 이유
        3. newTree: 재처리할 새로운 실행 트리 (shouldStop이 true면 무시됨)
        
        주의사항:
        - 근본 해결 불가능한 경우에만 shouldStop을 true로 설정
        - 재처리 가능하면 새로운 트리를 제시하되, 실패 원인을 고려하여 수정
        - JSON 형식만 출력하고 다른 설명은 넣지 마세요
        """.trimIndent()
    }
    
    /**
     * 실행 비교 프롬프트
     */
    fun buildComparisonPrompt(
        userQuery: String,
        previousTree: ExecutionTree?,
        previousResult: String,
        currentTree: ExecutionTree,
        currentResult: String
    ): String {
        val previousTreeInfo = formatExecutionTree(previousTree)
        val currentTreeInfo = formatExecutionTree(currentTree)
        
        return """
        당신은 실행 비교 분석가입니다. 이전 실행과 현재 실행을 비교하여 유의미한 차이가 있는지 판단해주세요.

        사용자 요구사항: "$userQuery"
        
        $previousTreeInfo
        이전 실행 결과: "$previousResult"
        
        $currentTreeInfo
        현재 실행 결과: "$currentResult"
        
        다음 JSON 형식으로 응답해주세요:
        {
            "isSignificantlyDifferent": true/false,
            "reason": "판단 이유를 자세히 설명"
        }
        
        판단 기준:
        1. isSignificantlyDifferent: 
           - true: 실행 트리 구조가 다르거나, 결과가 명확히 개선되었거나, 접근 방식이 변경됨
           - false: 트리 구조가 거의 동일하고, 결과도 비슷하며, 유의미한 개선이 없음
        2. reason: 왜 그렇게 판단했는지 구체적인 이유
        
        주의사항:
        - 단순히 결과 텍스트가 약간 다르다고 해서 유의미한 차이는 아님
        - 트리 구조나 실행 방식의 변화, 또는 결과의 질적 개선이 있어야 유의미한 차이로 판단
        - JSON 형식만 출력하고 다른 설명은 넣지 마세요
        """.trimIndent()
    }
    
    /**
     * 레이어 설명 포맷팅
     * DRY: 공통 포맷팅 로직
     * KSP로 생성된 functionDetails 활용하여 상세한 파라미터 정보 제공
     */
    private fun formatLayerDescriptions(
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        return layerDescriptions.joinToString("\n\n") { layer ->
            val functionsInfo = if (layer.functionDetails.isNotEmpty()) {
                // functionDetails가 있으면 상세 정보 포함
                layer.functionDetails.values.joinToString("\n    ") { func ->
                    val paramsInfo = func.parameters.entries.joinToString(", ") { (name, param) ->
                        val required = if (param.required) "필수" else "선택"
                        val defaultValue = param.defaultValue?.let { " (기본값: $it)" } ?: ""
                        "$name: ${param.type} ($required$defaultValue)"
                    }
                    "- ${func.name}: ${func.description}\n      파라미터: ($paramsInfo)\n      반환 타입: ${func.returnType}"
                }
            } else {
                // functionDetails가 없으면 함수명만 나열 (하위 호환성)
                layer.functions.joinToString(", ")
            }
            
            """
            레이어: ${layer.name}
            설명: ${layer.description}
            함수:
            $functionsInfo
            """.trimIndent()
        }
    }
    
    /**
     * 실행 컨텍스트 포맷팅
     */
    private fun formatExecutionContext(
        context: com.hana.orchestrator.domain.entity.ExecutionContext?
    ): String {
        return context?.let { ctx ->
            """
            실행 컨텍스트:
            - 성공한 노드: ${ctx.completedNodes.size}개
            - 실패한 노드: ${ctx.failedNodes.size}개
            - 실행 중인 노드: ${ctx.runningNodes.size}개
            """.trimIndent()
        } ?: "실행 컨텍스트 정보 없음"
    }
    
    /**
     * 실패한 노드 포맷팅
     */
    private fun formatFailedNodes(
        failedNodes: List<com.hana.orchestrator.domain.entity.NodeExecutionResult>?
    ): String {
        return failedNodes?.joinToString("\n") { node ->
            val errorMessage = node.error ?: "에러 정보 없음"
            "- ${node.node.layerName}.${node.node.function}: $errorMessage"
        } ?: "실패한 노드 없음"
    }
    
    /**
     * 실행 트리 포맷팅
     */
    private fun formatExecutionTree(tree: ExecutionTree?): String {
        return tree?.let { t ->
            """
            실행 트리:
            - 루트: ${t.rootNode.layerName}.${t.rootNode.function}
            - 자식 수: ${t.rootNode.children.size}
            """.trimIndent()
        } ?: "트리 정보 없음"
    }
}
