package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.llm.ReActStep
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.llm.useSuspend
import com.hana.orchestrator.presentation.model.execution.ExecutionPhase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * ReAct(Reasoning + Acting) 루프 실행기
 * SRP: LLM-guided 단계별 실행 책임만 담당
 *
 * 지원 액션:
 * - call_layer    : 단일 레이어 함수 순차 호출
 * - call_parallel : 여러 레이어 함수 동시 호출 (coroutineScope + async/awaitAll)
 * - finish        : 루프 종료 및 최종 결과 반환
 */
class ReactiveExecutor(
    private val layerManager: LayerManager,
    private val historyManager: ExecutionHistoryManager,
    private val statePublisher: ExecutionStatePublisher,
    private val modelSelectionStrategy: ModelSelectionStrategy
) {
    private val maxSteps = 15
    private val logger = createOrchestratorLogger(ReactiveExecutor::class.java, historyManager)

    /**
     * ReAct 루프 실행
     * 최대 maxSteps 반복. 각 스텝에서 LLM이 액션 결정 → 실행 → 관찰 → 반복
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

            val stepResult = when (decision.action) {
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

                "call_layer" -> {
                    val args: Map<String, Any> = decision.args
                    try {
                        layerManager.executeOnLayer(decision.layerName, decision.function, args)
                    } catch (e: Exception) {
                        val errMsg = "ERROR(${decision.layerName}.${decision.function}): ${e.message}"
                        logger.warn("⚠️ [ReAct] 스텝 #$step 레이어 오류: $errMsg")
                        errMsg
                    }.also { result ->
                        logger.info("📋 [ReAct] 스텝 #$step 결과: ${result.take(120)}")
                        historyManager.addLogToCurrent("📋 ${decision.layerName}.${decision.function}: ${result.take(60)}")
                    }
                }

                "call_parallel" -> {
                    if (decision.calls.isEmpty()) {
                        logger.warn("⚠️ [ReAct] call_parallel calls 비어있음, 스킵")
                        "(병렬 호출 목록 없음)"
                    } else {
                        logger.info("⚡ [ReAct] 스텝 #$step 병렬 실행 ${decision.calls.size}개")
                        historyManager.addLogToCurrent("⚡ 병렬 실행 ${decision.calls.size}개")
                        val results = coroutineScope {
                            decision.calls.map { call ->
                                async {
                                    try {
                                        layerManager.executeOnLayer(call.layerName, call.function, call.args)
                                    } catch (e: Exception) {
                                        "ERROR(${call.layerName}.${call.function}): ${e.message}"
                                    }
                                }
                            }.awaitAll()
                        }
                        results.mapIndexed { i, r ->
                            val callDesc = decision.calls[i].let { "${it.layerName}.${it.function}" }
                            "[$callDesc]: ${r.take(200)}"
                        }.joinToString("\n").also { combined ->
                            logger.info("📋 [ReAct] 스텝 #$step 병렬 결과:\n${combined.take(300)}")
                        }
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

            // 스텝 기록 — call_layer/call_parallel 공통
            val stepLayerName = when (decision.action) {
                "call_parallel" -> "parallel(${decision.calls.size})"
                else -> decision.layerName
            }
            val stepFunction = when (decision.action) {
                "call_parallel" -> decision.calls.joinToString("+") { it.function }
                else -> decision.function
            }
            stepHistory.add(
                ReActStep(
                    stepNumber = step,
                    reasoning = decision.reasoning,
                    layerName = stepLayerName,
                    function = stepFunction,
                    args = decision.args,
                    calls = if (decision.action == "call_parallel") decision.calls else emptyList(),
                    result = stepResult
                )
            )
        }

        // 최대 스텝 도달 — 마지막 결과로 반환
        val finalResult = stepHistory.lastOrNull()?.result ?: "최대 스텝 도달"
        logger.warn("⚠️ [ReAct] 최대 스텝(${maxSteps}) 도달: 강제 종료")
        return ExecutionResult(result = finalResult, error = "최대 ReAct 스텝(${maxSteps}) 도달", stepHistory = stepHistory.toList())
    }
}
