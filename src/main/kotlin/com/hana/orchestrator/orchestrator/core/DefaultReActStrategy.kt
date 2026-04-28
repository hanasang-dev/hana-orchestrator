package com.hana.orchestrator.orchestrator.core

import com.hana.orchestrator.data.mapper.ExecutionTreeMapper
import com.hana.orchestrator.data.model.response.ExecutionNodeResponse
import com.hana.orchestrator.data.model.response.ExecutionTreeResponse
import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.llm.ReActStep
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.llm.useSuspend
import com.hana.orchestrator.presentation.mapper.ExecutionTreeMapper as PresentationMapper
import com.hana.orchestrator.presentation.model.execution.ExecutionPhase
import com.hana.orchestrator.presentation.model.execution.ExecutionTreeNodeResponse
import com.hana.orchestrator.orchestrator.ExecutionHistoryManager
import com.hana.orchestrator.orchestrator.ExecutionStatePublisher
import com.hana.orchestrator.orchestrator.createOrchestratorLogger

/**
 * 표준 LLM-guided ReAct 루프 전략
 *
 * 각 스텝에서 LLM이 미니트리를 결정 → TreeExecutor가 실행 → 결과 누적
 * action:
 * - execute_tree : LLM이 미니트리 생성 → TreeExecutor.executeTree() 위임 (플레이스홀더/병렬 지원)
 * - finish       : 루프 종료 및 최종 결과 반환
 *
 * 종료 조건 (우선순위 순):
 * 1. LLM이 finish 반환 → 정상 완료
 * 2. 중복 루프 감지 → 이미 완료한 작업을 또 제안
 * 3. 에러 후 동일 트리 반복 → 에러 재시도 루프
 * 4. 연속 에러 N회 → 근본적인 실패 상태
 * 5. absoluteMaxSteps 도달 → runaway 안전망 (마지막 수단)
 */
