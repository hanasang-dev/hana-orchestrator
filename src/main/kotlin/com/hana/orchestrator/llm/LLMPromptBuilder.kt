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
     * TBox: 전체 레이어의 이름 + 1줄 요약만 포함 (항상 프롬프트에 포함)
     * 컨텍스트를 최소화하면서 LLM이 레이어 존재를 인식할 수 있도록 함
     */
    private fun formatTBox(
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        return layerDescriptions.joinToString("\n") { layer ->
            val summary = layer.description.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(100) ?: ""
            "  ${layer.name}: $summary"
        }
    }

    /**
     * ABox: 관련 레이어의 함수 상세 스펙 포함 (임베딩으로 선택된 top-k 레이어만)
     * LLM이 실제로 호출할 레이어의 파라미터를 정확히 알 수 있도록 함
     */
    private fun formatABox(
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): String {
        if (layerDescriptions.isEmpty()) return ""
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
     *
     * @param allLayerDescriptions TBox — 전체 레이어 목록 (이름 + 1줄 요약)
     * @param relevantLayerDescriptions ABox — 임베딩으로 선택된 관련 레이어 (함수 상세 스펙)
     *        null이면 allLayerDescriptions를 ABox로도 사용 (임베딩 비활성화 시 fallback)
     */
    fun buildReActPrompt(
        query: String,
        stepHistory: List<ReActStep>,
        allLayerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>,
        projectContext: Map<String, String> = emptyMap(),
        relevantLayerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>? = null
    ): String {
        val tbox = formatTBox(allLayerDescriptions)
        val abox = formatABox(relevantLayerDescriptions ?: allLayerDescriptions)
        val availableLayerNames = getAvailableLayerNames(allLayerDescriptions)
        val historySection = if (stepHistory.isEmpty()) {
            "아직 수행한 작업 없음"
        } else {
            val lastIdx = stepHistory.lastIndex
            stepHistory.mapIndexed { idx, step ->
                val treeDesc = step.tree?.rootNodes?.joinToString(", ") { "${it.layerName}.${it.function}" } ?: "(알 수 없음)"
                if (idx == lastIdx) {
                    // 마지막 스텝: 전체 결과 — 다음 결정의 직접 근거
                    "✅ [완료] 스텝 ${step.stepNumber}: [$treeDesc]\n  → 결과: ${step.result}"
                } else {
                    // 과거 스텝: 슬롯 참조 — args에서 {{step:N}}으로 직접 참조 가능
                    "✅ [완료] 스텝 ${step.stepNumber}: [$treeDesc]\n  → [슬롯] {{step:${step.stepNumber}}}"
                }
            }.joinToString("\n")
        }

        val alreadyDoneNote = if (stepHistory.isNotEmpty()) {
            // args 포함 nodeKey는 {{parent}} 치환 후 대용량 내용이 포함될 수 있으므로
            // 프롬프트 표시용으로는 layerName.function 형태만 사용
            val doneFunctions = stepHistory
                .flatMap { it.successfulFunctions }
                .map { it.substringBefore("(") }
                .distinct()
                .joinToString(", ")
            if (doneFunctions.isNotEmpty()) {
                "\n완료된 함수: $doneFunctions\n   동일 함수를 rootNodes에 중복 추가하지 마세요."
            } else ""
        } else ""

        // ── Failure ledger — 같은 layer.function 이 2회 이상 ERROR 면 LLM 에게 강한 hint ──
        // 14B 모델이 같은 실수 반복하는 패턴 차단. 호출 카운트 + 마지막 에러 첫 줄 노출.
        val repeatedFailuresNote = if (stepHistory.size >= 2) {
            val failedFnToErrors = stepHistory
                .filter { it.result.startsWith("ERROR") || it.result.startsWith("FINISH BLOCKED") }
                .flatMap { step ->
                    val errFirstLine = step.result.lineSequence().firstOrNull()?.take(140) ?: ""
                    step.tree?.rootNodes?.map { "${it.layerName}.${it.function}" to errFirstLine }
                        ?: emptyList()
                }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size >= 2 }

            if (failedFnToErrors.isNotEmpty()) {
                val lines = failedFnToErrors.entries.joinToString("\n") { (fn, errs) ->
                    "  - $fn: ${errs.size}회 실패 — 최근 \"${errs.last()}\""
                }
                "\n⚠️ 반복 실패 감지 (같은 함수·반복 호출, 같은 에러 가능성 높음):\n$lines\n" +
                "   동일 layer.function 또는 동일 args 재시도 금지. **다른 접근**(다른 함수, 다른 args, 다른 분기) 시도하세요."
            } else ""
        } else ""

        val sessionContextSection = projectContext["_sessionContext"]
            ?.takeIf { it.isNotBlank() }
            ?.let { "\n$it\n" } ?: ""

        val projectContextSection = if (projectContext.isNotEmpty()) {
            val sb = StringBuilder("\n📂 프로젝트 컨텍스트 (반드시 준수):\n")
            projectContext["workingDirectory"]?.let { sb.append("  작업 디렉토리: $it\n") }
            projectContext.filterKeys { it != "workingDirectory" && it != "_sessionContext" }
                .forEach { (k, v) -> sb.append("  [$k] $v\n") }
            sb.toString()
        } else ""

        val aboxSection = if (relevantLayerDescriptions != null) {
            "\n상세 스펙 (관련 레이어):\n$abox"
        } else {
            ""
        }

        return """목표: "$query"
$sessionContextSection$projectContextSection
사용 가능한 레이어 (전체):
$tbox
$aboxSection
⛔ layerName은 반드시 위 목록에 있는 이름만 사용하세요: $availableLayerNames
   목록에 없는 이름은 절대 사용하지 마세요.

지금까지 수행한 작업:
$historySection
$alreadyDoneNote
$repeatedFailuresNote

데이터 흐름 규칙:
   - A의 결과가 B의 입력으로 필요하면 → B를 A의 children 배열 안에 넣고 B의 args에 "{{parent}}" 사용
   - "{{parent}}"는 직접 부모 노드의 결과만 가리킴. 형제(sibling) 노드 결과는 받을 수 없음.
   - 이 규칙은 깊이에 관계없이 동일하게 적용됨: 각 노드는 자신의 부모 결과만 {{parent}}로 받음.
     올바른: A(children:[B(children:[C(args:{x:"{{parent}}"})])]) ← C가 B 결과를, B가 A 결과를 받음
     잘못됨: A(children:[B, C]) ← C의 "{{parent}}"는 A 결과를 가리킴 (B 결과 아님)
   - 여러 노드의 결과를 하나의 노드에 합쳐야 할 때(fan-in): 각 노드에 "id" 필드를 지정하고,
     합치는 노드의 args에서 "{{nodeId:아이디}}" 플레이스홀더로 참조하세요.
     {{nodeId:X}}는 id가 X인 노드의 실행이 끝날 때까지 기다린 뒤 그 결과로 치환됩니다.
   - 과거 스텝 결과([슬롯] 표시)가 필요하면 args에서 "{{step:N}}" 플레이스홀더로 직접 참조하세요.
     시스템이 자동으로 해당 스텝의 저장된 결과로 치환합니다.
   - LLM이 파일/커밋 내용을 알고 있다고 가정하지 마세요. 반드시 해당 레이어 함수로 먼저 가져오세요.

데이터 연쇄 패턴:
   - 데이터를 가져온 뒤 처리하는 작업은 반드시 한 번의 execute_tree로 처리하세요
   - 데이터를 쓰는 노드는 반드시 해당 데이터를 생성한 노드의 children에 배치하세요 (형제 배치 금지)
   - 변환 결과를 저장해야 한다면 저장 노드도 같은 트리의 children에 포함하세요 (읽기→변환→저장을 하나의 트리로)
   - 잘못된 예: 스텝1 데이터만 읽음 → 스텝2 같은 데이터를 다시 읽으려 함 (중복 루프)

결정 규칙 (반드시 순서대로 확인):
⚠️ 절대 원칙: 코드·파일·시스템·데이터에 관한 질문은 반드시 레이어로 실제 데이터를 가져온 뒤 답해야 합니다.
   모델 학습 지식으로 코드 내용을 추측하거나 답변하는 것은 금지입니다. 반드시 읽어야 합니다.
1. 다음 중 하나에 해당하면 → finish 선택:
   - 목표 달성에 충분한 실제 결과가 히스토리에 있음 (히스토리에 실제 데이터가 있는 경우만)
   - 파일/코드/시스템과 무관한 순수 지식 질의 (예: 수학 계산, 언어/문법 질문, 개념 설명)
   → finish 선택 후 result에 한국어 최종 답변 작성.
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
{"action":"execute_tree","tree":{"rootNodes":[{"layerName":"레이어명","function":"함수명","args":{"파라미터":"값"},"parallel":false,"children":[]}]},"result":"","reasoning":"수행 이유"}

체인 — 앞 노드 결과({{parent}})가 뒤 노드 입력으로 필요한 경우, 뒤 노드를 앞 노드의 children에 넣는다. 깊이 제한 없음:
{"action":"execute_tree","tree":{"rootNodes":[{"layerName":"레이어A","function":"함수A","args":{},"parallel":false,"children":[{"layerName":"레이어B","function":"함수B","args":{"input":"{{parent}}"},"parallel":false,"children":[]}]}]},"result":"","reasoning":"A→B 체인"}

병렬 — 독립적인 작업 동시 실행:
{"action":"execute_tree","tree":{"rootNodes":[{"layerName":"레이어A","function":"함수A","args":{},"parallel":true,"children":[]},{"layerName":"레이어B","function":"함수B","args":{},"parallel":true,"children":[]}]},"result":"","reasoning":"동시 실행"}

fan-in — 두 노드(A·B)의 결과를 C 하나에 합칠 때: A에 "id" 부여, B의 children에 C를 두고 C의 args에서 {{parent}}(=B 결과)와 {{nodeId:nodeA}}(=A 결과)를 함께 사용:
{"action":"execute_tree","tree":{"rootNodes":[{"id":"nodeA","layerName":"레이어A","function":"함수A","args":{},"parallel":true,"children":[]},{"layerName":"레이어B","function":"함수B","args":{},"parallel":true,"children":[{"layerName":"레이어C","function":"함수C","args":{"input":"A결과: {{nodeId:nodeA}}\n\nB결과: {{parent}}"},"parallel":false,"children":[]}]}]},"result":"","reasoning":"A·B 병렬 후 C에서 합산"}

목표 완전 달성 시:
{"action":"finish","result":"(사용자에게 전달할 최종 답변)","reasoning":"완료 이유"}

사용자에게 추가 정보가 필요할 때:
{"action":"ask","question":"(사용자에게 할 구체적인 질문)","result":"","reasoning":"질문 이유"}""".trimIndent()
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

    /**
     * finish 직전 결과가 원래 query 충족하는지 판정용 프롬프트.
     * - ERROR 결과는 거의 항상 satisfied=false
     * - 부분 답변은 missing 에 부족한 부분을 명시
     */
    fun buildFinishJudgePrompt(
        query: String,
        candidateResult: String,
        stepHistory: List<ReActStep>
    ): String {
        val historySummary = if (stepHistory.isEmpty()) "(이전 스텝 없음)"
        else stepHistory.takeLast(5).joinToString("\n") { s ->
            val tree = s.tree?.rootNodes?.joinToString(", ") { "${it.layerName}.${it.function}" } ?: "-"
            "스텝 ${s.stepNumber} [$tree] → ${s.result.take(160)}"
        }
        return """원래 목표(query): "$query"

후보 최종 결과:
$candidateResult

최근 실행 히스토리:
$historySummary

판정 기준:
- 위 후보 결과가 원래 query 의 요구를 실제로 충족했는가?
- 결과가 "ERROR" 로 시작하거나 실패·차단 메시지면 satisfied=false
- 결과가 비어있거나 골과 무관한 일반 멘트면 satisfied=false
- 부분만 달성됐다면 satisfied=false 로 두고 missing 에 빠진 부분을 한 문장으로 명시
- 명확히 충족됐다면 satisfied=true, missing="" (빈 문자열)
- reasoning 은 한 문장으로 판정 근거

반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요:
{"satisfied": true, "missing": "", "reasoning": "충족 이유"}
또는
{"satisfied": false, "missing": "부족한 부분", "reasoning": "부족 이유"}""".trimIndent()
    }
}
