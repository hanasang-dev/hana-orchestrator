package com.hana.orchestrator.llm

import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionHistory

/**
 * LLM 프롬프트 생성기
 * SRP: 프롬프트 생성만 담당
 * DRY: 공통 프롬프트 구조 추출
 */
internal class LLMPromptBuilder {
    companion object {
        /**
         * JSON 규칙 (공통)
         */
        private const val JSON_RULES = """JSON 규칙:
- 문자열 값 내부의 따옴표(")는 반드시 백슬래시로 이스케이프하세요 (\")
- 유니코드 이스케이프(\\u{...})나 잘못된 형식(\\u{C5AC번 등)을 절대 사용하지 마세요. 한글, 영문 등 모든 문자는 일반 문자열로 작성하세요
- 예: "args": {"message": "안녕"} (O) vs "args": {"message": "\\u{c548}\\u{d55c}"} (X) vs "args": {"message": "\\u{C5AC번"} (X)
- 올바른 예: "args": {"message": "안녕"} 또는 "args": {"text": "문자열 내부의 \\\"따옴표\\\"는 이스케이프"}
- 경로 처리: 상대 경로는 그대로 사용하세요. "현재 디렉토리"는 "."로, "src"는 "src"로 표현하세요. 절대 경로(/로 시작)로 변환하지 마세요."""
        
        /**
         * 4단계 분석 절차 (공통)
         */
        private const val FOUR_STEP_PROCEDURE = """중요: 반드시 다음 절차를 순서대로 수행하세요. 각 단계를 건너뛰지 마세요.

판단 절차 (필수):
1단계: 요청 분석
- 요청이 무엇을 요구하는지 정확히 분석하세요
- 요청의 핵심 기능 요구사항을 명확히 파악하세요

2단계: 각 레이어의 기능 범위 파악 (반드시 수행)
- 각 레이어의 description을 한 줄씩 읽으세요
- 레이어 description에 명시된 기능과 명시되지 않은 기능을 명확히 구분하세요
- 레이어 description에 "제공하지 않습니다", "불가능합니다" 등의 표현이 있으면 해당 기능은 절대 제공하지 않습니다
- 각 레이어가 제공하는 기능 범위를 정확히 파악하세요

3단계: 각 함수의 기능 파악 (반드시 수행)
- 각 함수의 설명을 읽으세요
- 함수 설명에 명시된 작업과 명시되지 않은 작업을 명확히 구분하세요
- 함수 설명에 "없습니다", "하지 않습니다" 등의 표현이 있으면 해당 작업은 절대 수행하지 않습니다

4단계: 매칭 검증 (반드시 수행)
- 요청의 핵심 기능 요구사항이 각 레이어의 description에 명시된 기능 범위 내에 있는지 확인하세요
- 요청의 핵심 기능 요구사항이 함수 설명에 명시된 작업으로 수행 가능한지 확인하세요"""
    }
    
    /**
     * 사용 가능한 레이어 이름 목록 생성 (헬퍼)
     */
    private fun getAvailableLayerNames(layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>): String {
        return layerDescriptions.joinToString(", ") { it.name }
    }
    