class DefaultReActStrategy(
    private val layerManager: LayerManager,
    private val historyManager: ExecutionHistoryManager,
    private val statePublisher: ExecutionStatePublisher,
    private val modelSelectionStrategy: ModelSelectionStrategy,
    private val treeExecutor: TreeExecutor,
    private val clarificationGate: com.hana.orchestrator.orchestrator.ClarificationGate? = null
) : ReActStrategy {

    /** runaway 방지용 절대 안전망 — 정상 작업에서는 도달하지 않아야 함 */
    private val absoluteMaxSteps = 200
    /** 이 횟수만큼 연속으로 ERROR 결과가 나오면 복구 불가 상태로 판단하고 중단 */
    private val maxConsecutiveErrors = 3
    private val logger = createOrchestratorLogger(DefaultReActStrategy::class.java, historyManager)

    /** 로그 추가 + WebSocket 즉시 broadcast */
    private fun logAndEmit(message: String) {
        historyManager.addLogToCurrent(message)
        historyManager.getCurrentExecution()?.let { statePublisher.emitExecutionUpdateAsync(it) }
    }

    // ── 인수(args) 포함 노드 식별 키 생성 헬퍼 ──────────────────────────────

    /** 도메인 노드 식별 키 (레이어.함수(args)) — 중복 감지용 */
    private fun nodeKey(layerName: String, function: String, args: Map<String, Any>): String {
        val argsStr = args.entries.sortedBy { it.key }
            .joinToString(",") { (k, v) -> "$k=${anyToCanonical(v)}" }
        return "$layerName.$function($argsStr)"
    }

    private fun anyToCanonical(v: Any?): String = when (v) {
        is Map<*, *> -> "{${v.entries.sortedBy { it.key.toString() }
            .joinToString(",") { (k, vv) -> "$k=${anyToCanonical(vv)}" }}}"
        is List<*> -> "[${v.joinToString(",") { anyToCanonical(it) }}]"
        else -> v?.toString() ?: ""
    }

    /** LLM 응답 노드 식별 키 (JsonElement args 포함) — 중복 감지용 */
    private fun llmNodeKey(node: ExecutionNodeResponse): String {
        val argsStr = (node.args as? JsonObject)
            ?.entries?.sortedBy { it.key }
            ?.joinToString(",") { (k, v) -> "$k=${jsonToCanonical(v)}" } ?: ""
        return "${node.layerName}.${node.function}($argsStr)"
    }

    private fun jsonToCanonical(e: JsonElement): String = when (e) {
        is JsonObject -> "{${e.entries.sortedBy { it.key }
            .joinToString(",") { (k, v) -> "$k=${jsonToCanonical(v)}" }}}"
        is JsonArray -> "[${e.joinToString(",") { jsonToCanonical(it) }}]"
        is JsonPrimitive -> e.content  // JsonNull도 JsonPrimitive의 서브클래스
    }

    /** 프레젠테이션 트리 노드에서 args 포함 식별 키 목록 수집 (루트 + children 재귀) */
    private fun collectAllNodeFunctions(nodes: List<ExecutionTreeNodeResponse>?): List<String> {
        if (nodes == null) return emptyList()
        return nodes.flatMap { node ->
            val argsStr = node.args.entries.sortedBy { it.key }
                .joinToString(",") { (k, v) -> "$k=$v" }
            listOf("${node.layerName}.${node.function}($argsStr)") + collectAllNodeFunctions(node.children)
        }
    }

    /** LLM 데이터 트리 노드에서 args 포함 식별 키 목록 수집 (루트 + children 재귀) */
    private fun collectLLMNodeFunctions(nodes: List<ExecutionNodeResponse>): List<String> {
        return nodes.flatMap { node ->
            listOf(llmNodeKey(node)) + collectLLMNodeFunctions(node.children)
        }
    }

    /**
     * 스텝 결과에서 "다음 단계:" 힌트 파싱
     * 형식: layerName="X", function="Y"
     * 이미 실행된 함수는 제외 (doneFunctions)
     */
    private fun parseNextStepHint(result: String, doneFunctions: Set<String>): Pair<String, String>? {
        val match = Regex("""다음 단계: layerName="([^"]+)", function="([^"]+)"""").find(result) ?: return null
        val layerName = match.groupValues[1]
        val function = match.groupValues[2]
        val key = "$layerName.$function"
        return if (key !in doneFunctions) Pair(layerName, function) else null
    }

    /**
     * 스텝 결과에서 "[필수후속]" 힌트 파싱 → 미실행 필수 후속 단계 반환
     * 형식: [필수후속] ... layerName.function(key="value") ...
     * 이미 실행된 함수는 제외 (doneFunctions)
     */
    private fun parseRequiredFollowUpHint(
        result: String,
        doneFunctions: Set<String>
    ): Triple<String, String, Map<String, String>>? {
        if (!result.contains("[필수후속]")) return null
        val match = Regex("""\[필수후속\][^\n]*?(\w+)\.(\w+)\(([^)]*)\)""").find(result) ?: return null
        val layerName = match.groupValues[1]
        val function = match.groupValues[2]
        val argsRaw = match.groupValues[3]
        val fnKey = "$layerName.$function"
        if (fnKey in doneFunctions) return null
        val args = mutableMapOf<String, String>()
        Regex("""(\w+)="([^"]*)"""").findAll(argsRaw).forEach { m ->
            args[m.groupValues[1]] = m.groupValues[2]
        }
        return Triple(layerName, function, args)
    }

    /**
     * ReAct 루프 실행.
     * 종료는 의미적 감지에 맡기고, absoluteMaxSteps는 runaway 안전망으로만 사용.
     */
    override suspend fun execute(
        query: String,
        executionId: String,
        startTime: Long,
        projectContext: Map<String, String>
    ): ExecutionResult {
        val stepHistory = mutableListOf<ReActStep>()
        var step = 0
        var consecutiveErrors = 0

        while (true) {
            // 매 스텝마다 갱신 — hotLoad로 추가된 레이어도 즉시 반영
            val allDescriptions = layerManager.getAllLayerDescriptions()
            step++

            // ── runaway 안전망 (의미적 감지가 모두 빗나간 경우에만 도달) ──
            if (step > absoluteMaxSteps) {
                val finalResult = stepHistory.lastOrNull()?.result ?: "최대 스텝 도달"
                logger.warn("⚠️ [ReAct] 절대 최대 스텝($absoluteMaxSteps) 도달: 강제 종료")
                return ExecutionResult(
                    result = finalResult,
                    error = "절대 최대 ReAct 스텝($absoluteMaxSteps) 도달",
                    stepHistory = stepHistory.toList()
                )
            }

            // ── 연속 에러 감지: 복구 불가 상태 판단 ──
            if (consecutiveErrors >= maxConsecutiveErrors) {
                val finalResult = stepHistory
                    .lastOrNull { !it.result.startsWith("ERROR") }
                    ?.result ?: "연속 실패로 중단"
                logger.warn("⚠️ [ReAct] 연속 에러 ${maxConsecutiveErrors}회 → 강제 종료")
                return ExecutionResult(
                    result = finalResult,
                    error = "연속 에러 ${maxConsecutiveErrors}회로 중단",
                    stepHistory = stepHistory.toList()
                )
            }

            // 진행률: 스텝마다 5%씩 올라가다 85%에서 수렴 (작업량 불확정이므로 점근)
            val progressPct = minOf(15 + step * 5, 85)
            logger.info("🔄 [ReAct] 스텝 #$step 시작")
            logAndEmit("🔄 스텝 #$step — LLM 결정 요청 중...")
            statePublisher.emitProgress(
                executionId, ExecutionPhase.TREE_EXECUTION,
                "🤔 스텝 #$step 결정 중...", progressPct, System.currentTimeMillis() - startTime
            )

            // 직전 스텝 결과의 "다음 단계:" 힌트 → LLM 없이 자동 실행
            val doneFunctionNames = stepHistory.flatMap { it.successfulFunctions }
                .map { it.substringBefore("(") }.toSet()
            val autoStep = stepHistory.lastOrNull()?.result?.let { parseNextStepHint(it, doneFunctionNames) }
            if (autoStep != null) {
                val (layerName, function) = autoStep
                logger.info("🔜 [ReAct] '다음 단계' 자동 실행: $layerName.$function")
                val autoNode = ExecutionNodeResponse(layerName, function, buildJsonObject {})
                val autoLlmTree = ExecutionTreeResponse(rootNodes = listOf(autoNode))
                val autoDomainTree = try {
                    ExecutionTreeMapper.toExecutionTree(autoLlmTree)
                } catch (e: Exception) {
                    logger.warn("⚠️ [ReAct] '다음 단계' 트리 변환 실패: ${e.message}")
                    stepHistory.add(ReActStep(step, "auto:$layerName.$function", null, "ERROR(auto-next): ${e.message}"))
                    consecutiveErrors++
                    continue
                }
                val autoExecResult = try {
                    treeExecutor.executeTree(autoDomainTree, historyManager.getCurrentExecution()!!)
                } catch (e: Exception) {
                    logger.warn("⚠️ [ReAct] '다음 단계' 실행 실패: ${e.message}")
                    null
                }
                val autoResult = autoExecResult?.result ?: "ERROR(auto-next): 실행 실패"
                val autoSuccessful = autoExecResult?.context?.completedNodes
                    ?.filter { it.isSuccess && !it.result.orEmpty().startsWith("ERROR") }
                    ?.map { nodeKey(it.node.layerName, it.node.function, it.node.args) }
                    ?: emptyList()
                logger.info("📋 [ReAct] 스텝 #$step (자동) 결과: ${autoResult.take(120)}")
                val autoPresentation = with(PresentationMapper) { autoDomainTree.toResponse() }
                stepHistory.add(ReActStep(step, "자동 실행: $layerName.$function", autoPresentation, autoResult, autoSuccessful))
                if (autoResult.startsWith("ERROR")) consecutiveErrors++ else consecutiveErrors = 0
                continue
            }

            // 히스토리 전체에서 [필수후속] 힌트 파싱 → 미실행 필수 후속 단계 자동 실행
            val requiredFollowUp = stepHistory
                .asSequence()
                .mapNotNull { parseRequiredFollowUpHint(it.result, doneFunctionNames) }
                .firstOrNull()
            if (requiredFollowUp != null) {
                val (rfLayerName, rfFunction, rfArgs) = requiredFollowUp
                logger.info("🔜 [ReAct] '[필수후속]' 자동 실행: $rfLayerName.$rfFunction($rfArgs)")
                val rfArgsJson = buildJsonObject { rfArgs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
                val rfNode = ExecutionNodeResponse(rfLayerName, rfFunction, rfArgsJson)
                val rfLlmTree = ExecutionTreeResponse(rootNodes = listOf(rfNode))
                val rfDomainTree = try {
                    ExecutionTreeMapper.toExecutionTree(rfLlmTree)
                } catch (e: Exception) {
                    logger.warn("⚠️ [ReAct] '[필수후속]' 트리 변환 실패: ${e.message}")
                    stepHistory.add(ReActStep(step, "auto:[필수후속]:$rfLayerName.$rfFunction", null, "ERROR(required-followup): ${e.message}"))
                    consecutiveErrors++
                    continue
                }
                val rfExecResult = try {
                    treeExecutor.executeTree(rfDomainTree, historyManager.getCurrentExecution()!!)
                } catch (e: Exception) {
                    logger.warn("⚠️ [ReAct] '[필수후속]' 실행 실패: ${e.message}")
                    null
                }
                val rfResult = rfExecResult?.result ?: "ERROR(required-followup): 실행 실패"
                val rfSuccessful = rfExecResult?.context?.completedNodes
                    ?.filter { it.isSuccess && !it.result.orEmpty().startsWith("ERROR") }
                    ?.map { nodeKey(it.node.layerName, it.node.function, it.node.args) }
                    ?: emptyList()
                logger.info("📋 [ReAct] 스텝 #$step ([필수후속] 자동) 결과: ${rfResult.take(120)}")
                val rfPresentation = with(PresentationMapper) { rfDomainTree.toResponse() }
                stepHistory.add(ReActStep(step, "자동 실행([필수후속]): $rfLayerName.$rfFunction", rfPresentation, rfResult, rfSuccessful))
                if (rfResult.startsWith("ERROR")) consecutiveErrors++ else consecutiveErrors = 0
                continue
            }

            // LLM이 다음 액션 결정
            val decision = try {
                modelSelectionStrategy.selectClientForReActDecision()
                    .useSuspend { client -> client.decideNextAction(query, stepHistory, allDescriptions, projectContext) }
            } catch (e: Exception) {
                logger.error("❌ [ReAct] LLM 결정 실패: ${e.message}")
                val fallback = stepHistory.lastOrNull()?.result ?: ""
                return ExecutionResult(
                    result = fallback,
                    error = "ReAct 결정 실패: ${e.message}",
                    stepHistory = stepHistory.toList()
                )
            }

            logger.info("🤔 [ReAct] 스텝 #$step 결정: action=${decision.action}, reasoning=${decision.reasoning.take(80)}")
            logAndEmit("🤔 #$step → ${decision.action}: ${decision.reasoning.take(80).replace('\n', ' ')}")

            when (decision.action) {
                "finish" -> {
                    val lastSuccessResult = stepHistory.lastOrNull { s ->
                        !s.result.startsWith("ERROR") && !s.result.startsWith("Unknown function")
                    }?.result
                    val finalResult = decision.result.ifEmpty { null }
                        ?: lastSuccessResult
                        ?: "작업 완료"
                    logger.info("✅ [ReAct] 완료 (${step}스텝): ${finalResult.take(100)}")
                    logAndEmit("✅ 완료 (${step}스텝)")
                    statePublisher.emitProgress(
                        executionId, ExecutionPhase.COMPLETED, "✅ 완료", 100,
                        System.currentTimeMillis() - startTime
                    )
                    return ExecutionResult(result = finalResult, stepHistory = stepHistory.toList())
                }

                "execute_tree" -> {
                    val llmTree = decision.tree
                    if (llmTree == null) {
                        logger.warn("⚠️ [ReAct] execute_tree: tree is null, 스킵")
                        stepHistory.add(ReActStep(step, decision.reasoning, null, "ERROR(tree-null): tree is null"))
                        consecutiveErrors++
                        continue
                    }

                    // 중복 루프 감지 1: 제안된 함수들이 전부 이미 성공 완료된 경우 강제 finish
                    if (stepHistory.isNotEmpty()) {
                        val doneFunctions = stepHistory.flatMap { it.successfulFunctions }.toSet()
                        val proposed = collectLLMNodeFunctions(llmTree.getActualRootNodes()).toSet()
                        if (proposed.isNotEmpty() && doneFunctions.containsAll(proposed)) {
                            val finalResult = stepHistory
                                .lastOrNull { !it.result.startsWith("ERROR") && !it.result.startsWith("Unknown function") && !it.result.startsWith("정보 부족") }
                                ?.result ?: stepHistory.lastOrNull()?.result ?: "작업 완료"
                            logger.info("🔁 [ReAct] 중복 루프 감지 → 강제 finish (이미 완료: $proposed)")
                            return ExecutionResult(result = finalResult, stepHistory = stepHistory.toList())
                        }
                    }

                    // 중복 루프 감지 2: 직전 스텝이 에러인데 동일 트리를 또 제안하는 경우 강제 finish
                    val lastStep = stepHistory.lastOrNull()
                    if (lastStep?.result?.startsWith("ERROR") == true) {
                        val lastFunctions = lastStep.tree?.rootNodes?.let { collectAllNodeFunctions(it) }?.toSet()
                        val proposed = collectLLMNodeFunctions(llmTree.getActualRootNodes()).toSet()
                        if (lastFunctions != null && lastFunctions == proposed) {
                            val finalResult = stepHistory
                                .lastOrNull { !it.result.startsWith("ERROR") }
                                ?.result ?: "실행 실패"
                            logger.info("🔁 [ReAct] 에러 후 동일 액션 반복 감지 → 강제 finish (반복: $proposed)")
                            return ExecutionResult(result = finalResult, error = "에러 후 동일 액션 반복으로 중단", stepHistory = stepHistory.toList())
                        }
                    }

                    // LLM 트리(data layer) → 도메인 트리 변환
                    val domainTree = try {
                        ExecutionTreeMapper.toExecutionTree(llmTree)
                    } catch (e: Exception) {
                        logger.warn("⚠️ [ReAct] 미니트리 변환 실패: ${e.message}")
                        stepHistory.add(ReActStep(step, decision.reasoning, null, "ERROR(tree-convert): ${e.message}"))
                        consecutiveErrors++
                        continue
                    }

                    val treeDesc = domainTree.rootNodes.joinToString(", ") { "${it.layerName}.${it.function}" }
                    logger.info("🌳 [ReAct] 스텝 #$step 미니트리 실행: [$treeDesc]")
                    logAndEmit("🌳 실행: [$treeDesc]")

                    val treeExecResult = try {
                        val currentHistory = historyManager.getCurrentExecution()!!
                        treeExecutor.executeTree(domainTree, currentHistory)
                    } catch (e: Exception) {
                        val errMsg = "ERROR(mini-tree): ${e.message}"
                        logger.warn("⚠️ [ReAct] 스텝 #$step 미니트리 오류: $errMsg")
                        null
                    }
                    val stepResult = treeExecResult?.result ?: "ERROR(mini-tree): 실행 실패"
                    val successfulFunctions = treeExecResult?.context?.completedNodes
                        ?.filter { it.isSuccess && !it.result.orEmpty().startsWith("ERROR") }
                        ?.map { nodeKey(it.node.layerName, it.node.function, it.node.args) }
                        ?: emptyList()

                    logger.info("📋 [ReAct] 스텝 #$step 결과: ${stepResult.take(120)}")
                    logAndEmit("📋 #$step 결과: ${stepResult.take(80).replace('\n', ' ')}")

                    val presentationTree = with(PresentationMapper) { domainTree.toResponse() }
                    stepHistory.add(ReActStep(step, decision.reasoning, presentationTree, stepResult, successfulFunctions))
                    if (stepResult.startsWith("ERROR")) consecutiveErrors++ else consecutiveErrors = 0
                }

                "ask" -> {
                    val question = decision.question.ifEmpty { decision.reasoning }
                    if (clarificationGate == null) {
                        logger.warn("⚠️ [ReAct] ask 요청이지만 ClarificationGate 없음: $question")
                        stepHistory.add(ReActStep(step, "사용자 질문(미처리): $question", null, "[정보 부족] $question"))
                        consecutiveErrors = 0
                    } else {
                        logger.info("❓ [ReAct] 사용자에게 질문: $question")
                        logAndEmit("❓ 질문: $question")
                        statePublisher.emitProgress(executionId, ExecutionPhase.TREE_EXECUTION, "❓ 사용자 답변 대기 중...", progressPct, System.currentTimeMillis() - startTime)
                        val answer = clarificationGate.requestClarification(question)
                        logger.info("💬 [ReAct] 사용자 답변: ${answer.take(80)}")
                        logAndEmit("💬 답변: ${answer.take(60)}")
                        stepHistory.add(ReActStep(step, "사용자 질문: $question", null, "[사용자 답변] $answer"))
                        consecutiveErrors = 0
                    }
                }

                else -> {
                    logger.warn("⚠️ [ReAct] 알 수 없는 action: ${decision.action}")
                    return ExecutionResult(
                        result = stepHistory.lastOrNull()?.result ?: "",
                        error = "알 수 없는 action: ${decision.action}"
                    )
                }
            }
        }
    }
}
