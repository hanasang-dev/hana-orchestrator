package com.hana.orchestrator.llm

import com.hana.orchestrator.domain.entity.ExecutionTree

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
    }

    /**
     * 사용 가능한 레이어 이름 목록 생성 (헬퍼)
     */
    private fun getAvailableLayerNames(layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>): String {
        return layerDescriptions.joinToString(", ") { it.name }
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

            val layerDesc = if (layer.description.isNotBlank()) {
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
     * LLM 직접 답변 생성 프롬프트
     */
    fun buildDirectAnswerPrompt(userQuery: String): String {
        return """요청: "$userQuery"

위 요청에 대해 직접 답변해주세요. 정확하고 간결하게 답변하세요.

답변:""".trimIndent()
    }

    /**
     * ReAct 다음 액션 결정 프롬프트
     * LLM이 스텝 히스토리를 보고 execute_tree / finish 를 결정
     */
    fun buildReActPrompt(
        query: String,
        stepHistory: List<ReActStep>,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>,
        projectContext: Map<String, String> = emptyMap()
    ): String {
        val layersInfo = formatLayerDescriptionsCompact(layerDescriptions)
        val availableLayerNames = getAvailableLayerNames(layerDescriptions)
        val historySection = if (stepHistory.isEmpty()) {
            "아직 수행한 작업 없음"
        } else {
            stepHistory.joinToString("\n") { step ->
                val treeDesc = step.tree?.rootNodes?.joinToString(", ") { "${it.layerName}.${it.function}" } ?: "(알 수 없음)"
                val result = if (step.result.length > 2000) {
                    "${step.result.take(2000)}...[전체 ${step.result.length}자 — 데이터가 이미 로드됨. 다시 읽기 금지]"
                } else {
                    step.result
                }
                // 힌트는 원본(step.result)에서 추출 — 잘린 result 아님
                val nextStepHint = buildString {
                    if (step.result.contains("다음 단계:")) {
                        val idx = step.result.indexOf("다음 단계:")
                        val end = step.result.indexOf("\n", idx).takeIf { it != -1 } ?: step.result.length
                        append("\n  🔜 [아직 미완료 — 반드시 다음에 실행] ${step.result.substring(idx, end).trim()}")
                    }
                    if (step.result.contains("[필수후속]")) {
                        val idx = step.result.indexOf("[필수후속]")
                        val end = step.result.indexOf("\n", idx).takeIf { it != -1 } ?: step.result.length
                        append("\n  ⛔ ${step.result.substring(idx, end).trim()}")
                    }
                }
                "✅ [완료] 스텝 ${step.stepNumber}: [$treeDesc]\n  → 결과: $result$nextStepHint"
            }
        }

        val alreadyDoneNote = if (stepHistory.isNotEmpty()) {
            val doneFunctions = stepHistory
                .flatMap { it.successfulFunctions }
                .distinct()
                .joinToString(", ")
            if (doneFunctions.isNotEmpty()) {
                "\n⛔ 이미 성공 완료된 함수 목록 → $doneFunctions\n   위 함수들은 실행 결과가 히스토리에 있습니다.\n   🔜 '아직 미완료' 표시가 있으면 그 단계만 실행하세요. 완료된 함수는 rootNodes에 넣지 마세요."
            } else ""
        } else ""

        val projectContextSection = if (projectContext.isNotEmpty()) {
            val sb = StringBuilder("\n📂 프로젝트 컨텍스트 (반드시 준수):\n")
            projectContext["workingDirectory"]?.let { sb.append("  작업 디렉토리: $it\n") }
            // kotlinFileIndex: "ClassName:relative/path.kt, ..." → 명확한 테이블로 변환
            projectContext["kotlinFileIndex"]?.takeIf { it.isNotEmpty() }?.let { index ->
                sb.append("\n  📄 Kotlin 파일 경로 인덱스 (파일 접근 시 반드시 이 경로를 사용):\n")
                index.split(", ").forEach { entry ->
                    val colon = entry.indexOf(':')
                    if (colon > 0) {
                        sb.append("    ${entry.substring(0, colon)} → ${entry.substring(colon + 1)}\n")
                    }
                }
            }
            projectContext.filterKeys { it != "workingDirectory" && it != "kotlinFileIndex" }
                .forEach { (k, v) -> sb.append("  [$k] $v\n") }
            sb.toString()
        } else ""

        return """목표: "$query"
$projectContextSection
사용 가능한 레이어:
$layersInfo

⛔ layerName은 반드시 위 목록에 있는 이름만 사용하세요: $availableLayerNames
   목록에 없는 레이어(예: echo, shell, command 등)는 절대 사용하지 마세요.

지금까지 수행한 작업:
$historySection
$alreadyDoneNote

⚠️ 데이터 흐름 규칙:
   - A의 결과가 B의 입력으로 필요하면 → B를 A의 children 배열 안에 넣고 B의 args에 "{{parent}}" 사용
   - "{{parent}}"는 직접 부모 노드의 결과만 가리킴. 형제(sibling) 노드 결과는 받을 수 없음.
   - 이 규칙은 깊이에 관계없이 동일하게 적용됨: 각 노드는 자신의 부모 결과만 {{parent}}로 받음.
     올바른: A(children:[B(children:[C(args:{x:"{{parent}}"})])]) ← C가 B 결과를, B가 A 결과를 받음
     잘못됨: A(children:[B, C]) ← C의 "{{parent}}"는 A 결과를 가리킴 (B 결과 아님)
   - LLM이 파일/커밋 내용을 알고 있다고 가정하지 마세요. 반드시 해당 레이어 함수로 먼저 가져오세요.

⭐ 데이터 연쇄 패턴 (최우선 규칙):
   - 데이터를 가져온 뒤 처리하는 작업은 반드시 한 번의 execute_tree로 처리하세요
   - 데이터를 쓰는 노드는 반드시 해당 데이터를 생성한 노드의 children에 배치하세요 (형제 배치 금지)
   - 변환 결과를 저장해야 한다면 저장 노드도 같은 트리의 children에 포함하세요 (읽기→변환→저장을 하나의 트리로)
   - 잘못된 예: 스텝1 데이터만 읽음 → 스텝2 같은 데이터를 다시 읽으려 함 (중복 루프)
   - 히스토리에 "[데이터가 이미 로드됨]" 표시가 있고 처리가 필요하면: finish 선택 후 result에 직접 결과 작성

결정 규칙 (반드시 순서대로 확인):
0. ⛔[finish 금지] 히스토리의 결과에 "다음 단계:"가 포함되어 있거나, "⛔ [필수후속"이 표시되어 있고 해당 후속 단계가 아직 실행되지 않은 경우 → 해당 단계를 먼저 실행해야 함. finish 선택 불가.
1. ⭐[최우선] 히스토리에 SUCCESS 결과가 있고, 결과에 "다음 단계:"가 없으며, 목표 달성에 필요한 모든 작업이 완료됐는가? → 반드시 finish 선택.
2. A 결과 → B 입력이 필요한가? → A를 rootNode로, B를 A의 children에 넣고 B.args에 "{{parent}}" 사용
3. 독립적으로 동시에 실행 가능한 작업이 2개 이상 있는가? → rootNodes 여러 개 (parallel: true)
4. 그 외 단일 작업이 필요한가? → rootNodes 1개
5. 목표를 수행하기에 사용자 의도가 불명확하거나 필수 정보가 부족한가? → ask 선택. 실행 가능한 정보가 있으면 ask 금지.

autoApprove 규칙:
- 각 노드에 "autoApprove" 필드를 설정할 수 있습니다 (기본값: false).
- autoApprove: false → 해당 동작 전 사용자 승인 대기 (되돌리기 어려운 작업, 파일 수정, 시스템 변경 등)
- autoApprove: true  → 즉시 실행 (조회·읽기·분석 등 되돌릴 수 있는 작업)

$JSON_RULES

반드시 다음 JSON 형식 중 하나로만 응답하세요. 다른 텍스트는 포함하지 마세요.

단일 노드:
{"action":"execute_tree","tree":{"rootNodes":[{"layerName":"git","function":"currentBranch","args":{},"parallel":false,"children":[]}]},"reasoning":"브랜치 조회"}

체인 — 앞 노드 결과({{parent}})가 뒤 노드 입력으로 필요한 경우, 뒤 노드를 앞 노드의 children에 넣는다. 깊이 제한 없음:
{"action":"execute_tree","tree":{"rootNodes":[{"layerName":"file-system","function":"readFile","args":{"path":"README.md"},"parallel":false,"children":[{"layerName":"llm","function":"analyze","args":{"context":"{{parent}}","query":"2줄로 요약해줘"},"parallel":false,"children":[]}]}]},"reasoning":"A→B 체인"}

병렬 — 독립적인 작업 동시 실행:
{"action":"execute_tree","tree":{"rootNodes":[{"layerName":"git","function":"status","args":{},"parallel":true,"children":[]},{"layerName":"git","function":"currentBranch","args":{},"parallel":true,"children":[]}]},"reasoning":"동시 실행"}

목표 완전 달성 시:
{"action":"finish","result":"(사용자에게 전달할 최종 답변)","reasoning":"완료 이유"}

사용자에게 추가 정보가 필요할 때:
{"action":"ask","question":"(사용자에게 할 구체적인 질문)","reasoning":"질문 이유"}""".trimIndent()
    }

    fun buildTreeReviewPrompt(
        userQuery: String,
        tree: ExecutionTree,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        val treeStr = tree.rootNodes.joinToString("\n") { node ->
            fun nodeToStr(n: com.hana.orchestrator.domain.entity.ExecutionNode, indent: Int): String {
                val pad = "  ".repeat(indent)
                val argsStr = n.args.entries.joinToString(", ") { "${it.key}=${it.value}" }
                val self = "${pad}- ${n.layerName}.${n.function}($argsStr)"
                return if (n.children.isEmpty()) self
                else self + "\n" + n.children.joinToString("\n") { nodeToStr(it, indent + 1) }
            }
            nodeToStr(node, 0)
        }
        val availableLayers = layerDescriptions.joinToString("\n") { desc ->
            "- ${desc.name}: ${desc.functions.joinToString(", ")}"
        }
        return """사용자 요청: "$userQuery"

사용 가능한 레이어 및 함수:
$availableLayers

사용자가 구성한 실행 트리:
$treeStr

위 트리가 사용자 요청을 올바르게 수행할 수 있는지 검토하세요.

검토 기준:
- 트리에 사용된 layerName과 function이 위 목록에 실제로 존재하는지 확인하세요
- 트리의 실행 흐름이 사용자 요청을 처리하기에 논리적으로 적절한지 판단하세요
- 레이어·함수가 존재하고 실행 흐름이 요청 의도에 부합하면 approved=true로 판단하세요
- 반드시 한국어로 reason을 작성하세요

반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요:
{"approved": true, "reason": "한 문장 이유"}
또는
{"approved": false, "reason": "한 문장 이유"}""".trimIndent()
    }
}