    /**
     * 노드 필수 필드 설명 (공통)
     */
    private fun buildNodeRequiredFields(availableLayerNames: String): String {
        return """중요: 모든 노드(루트 노드와 children 내부 노드 모두)는 반드시 다음 필수 필드를 포함해야 합니다:
- layerName: 레이어 이름 (문자열, 필수) - 반드시 위에 나열된 실제 레이어 이름 중 하나를 사용하세요: $availableLayerNames
- function: 함수 이름 (문자열, 필수) - 해당 레이어의 실제 함수 이름을 사용하세요
- args: 파라미터 맵 (객체, 기본값: {})
- parallel: 병렬 실행 여부 (불린, 기본값: false)
- children: 자식 노드 배열 (배열, 기본값: [])

중요: layerName은 반드시 실제 레이어 이름을 사용하세요. "레이어명", "레이어이름" 같은 플레이스홀더를 사용하지 마세요.
사용 가능한 레이어 이름: $availableLayerNames"""
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
        val availableLayerNames = getAvailableLayerNames(layerDescriptions)
        
        return """요청: "$userQuery"

사용 가능한 레이어:
$layersInfo

의도 구분 (먼저 판단):
- 요청이 답만 필요로 하는지, 실제 실행이 필요한지 구분하세요. 답만 필요하면 위 레이어 중 "사용자 질문에 직접 답변"하는 역할의 레이어·함수 하나만 선택하고, 실행 단계를 만들지 마세요. 실행이 필요하면 해당 실행에 맞는 레이어·함수를 선택하세요.
- 답만 필요할 때는 경로·명령 등 실행용 인자를 지어내지 마세요. 답변만 반환하는 레이어는 요청 문장만 인자로 넘기면 됩니다.

각 레이어의 목적(description)과 함수 설명을 정확히 읽고, 위 의도에 맞는 레이어와 함수만 선택하세요.

$FOUR_STEP_PROCEDURE
- 레이어 description이나 함수 설명에 명시되지 않은 기능을 요청이 요구하면 해당 레이어/함수를 선택하지 마세요.
- 불필요한 레이어를 사용하지 마세요.

선택 기준:
- 레이어 description에 명시된 기능만 사용 가능합니다. 명시되지 않은 기능은 사용할 수 없습니다.
- 함수 설명에 명시된 작업만 수행 가능합니다. 명시되지 않은 작업은 수행할 수 없습니다.

중요 (여러 작업이 함께 있는 요청):
- 요청에 서로 다른 작업이 여러 개 있으면, 각 작업에 맞는 단계를 모두 실행 계획에 넣으세요. 한 가지만 단일 노드로 두지 마세요.
- 순차가 필요하면 루트의 children에 순서대로 넣거나, 루트를 첫 단계로 하고 children으로 이어 붙이세요.
- 사용할 레이어·함수는 반드시 위에 나열된 사용 가능한 레이어 목록에서만 선택하세요.
- 어떤 레이어를 쓸지는 위 레이어들의 description·함수 설명만 보고 요청에 맞게 판단하세요.

실행 계획을 생성하고, 반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요.

필수: rootNodes 배열에는 반드시 1개 이상의 노드가 있어야 합니다. 빈 배열(rootNodes:[])은 허용되지 않습니다. 답변·조회만 요청해도 실행 계획이 필요하므로, 요청에 맞는 레이어와 함수를 하나 이상 선택하세요.

$JSON_RULES

${buildNodeRequiredFields(availableLayerNames)}

이전 노드 결과를 인자로 쓰기 (순차 흐름):
- 자식 노드에서 부모 노드의 실행 결과를 인자로 쓰려면 해당 인자 값을 문자열 "{{parent}}"로 두세요.
- 특정 노드의 결과를 쓰려면 "{{nodeId:노드id}}"를 사용하세요 (노드 id는 트리 생성 시 각 노드에 부여한 id).

예시는 참고용이며, 레이어·함수 이름은 위 사용 가능한 레이어 목록에서만 선택하세요.

예시 구조 (단일 노드):
{"rootNodes":[{"layerName":"레이어이름","function":"함수이름","args":{"파라미터명":"값"},"parallel":false,"children":[]}]}

예시 구조 (중첩된 children):
{"rootNodes":[{"layerName":"레이어이름","function":"함수이름","args":{"파라미터명":"값"},"parallel":false,"children":[{"layerName":"자식레이어이름","function":"자식함수이름","args":{"파라미터명":"값"},"parallel":false,"children":[]}]}]}""".trimIndent()
    }
    
    /** 결과 평가 프롬프트. 요구사항과 실행 결과(및 선택적 실행 요약)로 판단. */
    fun buildEvaluationPrompt(userQuery: String, executionResult: String, executionSummary: String? = null): String {
        val summaryBlock = if (executionSummary != null) {
            """
            실행 요약 (참고용, 판단은 요구사항과 실행 결과 기준으로 하세요): $executionSummary
            """.trimIndent()
        } else ""
        return """요구사항: "$userQuery"
실행 결과: "$executionResult"$summaryBlock

결과가 요구사항을 충족하는지 평가하세요.

평가 원칙:
- 부합 = 요구사항의 의도(사용자가 바라는 출력·동작 결과)가 실행 결과에 나왔으면 됨. 요구사항 문장과 결과 문장이 같을 필요 없음.
- 지시형 요청: 요청한 내용이 실행 결과에 포함·반영되었으면 부합.
- 질문형 요청: 실행 결과가 그에 대한 답이면 부합.
- 실행 결과에 요청한 내용이 없을 때만 미부합. 있으면 부합.

반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요.

중요: 다음 세 필드는 모두 필수입니다. 반드시 모두 포함하세요. 필드가 누락되면 파싱 오류가 발생합니다:
- isSatisfactory: 요구사항 충족 여부 (불린, 필수) - 반드시 true 또는 false만 사용. null이나 생략 불가
- reason: 평가 이유 (문자열, 필수) - 문자열 내부에 따옴표(")가 있으면 백슬래시로 이스케이프하세요 (\")
- needsRetry: 재처리 필요 여부 (불린, 필수) - 반드시 true 또는 false만 사용. null이나 생략 불가

$JSON_RULES

응답 형식 (반드시 이 형식을 정확히 따르세요):
{"isSatisfactory":true,"reason":"결과가 요구사항을 충족합니다","needsRetry":false}

또는

{"isSatisfactory":false,"reason":"결과가 요구사항을 충족하지 않습니다","needsRetry":true}

주의: JSON 응답은 반드시 완전한 객체여야 합니다. 중간에 끊기거나 필드가 누락되면 안 됩니다.""".trimIndent()
    }
    
