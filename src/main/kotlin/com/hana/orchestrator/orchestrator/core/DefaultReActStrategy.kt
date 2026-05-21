package com.hana.orchestrator.orchestrator.core

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
 * 3. 연속 에러 N회 → 근본적인 실패 상태 (에러 후 재시도 루프도 이로 처리)
 * 4. absoluteMaxSteps 도달 → runaway 안전망 (마지막 수단)
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
    /** finish 가드 — 이 횟수까지만 LLM 충족 판정으로 차단. 초과 시 정상 finish 허용 (무한루프 방지) */
    private val maxFinishBlocks = 2
    /**
     * 전체 stepHistory 추정 문자 수가 이 값 초과 시 오래된 스텝을 요약 압축.
     * 4자 ≈ 1토큰 기준, 모델 contextLength(32768토큰)의 약 40% = 13,000자로 여유 확보.
     * 이 값은 contextLength에 비례해야 하므로, 모델 변경 시 함께 조정.
     */
    private val historyCompressThresholdChars = 13_000
    private val logger = createOrchestratorLogger(DefaultReActStrategy::class.java, historyManager)

    /**
     * stepHistory 총 문자 수 추정 (토큰 오버플로 감지용)
     */
    private fun estimateHistoryChars(stepHistory: List<ReActStep>): Int =
        stepHistory.sumOf { s ->
            (s.tree?.rootNodes?.joinToString(", ") { "${it.layerName}.${it.function}" }?.length ?: 0) +
            s.result.length + s.reasoning.length + 40
        }

    /**
     * 오래된 스텝을 LLM 요약으로 압축.
     * 최신 keepRecent 개 스텝은 원본 유지, 나머지를 단일 요약 스텝으로 대체.
     * best-effort: 실패 시 원본 유지.
     */
    private suspend fun compressHistory(
        stepHistory: MutableList<ReActStep>,
        keepRecent: Int = 5
    ) {
        if (stepHistory.size <= keepRecent) return
        val toCompress = stepHistory.dropLast(keepRecent)
        val kept = stepHistory.takeLast(keepRecent)
        try {
            val client = modelSelectionStrategy.selectClientForSummarizeHistory()
            val summary = client.useSuspend { it.summarizeHistory(toCompress) }
            val summaryStep = ReActStep(
                stepNumber = toCompress.first().stepNumber,
                reasoning = "[압축 요약] 스텝 ${toCompress.first().stepNumber}~${toCompress.last().stepNumber}",
                tree = null,
                result = summary,
                successfulFunctions = toCompress.flatMap { it.successfulFunctions }.distinct()
            )
            stepHistory.clear()
            stepHistory.add(summaryStep)
            stepHistory.addAll(kept)
            logger.info("🗜️ [ReAct] 히스토리 압축: ${toCompress.size}개 스텝 → 요약 1개 + 최근 ${kept.size}개 유지")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 코루틴 취소 전파
        } catch (e: Exception) {
            logger.warn("⚠️ [ReAct] 히스토리 압축 실패 (원본 유지): ${e.message}")
        }
    }

    /**
     * 스텝 결과가 크면 context store에 저장. 프롬프트 빌더가 키 참조로 렌더링함.
     * 실패해도 루프에 영향 없음 (best-effort).
     */
    private suspend fun storeStepResult(executionId: String, stepNumber: Int, result: String) {
        try {
            layerManager.executeOnLayerInternal(
                "context", "put",
                mapOf("key" to "step_${executionId}_${stepNumber}_result", "value" to result)
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("⚠️ [ReAct] context store 저장 실패 (무시): ${e.message}")
        }
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

    /** LLM 데이터 트리 노드에서 args 포함 식별 키 목록 수집 (루트 + children 재귀) */
    private fun collectLLMNodeFunctions(nodes: List<ExecutionNodeResponse>): List<String> {
        return nodes.flatMap { node ->
            listOf(llmNodeKey(node)) + collectLLMNodeFunctions(node.children)
        }
    }

    /**
     * ReAct 루프 실행.
     * 종료는 의미적 감지에 맡기고, absoluteMaxSteps는 runaway 안전망으로만 사용.
     *
     * HTN B2: primary entry — Task 기반. CompoundTask 의 query 를 LLM 프롬프트로 사용.
     * PrimitiveTask 단독으로 들어오면 description 을 query 로 취급 (예외 경로, 일반적이지 X).
     */
    override suspend fun execute(
        task: com.hana.orchestrator.orchestrator.core.task.Task,
        executionId: String,
        startTime: Long,
        projectContext: Map<String, String>,
        isScheduled: Boolean
    ): ExecutionResult {
        val query: String = when (task) {
            is com.hana.orchestrator.orchestrator.core.task.CompoundTask -> task.query
            is com.hana.orchestrator.orchestrator.core.task.PrimitiveTask -> task.description
        }
        val stepHistory = mutableListOf<ReActStep>()
        var step = 0
        var consecutiveErrors = 0

        // 세션 컨텍스트 조회 (best-effort — 없어도 동작)
        val sessionContext = try {
            layerManager.executeOnLayerInternal("session", "currentContext")
                .takeIf { it.isNotBlank() && !it.startsWith("ERROR") } ?: ""
        } catch (e: Exception) { "" }
        val enrichedProjectContext = if (sessionContext.isNotBlank())
            projectContext + mapOf("_sessionContext" to sessionContext)
        else projectContext
        // 필수후속 시도 추적 — REJECTED/실패 포함. doneFunctionNames에 합산하여 무한 재시도 방지
        val attemptedFollowUps = mutableSetOf<String>()
        // 병렬 요청 시 historyManager 공유 상태 충돌 방지 — 실행 시작 시 한 번만 캡처
        val currentExecution = historyManager.getCurrentExecution(executionId)
            ?: return ExecutionResult(
                result = "",
                error = "No active execution context — concurrent request may have overwritten state",
                stepHistory = emptyList()
            )

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
            historyManager.addLogTo(executionId, "🔄 ReAct 스텝 #$step")
            statePublisher.emitProgress(
                executionId, ExecutionPhase.TREE_EXECUTION,
                "🤔 스텝 #$step 결정 중...", progressPct, System.currentTimeMillis() - startTime
            )

            // @RequiresSelfAction 어노테이션 기반 후속 액션 감지 — 도메인 불변조건 보호
            // 성공한 함수 중 @RequiresSelfAction 어노테이션이 있고 아직 후속 미실행인 경우 자동 실행
            val doneFunctionNames = stepHistory.flatMap { it.successfulFunctions }
                .map { it.substringBefore("(") }.toSet() + attemptedFollowUps
            val pendingSelfAction = stepHistory
                .asSequence()
                .flatMap { it.successfulFunctions.asSequence() }
                .mapNotNull { funcKey ->
                    val layerName = funcKey.substringBefore(".")
                    val functionName = funcKey.substringAfter(".").substringBefore("(")
                    val requiredFn = layerManager.findRequiredSelfAction(layerName, functionName)
                        ?: return@mapNotNull null
                    val selfActionKey = "$layerName.$requiredFn"
                    if (selfActionKey in doneFunctionNames) return@mapNotNull null
                    Pair(layerName, requiredFn)
                }
                .firstOrNull()
            if (pendingSelfAction != null) {
                val (saLayerName, saFunction) = pendingSelfAction
                attemptedFollowUps.add("$saLayerName.$saFunction")
                logger.info("🔜 [ReAct] @RequiresSelfAction 자동 실행: $saLayerName.$saFunction")
                val saTask = com.hana.orchestrator.orchestrator.core.task.CompoundTask(
                    description = "auto-selfaction $saLayerName.$saFunction",
                    query = query,
                    subtasks = listOf(
                        com.hana.orchestrator.orchestrator.core.task.PrimitiveTask(
                            description = "$saLayerName.$saFunction",
                            layerName = saLayerName,
                            function = saFunction
                        )
                    )
                )
                val saDomainTree = try {
                    com.hana.orchestrator.orchestrator.core.task.TaskTreeMapper.toExecutionTree(saTask)
                } catch (e: Exception) {
                    logger.warn("⚠️ [ReAct] @RequiresSelfAction 트리 변환 실패: ${e.message}")
                    stepHistory.add(ReActStep(step, "auto-selfaction:$saLayerName.$saFunction", null, "ERROR(self-action): ${e.message}"))
                    consecutiveErrors++
                    continue
                }
                val saExecResult = try {
                    treeExecutor.executeTask(saTask, currentExecution)
                } catch (e: Exception) {
                    logger.warn("⚠️ [ReAct] @RequiresSelfAction 실행 실패: ${e.message}")
                    null
                }
                val saResult = saExecResult?.result ?: "ERROR(self-action): 실행 실패"
                val saSuccessful = saExecResult?.context?.completedNodes
                    ?.filter { it.isSuccess }
                    ?.map { nodeKey(it.node.layerName, it.node.function, it.node.args) }
                    ?: emptyList()
                logger.info("📋 [ReAct] 스텝 #$step (@RequiresSelfAction) 결과: ${saResult.take(120)}")
                storeStepResult(executionId, step, saResult)
                val saPresentation = with(PresentationMapper) { saDomainTree.toResponse() }
                stepHistory.add(ReActStep(step, "자동 실행(@RequiresSelfAction): $saLayerName.$saFunction", saPresentation, saResult, saSuccessful))
                if (saExecResult == null || saExecResult.context?.failedNodes?.isNotEmpty() == true) consecutiveErrors++ else consecutiveErrors = 0
                continue
            }

            // 컨텍스트 한계 근접 시 오래된 스텝 압축 (LLM 호출 전)
            if (estimateHistoryChars(stepHistory) > historyCompressThresholdChars) {
                logger.info("🗜️ [ReAct] 히스토리 압축 트리거 (추정 ${estimateHistoryChars(stepHistory)}자 > ${historyCompressThresholdChars}자)")
                compressHistory(stepHistory)
            }

            // LLM이 다음 액션 결정
            val decision = try {
                modelSelectionStrategy.selectClientForReActDecision()
                    .useSuspend { client ->
                        client.decideNextAction(
                            query, stepHistory, allDescriptions, enrichedProjectContext,
                            executionId = executionId, stepNumber = step
                        )
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // 코루틴 취소 전파 — Orchestrator.catch(CancellationException)이 처리
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

            when (decision.action) {
                "finish" -> {
                    // step 0에서 result가 비어있으면 거부 (아무것도 안 한 채 빈 답변)
                    // result가 있으면 허용 — LLM이 프롬프트 컨텍스트만으로 충분히 답변 가능한 경우
                    if (stepHistory.isEmpty() && decision.result.isBlank()) {
                        logger.warn("⚠️ [ReAct] step 0 finish 거부 — 빈 result")
                        stepHistory.add(ReActStep(step, decision.reasoning, null,
                            "답변 내용이 없습니다. 필요한 레이어를 실행하거나 result에 답변을 포함해 finish하세요."))
                        continue
                    }
                    val lastSuccessResult = stepHistory.lastOrNull { s ->
                        !s.result.startsWith("ERROR") && !s.result.startsWith("Unknown function")
                    }?.result
                    val finalResult = decision.result.ifEmpty { null }
                        ?: lastSuccessResult
                        ?: "작업 완료"

                    // ── finish 가드: 원래 query 충족 여부 LLM 판정 ─────────────────────
                    // 차단 횟수 한도 도달 전까지 satisfied=false 면 재시도 강제
                    val finishBlocks = stepHistory.count { it.reasoning.startsWith("[finish-blocked") }
                    if (finishBlocks < maxFinishBlocks) {
                        val outcome = try {
                            modelSelectionStrategy.selectClientForJudgeFinish()
                                .useSuspend { it.judgeFinish(query, finalResult, stepHistory.toList()) }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn("⚠️ [ReAct] finish 판정 실패 (통과 처리): ${e.message}")
                            null
                        }
                        if (outcome != null && !outcome.satisfied) {
                            val blockNo = finishBlocks + 1
                            logger.info("🚧 [ReAct] finish 차단 #$blockNo — missing=${outcome.missing.take(80)}")
                            historyManager.addLogTo(executionId, "🚧 finish 차단 #$blockNo — ${outcome.missing.take(60)}")
                            stepHistory.add(ReActStep(
                                step,
                                "[finish-blocked #$blockNo] ${outcome.reasoning}",
                                null,
                                "FINISH BLOCKED: 원래 query 미충족. 부족: ${outcome.missing}. 다른 접근으로 재시도하거나 정당화 후 finish."
                            ))
                            continue
                        }
                    }

                    logger.info("✅ [ReAct] 완료 (${step}스텝): ${finalResult.take(100)}")
                    historyManager.addLogTo(executionId, "✅ ReAct 완료 (${step}스텝)")
                    statePublisher.emitProgress(
                        executionId, ExecutionPhase.COMPLETED, "✅ 완료", 100,
                        System.currentTimeMillis() - startTime
                    )
                    return ExecutionResult(result = finalResult, stepHistory = stepHistory.toList())
                }

                "execute_tree" -> {
                    val llmTree = decision.tree
                    if (llmTree == null || llmTree.getActualRootNodes().isEmpty()) {
                        logger.warn("⚠️ [ReAct] execute_tree: tree is null or empty, 스킵")
                        stepHistory.add(ReActStep(step, decision.reasoning, null,
                            "[재시도 필요] execute_tree를 선택했으나 tree.rootNodes가 비어 있습니다. " +
                            "layerName(레이어명), function(함수명), args(파라미터)를 반드시 채워서 다시 시도하세요. " +
                            "사용 가능한 레이어와 함수 목록을 확인하고 rootNodes에 최소 1개 이상의 노드를 포함하세요."))
                        // consecutiveErrors 증가 안 함 — 포맷 오류는 실제 실행 에러가 아님
                        continue
                    }

                    // 중복 루프 감지: 직전 스텝과 동일한 루트 노드 제안 시 강제 finish
                    // 누적 전체 history 비교는 오탐률 높음 — 직전 스텝만 비교 (연속 루프만 차단)
                    // 자식 노드는 {{parent}} 치환 전/후 key가 달라 매칭 불가 → 루트만 검사
                    val lastStep = stepHistory.lastOrNull()
                    if (lastStep != null) {
                        val prevFunctions = lastStep.successfulFunctions.toSet()
                        val proposed = llmTree.getActualRootNodes().map { llmNodeKey(it) }.toSet()
                        if (proposed.isNotEmpty() && prevFunctions.containsAll(proposed)) {
                            val lastData = lastStep.result
                                .takeIf { !it.startsWith("ERROR") && !it.startsWith("Unknown function") }
                                ?: stepHistory.lastOrNull { !it.result.startsWith("ERROR") }?.result
                                ?: "작업 완료"
                            logger.info("🔁 [ReAct] 중복 루프 감지 (직전 스텝 동일) → 마지막 결과 반환 (이미 완료: $proposed)")
                            return ExecutionResult(result = lastData, stepHistory = stepHistory.toList())
                        }
                    }

                    // LLM 응답 → Task (B4). ExecutionTree 는 TreeExecutor 내부에서 매핑.
                    val task = try {
                        com.hana.orchestrator.orchestrator.core.task.TaskTreeMapper.fromLLMResponse(llmTree, query)
                    } catch (e: Exception) {
                        logger.warn("⚠️ [ReAct] LLM 응답 → Task 변환 실패: ${e.message}")
                        stepHistory.add(ReActStep(step, decision.reasoning, null, "ERROR(task-convert): ${e.message}"))
                        consecutiveErrors++
                        continue
                    }
                    val domainTree = com.hana.orchestrator.orchestrator.core.task.TaskTreeMapper.toExecutionTree(task)

                    val treeDesc = domainTree.rootNodes.joinToString(", ") { "${it.layerName}.${it.function}" }
                    logger.info("🌳 [ReAct] 스텝 #$step 미니트리 실행: [$treeDesc]")
                    historyManager.addLogTo(executionId, "🌳 미니트리: [$treeDesc]")

                    val treeExecResult = try {
                        treeExecutor.executeTask(task, currentExecution)
                    } catch (e: Exception) {
                        val errMsg = "ERROR(mini-tree): ${e.message}"
                        logger.warn("⚠️ [ReAct] 스텝 #$step 미니트리 오류: $errMsg")
                        null
                    }
                    val stepResult = treeExecResult?.result ?: "ERROR(mini-tree): 실행 실패"
                    val successfulFunctions = treeExecResult?.context?.completedNodes
                        ?.filter { it.isSuccess }
                        ?.map { nodeKey(it.node.layerName, it.node.function, it.node.args) }
                        ?: emptyList()

                    logger.info("📋 [ReAct] 스텝 #$step 결과: ${stepResult.take(120)}")
                    historyManager.addLogTo(executionId, "📋 결과: ${stepResult.take(60)}")
                    storeStepResult(executionId, step, stepResult)

                    val presentationTree = with(PresentationMapper) { domainTree.toResponse() }
                    stepHistory.add(ReActStep(step, decision.reasoning, presentationTree, stepResult, successfulFunctions))
                    if (treeExecResult == null || treeExecResult.context?.failedNodes?.isNotEmpty() == true) consecutiveErrors++ else consecutiveErrors = 0

                    // 자동 완료 제거: 항상 LLM에게 다음 결정 요청
                    // 이유: 자동 finish는 multi-step 작업을 조기 종료시킴
                    // LLM이 결과를 보고 finish/execute_tree/ask 스스로 결정
                }

                "ask" -> {
                    val question = decision.question.ifEmpty { decision.reasoning }
                    logger.info("❓ [ReAct] 사용자 질문: $question")
                    historyManager.addLogTo(executionId, "❓ 질문: ${question.take(60)}")
                    val answer = if (isScheduled) {
                        "[자율 실행] 사용자 확인 없이 자율 실행 중입니다. 스스로 가장 합리적인 선택을 결정하고 즉시 진행하세요. 추가 질문 없이 직접 실행하세요."
                    } else {
                        clarificationGate?.requestClarification(question) ?: ""
                    }
                    logger.info("💬 [ReAct] 답변: ${answer.take(80)}")
                    stepHistory.add(ReActStep(step, decision.reasoning, null, "Q: $question\nA: $answer"))
                    consecutiveErrors = 0
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
