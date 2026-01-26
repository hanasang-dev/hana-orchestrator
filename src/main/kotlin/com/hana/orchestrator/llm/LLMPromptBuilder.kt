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
        val layersInfo = formatLayerDescriptionsCompact(layerDescriptions)
        
        return """사용자 요청: "$userQuery"

사용 가능한 레이어:
$layersInfo

JSON 형식으로 실행 트리를 생성하세요:
{
    "rootNode": {
        "layerName": "레이어명",
        "function": "함수명",
        "args": {"key": "value"},
        "parallel": false,
        "children": []
    }
}

규칙:
- rootNode는 하나만
- children으로 순서 정의
- parallel=true면 병렬 실행
- args에 "query" 포함
- JSON만 출력""".trimIndent()
    }
    
    /**
     * 결과 평가 프롬프트
     */
    fun buildEvaluationPrompt(
        userQuery: String,
        executionResult: String,
        executionContext: com.hana.orchestrator.domain.entity.ExecutionContext?
    ): String {
        return """요구사항: "$userQuery"
결과: "$executionResult"

중요 제약사항:
- text-transformer.toUpperCase는 영문자만 대문자로 변환 가능 (한글/중국어/일본어 등은 변환 안됨)
- 한글/비영문 텍스트에 toUpperCase 적용 시 결과가 입력과 동일하면 변환 실패로 간주
- 요구사항에 "대문자로 변환"이 있으면 결과가 입력과 달라야 함

JSON으로 평가:
{
    "isSatisfactory": true/false,
    "reason": "판단 이유",
    "needsRetry": true/false
}

기준:
- isSatisfactory: 요구사항 충족 여부 (대문자 변환 요구 시 결과가 실제로 변환되어야 함)
- needsRetry: 재처리 필요 여부
- JSON만 출력""".trimIndent()
    }
    
    /**
     * 재처리 방안 프롬프트
     */
    fun buildRetryStrategyPrompt(
        userQuery: String,
        previousHistory: ExecutionHistory,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        val layersInfo = formatLayerDescriptionsCompact(layerDescriptions)
        val previousResult = previousHistory.result.result.take(200)
        
        return """요구사항: "$userQuery"
이전 결과: "$previousResult"
에러: ${previousHistory.result.error ?: "없음"}

사용 가능한 레이어:
$layersInfo

JSON으로 재처리 방안 제시:
{
    "shouldStop": true/false,
    "reason": "이유",
    "newTree": {
        "rootNode": {
            "layerName": "레이어명",
            "function": "함수명",
            "args": {},
            "parallel": false,
            "children": []
        }
    }
}

- shouldStop: 해결 불가능시만 true
- newTree: 재처리 트리 (shouldStop=true면 무시)
- JSON만 출력""".trimIndent()
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
        val prevTree = previousTree?.let { "${it.rootNode.layerName}.${it.rootNode.function}" } ?: "없음"
        val currTree = "${currentTree.rootNode.layerName}.${currentTree.rootNode.function}"
        
        return """요구사항: "$userQuery"
이전 트리: $prevTree
이전 결과: "${previousResult.take(200)}"
현재 트리: $currTree
현재 결과: "${currentResult.take(200)}"

JSON으로 비교:
{
    "isSignificantlyDifferent": true/false,
    "reason": "이유"
}

기준:
- true: 트리 구조 변경 또는 결과 개선
- false: 구조/결과 유사, 개선 없음
- JSON만 출력""".trimIndent()
    }
    
    /**
     * 레이어 설명 포맷팅 (간소화 버전)
     */
    private fun formatLayerDescriptionsCompact(
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        return layerDescriptions.joinToString("\n") { layer ->
            val funcs = if (layer.functionDetails.isNotEmpty()) {
                layer.functionDetails.values.joinToString(", ") { func ->
                    val params = func.parameters.keys.joinToString(",")
                    "${func.name}($params)"
                }
            } else {
                layer.functions.joinToString(",")
            }
            "${layer.name}: $funcs"
        }
    }
    
    /**
     * 레이어 설명 포맷팅 (상세 버전 - 필요시 사용)
     */
    private fun formatLayerDescriptions(
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        return formatLayerDescriptionsCompact(layerDescriptions)
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
    
    /**
     * 파라미터 추출 프롬프트
     * 부모 레이어의 결과를 자식 레이어 함수의 파라미터로 변환
     */
    fun buildParameterExtractionPrompt(
        parentResult: String,
        childLayerName: String,
        childFunctionName: String,
        childFunctionDetails: com.hana.orchestrator.layer.FunctionDescription,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        val paramsInfo = childFunctionDetails.parameters.entries.joinToString(", ") { (name, param) ->
            val req = if (param.required) "필수" else "선택"
            "$name:${param.type}($req)"
        }
        
        return """부모 결과: "$parentResult"
함수: ${childLayerName}.${childFunctionName}
설명: ${childFunctionDetails.description}
파라미터: $paramsInfo

JSON으로 변환:
{
    "파라미터명": "값"
}

- 부모 결과를 파라미터에 매핑
- 타입 변환 (String/Int/Boolean)
- 필수 파라미터 포함
- JSON만 출력""".trimIndent()
    }
}
