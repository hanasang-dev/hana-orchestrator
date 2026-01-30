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
     * 요구사항 실행 가능성 검증 프롬프트
     */
    fun buildFeasibilityCheckPrompt(
        userQuery: String,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        val layersInfo = formatLayerDescriptionsCompact(layerDescriptions)
        
        return """요청: "$userQuery"

사용 가능한 레이어:
$layersInfo

위 레이어로 실행 가능한지 판단하고, 반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요:

{"feasible":true,"reason":"이유","suggestion":null}""".trimIndent()
    }
    
    /**
     * 실행 트리 생성 프롬프트
     */
    fun buildExecutionTreePrompt(
        userQuery: String,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        // 모든 파라미터 정보 필요 (필수 + 선택 모두)
        val layersInfo = formatLayerDescriptionsCompact(layerDescriptions)
        
        return """요청: "$userQuery"

사용 가능한 레이어:
$layersInfo

실행 계획을 생성하고, 반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요:

{"rootNodes":[{"layerName":"레이어명","function":"함수명","args":{"파라미터명":"값"},"parallel":false,"children":[]}]}""".trimIndent()
    }
    
    /**
     * 결과 평가 프롬프트
     * 
     * @param executionContext 현재는 사용하지 않지만, LLMClient 인터페이스 일관성 및 향후 확장을 위해 유지
     */
    fun buildEvaluationPrompt(
        userQuery: String,
        executionResult: String,
        @Suppress("UNUSED_PARAMETER") executionContext: com.hana.orchestrator.domain.entity.ExecutionContext?
    ): String {
        return """요구사항: "$userQuery"
실행 결과: "$executionResult"

결과가 요구사항을 충족하는지 평가하세요. 다음을 고려하세요:
- 요구사항의 의도를 파악하고, 결과가 그 의도를 충족하는지 판단
- 결과에 요구사항의 핵심 내용이 포함되어 있으면 충족으로 판단
- 기술적으로 불가능한 요구사항의 경우, 원본이 그대로 나오거나 적절한 대안이 제공되면 충족으로 판단
- 요구사항의 의도와 결과의 일치 여부를 중시하되, 형식적인 차이는 허용

반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요:

{"isSatisfactory":true,"reason":"이유","needsRetry":false}""".trimIndent()
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

레이어:
$layersInfo

재처리 방안을 제시하고, 반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요:

{"shouldStop":false,"reason":"이유","newTree":{"rootNodes":[{"layerName":"레이어명","function":"함수명","args":{},"parallel":false,"children":[]}]}}""".trimIndent()
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
        val prevTree = previousTree?.let { 
            it.rootNodes.joinToString(", ") { node -> "${node.layerName}.${node.function}" }
        } ?: "없음"
        val currTree = currentTree.rootNodes.joinToString(", ") { node -> "${node.layerName}.${node.function}" }
        
        return """요구사항: "$userQuery"
이전 트리: $prevTree
이전 결과: "${previousResult.take(200)}"
현재 트리: $currTree
현재 결과: "${currentResult.take(200)}"

비교하고, 반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요:

{"isSignificantlyDifferent":true,"reason":"이유"}""".trimIndent()
    }
    
    /**
     * 레이어 설명 포맷팅 (간소화 버전 - 최소한의 정보만)
     */
    private fun formatLayerDescriptionsCompact(
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        return layerDescriptions.joinToString("\n") { layer ->
            val funcs = if (layer.functionDetails.isNotEmpty()) {
                layer.functionDetails.values.joinToString(", ") { func ->
                    val params = func.parameters.entries.joinToString(",") { (name, param) ->
                        "$name:${param.type}"
                    }
                    "${func.name}($params)"
                }
            } else {
                layer.functions.joinToString(", ")
            }
            "${layer.name}: $funcs"
        }
    }
    
    /**
     * 파라미터 추출 프롬프트
     * 부모 레이어의 결과를 자식 레이어 함수의 파라미터로 변환
     * 
     * @param layerDescriptions 현재는 사용하지 않지만, LLMClient 인터페이스 일관성 및 향후 확장을 위해 유지
     */
    fun buildParameterExtractionPrompt(
        parentResult: String,
        childLayerName: String,
        childFunctionName: String,
        childFunctionDetails: com.hana.orchestrator.layer.FunctionDescription,
        @Suppress("UNUSED_PARAMETER") layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        val paramsInfo = childFunctionDetails.parameters.entries.joinToString(", ") { (name, param) ->
            val req = if (param.required) "필수" else "선택"
            "$name:${param.type}($req)"
        }
        
        return """부모 결과: "$parentResult"
함수: ${childLayerName}.${childFunctionName}
설명: ${childFunctionDetails.description}
파라미터: $paramsInfo

부모 결과를 파라미터에 매핑하고, 반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요:

{"파라미터명":"값"}""".trimIndent()
    }
}
