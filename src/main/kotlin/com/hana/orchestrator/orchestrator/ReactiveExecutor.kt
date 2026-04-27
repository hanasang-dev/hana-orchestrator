package com.hana.orchestrator.orchestrator

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

/**
 * ReAct(Reasoning + Acting) 루프 실행기
 * SRP: LLM-guided 단계별 실행 책임만 담당
 *
 * 각 스텝에서 LLM이 미니트리를 결정 → TreeExecutor가 실행 → 결과 누적
 * action:
 * - execute_tree : LLM이 미니트리 생성 → TreeExecutor.executeTree() 위임 (플레이스홀더/병렬 지원)
 * - finish       : 루프 종료 및 최종 결과 반환
 */
class ReactiveExecutor(
    private val layerManager: LayerManager,
    private val historyManager: ExecutionHistoryManager,
    private val statePublisher: ExecutionStatePublisher,
    private val modelSelectionStrategy: ModelSelectionStrategy,
    private val treeExecutor: TreeExecutor
) {
    private val maxSteps = 15
    private val logger = createOrchestratorLogger(ReactiveExecutor::class.java, historyManager)

    // ── P1: 인수(args) 포함 노드 식별 키 생성 헬퍼 ──────────────────────────────

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
     * ReAct 루프 실행
     * 최대 maxSteps 반복. 각 스텝에서 LLM이 미니트리 결정 → TreeExecutor 실행 → 결과 관찰 → 반복
     */
    suspend fun execute(
        query: String,
        executionId: String,
        startTime: Long,
        projectContext: Map<String, String> = emptyMap()
    ): ExecutionResult {
        val allDescriptions = layerManager.getAllLayerDescriptions()
        val stepHistory = mutableListOf<ReActStep>()

        for (step in 1..maxSteps) {
            val progressPct = (step * 70 / maxSteps + 15).coerceAtMost(85)
            logger.info("🔄 [ReAct] 스텝 #$step 시작")
            historyManager.addLogToCurrent("🔄 ReAct 스텝 #$step")
            statePublisher.emitProgress(
                executionId, ExecutionPhase.TREE_EXECUTION,
                "🤔 스텝 #$step 결정 중...", progressPct, System.currentTimeMillis() - startTime
            )

            // 직전 스텝 결과의 "다음 단계:" 힌트 → LLM 없이 자동 실행
            // parseNextStepHint는 함수명만 비교하므로 args 접미사를 제거한 이름 집합을 사용
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
                    continue
                }
                val autoExecResult = try {
                    treeExecutor.executeTree(autoDomainTree, historyManager.getCurrentExecution()!!)
                } catch (e: Exception) {
                    logger.warn("⚠️ [ReAct] '다음 단계' 실행 실패: ${e.message}")
                    null
                }
                val autoResult = autoExecResult?.result ?: "ERROR(auto-next): 실행 실패"
                // P1: args 포함 키, P2: ERROR 문자열 반환은 성공으로 간주하지 않음
                val autoSuccessful = autoExecResult?.context?.completedNodes
                    ?.filter { it.isSuccess && !it.result.orEmpty().startsWith("ERROR") }
                    ?.map { nodeKey(it.node.layerName, it.node.function, it.node.args) }
                    ?: emptyList()
                logger.info("📋 [ReAct] 스텝 #$step (자동) 결과: ${autoResult.take(120)}")
                val autoPresentation = with(PresentationMapper) { autoDomainTree.toResponse() }
                stepHistory.add(ReActStep(step, "자동 실행: $layerName.$function", autoPresentation, autoResult, autoSuccessful))
                continue
            }

            // LLM이 다음 액션 결정
            val decision = try {
                modelSelectionStrategy.selectClientForReActDecision()
                    .useSuspend { client -> client.decideNextAction(query, stepHistory, allDescriptions, projectContext) }
            } catch (e: Exception) {
                logger.error("❌ [ReAct] LLM 결정 실패: ${e.message}")
                // 이미 완료된 스텝 결과가 있으면 그걸로 반환 (결과 유실 방지)
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
                    // LLM이 finish.result에 명시한 답변 우선 사용.
                    // 비어있으면 히스토리의 마지막 성공 결과 사용 (에러/Unknown function 제외)
                    val lastSuccessResult = stepHistory.lastOrNull { step ->
                        !step.result.startsWith("ERROR") && !step.result.startsWith("Unknown function")
                    }?.result
                    val finalResult = decision.result.ifEmpty { null }
                        ?: lastSuccessResult
                        ?: "작업 완료"
                    logger.info("✅ [ReAct] 완료 (${step}스텝): ${finalResult.take(100)}")
                    historyManager.addLogToCurrent("✅ ReAct 완료 (${step}스텝)")
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
                        stepHistory.add(ReActStep(step, decision.reasoning, null, "(tree is null)"))
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

                    // LLM 트리(data layer) → 도메인 트리 변환 (id 자동 생성 포함)
                    val domainTree = try {
                        ExecutionTreeMapper.toExecutionTree(llmTree)
                    } catch (e: Exception) {
                        logger.warn("⚠️ [ReAct] 미니트리 변환 실패: ${e.message}")
                        stepHistory.add(ReActStep(step, decision.reasoning, null, "ERROR(tree-convert): ${e.message}"))
                        continue
                    }

                    val treeDesc = domainTree.rootNodes.joinToString(", ") { "${it.layerName}.${it.function}" }
                    logger.info("🌳 [ReAct] 스텝 #$step 미니트리 실행: [$treeDesc]")
                    historyManager.addLogToCurrent("🌳 미니트리: [$treeDesc]")

                    // TreeExecutor로 미니트리 실행 (플레이스홀더 해석, 병렬/순차 지원)
                    val treeExecResult = try {
                        val currentHistory = historyManager.getCurrentExecution()!!
                        treeExecutor.executeTree(domainTree, currentHistory)
                    } catch (e: Exception) {
                        val errMsg = "ERROR(mini-tree): ${e.message}"
                        logger.warn("⚠️ [ReAct] 스텝 #$step 미니트리 오류: $errMsg")
                        null
                    }
                    val stepResult = treeExecResult?.result ?: "ERROR(mini-tree): 실행 실패"
                    // P1: args 포함 키, P2: ERROR 문자열 반환은 성공으로 간주하지 않음
                    val successfulFunctions = treeExecResult?.context?.completedNodes
                        ?.filter { it.isSuccess && !it.result.orEmpty().startsWith("ERROR") }
                        ?.map { nodeKey(it.node.layerName, it.node.function, it.node.args) }
                        ?: emptyList()

                    logger.info("📋 [ReAct] 스텝 #$step 결과: ${stepResult.take(120)}")
                    historyManager.addLogToCurrent("📋 결과: ${stepResult.take(60)}")

                    // 도메인 트리 → presentation 트리 (id 포함, UI 표시/저장용)
                    val presentationTree = with(PresentationMapper) { domainTree.toResponse() }
                    stepHistory.add(ReActStep(step, decision.reasoning, presentationTree, stepResult, successfulFunctions))
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

        // 최대 스텝 도달 — 마지막 결과로 반환
        val finalResult = stepHistory.lastOrNull()?.result ?: "최대 스텝 도달"
        logger.warn("⚠️ [ReAct] 최대 스텝(${maxSteps}) 도달: 강제 종료")
        return ExecutionResult(result = finalResult, error = "최대 ReAct 스텝(${maxSteps}) 도달", stepHistory = stepHistory.toList())
    }
}
