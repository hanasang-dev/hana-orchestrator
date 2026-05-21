package com.hana.orchestrator.llm

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ReAct 추론 추적 저장소 — execution 단위로 step별 LLM I/O(보낸 프롬프트, 받은 raw 응답, 파싱 결과) 보관.
 *
 * 목적:
 * - 결정이 이상할 때 "어떤 프롬프트가 어떤 응답을 받았고 어떻게 파싱됐는가" 즉시 확인
 * - 프롬프트 템플릿 / KDoc 교정 근거 확보
 * - MCP get_reasoning_trace 툴이 조회
 *
 * 정책:
 * - 인메모리, 전역 싱글톤(object)
 * - LRU evict (max=MAX_EXECUTIONS): 가장 오래된 execution 단위로 삭제
 * - execution 완료 후에도 보존 — 사후 분석용
 */
object ReasoningTraceRecorder {

    private const val MAX_EXECUTIONS = 50

    /**
     * 단일 ReAct 스텝의 LLM 호출 기록
     */
    @Serializable
    data class ReasoningStep(
        val stepNumber: Int,
        val contextMode: String,        // TBox+ABox(N) | 전체(N)
        val promptChars: Int,
        val prompt: String,             // LLM에 실제로 보낸 enhanced prompt
        val rawResponse: String,        // LLM이 반환한 raw text (extractResponseText 결과)
        val extractedJson: String,      // JsonExtractor가 뽑아낸 JSON 텍스트
        val parsedDecision: String?,    // ReActDecision JSON 직렬화 (null = 파싱 실패)
        val parseError: String?,        // 파싱 실패 시 메시지
        val latencyMs: Long
    )

    @Serializable
    data class ExecutionTrace(
        val executionId: String,
        val createdAt: Long,
        val steps: MutableList<ReasoningStep>
    )

    private val store = ConcurrentHashMap<String, ExecutionTrace>()
    private val insertionOrder = CopyOnWriteArrayList<String>()

    fun record(
        executionId: String,
        stepNumber: Int,
        contextMode: String,
        prompt: String,
        rawResponse: String,
        extractedJson: String,
        parsedDecision: String?,
        parseError: String?,
        latencyMs: Long
    ) {
        val trace = store.computeIfAbsent(executionId) {
            insertionOrder.add(executionId)
            evictIfNeeded()
            ExecutionTrace(executionId, System.currentTimeMillis(), mutableListOf())
        }
        synchronized(trace) {
            trace.steps.add(
                ReasoningStep(
                    stepNumber = stepNumber,
                    contextMode = contextMode,
                    promptChars = prompt.length,
                    prompt = prompt,
                    rawResponse = rawResponse,
                    extractedJson = extractedJson,
                    parsedDecision = parsedDecision,
                    parseError = parseError,
                    latencyMs = latencyMs
                )
            )
        }
    }

    fun get(executionId: String): ExecutionTrace? = store[executionId]

    fun getStep(executionId: String, stepNumber: Int): ReasoningStep? =
        store[executionId]?.steps?.firstOrNull { it.stepNumber == stepNumber }

    fun listExecutionIds(): List<String> = insertionOrder.toList()

    fun clear(executionId: String) {
        store.remove(executionId)
        insertionOrder.remove(executionId)
    }

    private fun evictIfNeeded() {
        while (insertionOrder.size > MAX_EXECUTIONS) {
            val oldest = insertionOrder.removeAt(0)
            store.remove(oldest)
        }
    }
}