    /**
     * 재처리 방안 프롬프트
     * @param previousExecutedWorkSummary 이전 실행에서 실제로 수행된 작업 목록(예: "echo.repeat, build.compileKotlin"). 실패 이유와 비교해 빠진 작업을 새 트리에 넣도록 LLM이 판단하는 데 씀.
     */
    fun buildRetryStrategyPrompt(
        userQuery: String,
        previousHistory: ExecutionHistory,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>,
        previousExecutedWorkSummary: String? = null
    ): String {
        val layersInfo = formatLayerDescriptionsCompact(layerDescriptions)
        val previousResult = previousHistory.result.result.take(200)
        val availableLayerNames = getAvailableLayerNames(layerDescriptions)
        val executedSection = if (!previousExecutedWorkSummary.isNullOrBlank()) {
            """
이전 실행에서 실제로 수행된 작업: $previousExecutedWorkSummary
(실패 이유를 보면, 위 목록에 없던 작업이 요구사항에 필요할 수 있음. 새 트리에 그 작업을 수행할 단계를 반드시 포함하세요.)
"""
        } else ""

        return """요구사항: "$userQuery"
이전 결과: "$previousResult"
에러: ${previousHistory.result.error ?: "없음"}$executedSection

레이어:
$layersInfo

재처리 방안을 제시하고, 반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요.

$JSON_RULES
- reason 필드에 따옴표가 포함되면 이스케이프하세요
- newTree의 args 값에도 따옴표 이스케이프가 필요합니다

중요: 다음 필드는 모두 필수입니다:
- shouldStop: 재처리 중단 여부 (불린, 필수)
- reason: 중단/재처리 이유 (문자열, 필수)
- newTree: 새로운 실행 트리 (shouldStop=false일 때만 필요, shouldStop=true면 null 가능)

newTree의 모든 노드는 다음 필수 필드를 포함해야 합니다:
${buildNodeRequiredFields(availableLayerNames).replace("중요: 모든 노드", "중요: newTree의 모든 노드")}

중요: 반드시 객체 형태로 응답하세요. 배열 형태로 응답하지 마세요.
- 올바른 형식: {"shouldStop":false,"reason":"...","newTree":{"rootNodes":[...]}}
- 잘못된 형식: [{"layerName":...}] (배열로 시작하면 안 됩니다)

중요: reason 필드의 문자열이 끝나기 전에 JSON이 끊기지 않도록 주의하세요. 따옴표를 제대로 닫으세요.

응답 형식 (재처리 계속):
{"shouldStop":false,"reason":"파라미터 수정 필요","newTree":{"rootNodes":[{"layerName":"실제레이어이름","function":"실제함수이름","args":{"파라미터명":"값"},"parallel":false,"children":[]}]}}

응답 형식 (재처리 중단):
{"shouldStop":true,"reason":"근본 해결 불가능","newTree":null}

주의: reason 필드 내부의 따옴표는 반드시 이스케이프하세요. 예: "reason":"파일이 존재하지 않습니다. \\\"test.txt\\\""""".trimIndent()
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
     * 레이어의 목적과 함수 설명을 포함하여 LLM이 정확히 이해할 수 있도록 함
     */
    private fun formatLayerDescriptionsCompact(
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        return layerDescriptions.joinToString("\n\n") { layer ->
            val layerHeader = "📦 ${layer.name}"
            
            // Description을 구조화된 형식으로 유지 (줄바꿈 보존)
            val layerDesc = if (layer.description.isNotBlank()) {
                // Description이 이미 구조화되어 있으므로, 각 줄에 들여쓰기만 추가
                val formattedDesc = layer.description
                    .lines()
                    .joinToString("\n   ") { line ->
                        if (line.trim().isEmpty()) "" else line.trim()
                    }
                    .trim()
                "\n   $formattedDesc"
            } else {
                ""
            }
            
            val funcs = if (layer.functionDetails.isNotEmpty()) {
                layer.functionDetails.values.joinToString("\n") { func ->
                    val params = func.parameters.entries.joinToString(", ") { (name, param) ->
                        val req = if (param.required) "필수" else "선택"
                        "$name:${param.type}($req)"
                    }
                    val funcDesc = if (func.description.isNotBlank() && func.description != func.name) {
                        // 함수 설명도 구조화된 형식 유지
                        " - ${func.description}"
                    } else {
                        ""
                    }
                    "   - ${func.name}($params)$funcDesc"
                }
            } else {
                layer.functions.joinToString("\n") { func ->
                    "   - $func"
                }
            }
            
            "$layerHeader$layerDesc\n   함수:\n$funcs"
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

$JSON_RULES

{"파라미터명":"값"}""".trimIndent()
    }
    
    /**
     * LLM 직접 답변 가능 여부 확인 프롬프트
     */
    fun buildLLMDirectAnswerCapabilityPrompt(userQuery: String): String {
        return """요청: "$userQuery"

이 요청은 레이어로 실행할 수 없습니다. LLM이 직접 답변할 수 있는지 판단하세요.

요청의 성격과 필요한 리소스를 분석하여, LLM이 직접 답변할 수 있는지 판단하세요.

반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요:

{"canAnswer":true,"reason":"이유"}""".trimIndent()
    }
    
    /**
     * LLM 직접 답변 생성 프롬프트
     */
    fun buildDirectAnswerPrompt(userQuery: String): String {
        return """요청: "$userQuery"

위 요청에 대해 직접 답변해주세요. 정확하고 간결하게 답변하세요.

답변:""".trimIndent()
    }
}
