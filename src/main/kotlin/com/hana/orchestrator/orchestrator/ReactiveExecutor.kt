package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.data.mapper.ExecutionTreeMapper
import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.llm.ReActStep
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.llm.useSuspend
import com.hana.orchestrator.presentation.mapper.ExecutionTreeMapper as PresentationMapper
import com.hana.orchestrator.presentation.model.execution.ExecutionPhase

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

    /**
     * ReAct 루프 실행
     * 최대 maxSteps 반복. 각 스텝에서 LLM이 미니트리 결정 → TreeExecutor 실행 → 결과 관찰 → 반복
     */
    suspend fun execute(
        query: String,
        executionId: String,
        startTime: Long
    ): ExecutionResult {
        val allDescriptions = layerManager.getAllLayerDescriptions()
        val stepHistory = mutableListOf<ReActStep>()

        for (step in 1..maxSteps) {
            val progressPct = (step * 70 / maxSteps + 15).coerceAtMost(85)
            logger.info("🔄 [ReAct] 스텝 #$step 시작")
            historyManager.addLogToCurrent("🔄 ReAct 스텝 #$step")
            statePublisher.emitProgressAsync(
                executionId, ExecutionPhase.TREE_EXECUTION,
                "🤔 스텝 #$step 결정 중...", progressPct, System.currentTimeMillis() - startTime
            )

            // LLM이 다음 액션 결정
            val decision = try {
                modelSelectionStrategy.selectClientForReActDecision()
                    .useSuspend { client -> client.decideNextAction(query, stepHistory, allDescriptions) }
            } catch (e: Exception) {
                logger.error("❌ [ReAct] LLM 결정 실패: ${e.message}")
                return ExecutionResult(result = "", error = "ReAct 결정 실패: ${e.message}")
            }

            logger.info("🤔 [ReAct] 스텝 #$step 결정: action=${decision.action}, reasoning=${decision.reasoning.take(80)}")

            when (decision.action) {
                "finish" -> {
                    val finalResult = decision.result.ifEmpty {
                        stepHistory.lastOrNull()?.result ?: "작업 완료"
                    }
                    logger.info("✅ [ReAct] 완료 (${step}스텝): ${finalResult.take(100)}")
                    historyManager.addLogToCurrent("✅ ReAct 완료 (${step}스텝)")
                    statePublisher.emitProgressAsync(
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
                    val stepResult = try {
                        val currentHistory = historyManager.getCurrentExecution()!!
                        treeExecutor.executeTree(domainTree, currentHistory).result
                    } catch (e: Exception) {
                        val errMsg = "ERROR(mini-tree): ${e.message}"
                        logger.warn("⚠️ [ReAct] 스텝 #$step 미니트리 오류: $errMsg")
                        errMsg
                    }

                    logger.info("📋 [ReAct] 스텝 #$step 결과: ${stepResult.take(120)}")
                    historyManager.addLogToCurrent("📋 결과: ${stepResult.take(60)}")

                    // 도메인 트리 → presentation 트리 (id 포함, UI 표시/저장용)
                    val presentationTree = with(PresentationMapper) { domainTree.toResponse() }
                    stepHistory.add(ReActStep(step, decision.reasoning, presentationTree, stepResult))
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
