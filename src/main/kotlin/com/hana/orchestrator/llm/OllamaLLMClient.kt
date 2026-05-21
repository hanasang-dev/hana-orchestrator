package com.hana.orchestrator.llm

import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.ollama.client.ContextWindowStrategy
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.message.Message
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.layer.LayerDescription
import com.hana.orchestrator.llm.embedding.LayerEmbeddingIndex
import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * Ollama 로컬 LLM 통신을 위한 모듈
 * SRP: LLM 통신 및 응답 파싱만 담당
 *
 * OOP 원칙:
 * - SRP: LLM 통신 책임만 담당, 프롬프트 생성/폴백 처리는 별도 클래스로 분리
 * - DRY: 공통 LLM 호출 로직 추출
 * - 캡슐화: 내부 구현 세부사항 숨김
 * - DIP: LLMClient 인터페이스 구현
 */
class OllamaLLMClient(
    private val modelId: String = "qwen3:8b",
    private val contextLength: Long = 40_960L,
    private val timeoutMs: Long = 120_000L,
    private val baseUrl: String = "http://localhost:11434"
) : LLMClient {
    private val logger = createOrchestratorLogger(OllamaLLMClient::class.java, null)

    /**
     * 설정 기반 생성자
     */
    constructor(config: LLMConfig, modelId: String, contextLength: Long, baseUrl: String) : this(
        modelId = modelId,
        contextLength = contextLength,
        timeoutMs = config.timeoutMs,
        baseUrl = baseUrl
    )

    // 공통 JSON 설정 (캡슐화)
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // Ollama 클라이언트 (baseUrl 설정 가능)
    // ai.koog의 simpleOllamaAIExecutor()는 환경변수 OLLAMA_HOST를 읽지만,
    // 런타임에 환경변수를 변경할 수 없으므로 OllamaClient를 직접 생성하여 사용
    private val ollamaClient = OllamaClient(
        baseUrl = baseUrl,
        timeoutConfig = ConnectionTimeoutConfig(
            connectTimeoutMillis = 5_000L,
            requestTimeoutMillis = timeoutMs,
            socketTimeoutMillis = timeoutMs
        ),
        contextWindowStrategy = ContextWindowStrategy.Companion.Fixed(contextLength)
    )

    // 프롬프트 생성기 (SRP: 프롬프트 생성 책임 분리)
    private val promptBuilder = LLMPromptBuilder()

    // 임베딩 기반 레이어 선택 인덱스 (선택적 — null이면 전체 레이어 사용)
    private var embeddingIndex: LayerEmbeddingIndex? = null

    fun setEmbeddingIndex(index: LayerEmbeddingIndex) {
        embeddingIndex = index
    }

    /**
     * LLM 응답에서 텍스트 추출
     * DRY: callLLM / generateDirectAnswer 중복 제거
     */
    private fun extractResponseText(responses: List<Message>): String {
        return when (val firstResponse = responses.firstOrNull()) {
            is Message.Assistant -> firstResponse.content
            is Message.Tool.Call -> firstResponse.content
            else -> throw Exception("LLM 응답이 비어있습니다")
        }
    }

    /**
     * 공통 LLM 모델 생성
     * DRY: 반복되는 모델 생성 로직 공통화
     */
    private fun createLLMModel(): LLModel {
        return LLModel(
            provider = LLMProvider.Ollama,
            id = modelId,
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Schema.JSON.Basic,
                LLMCapability.Tools
            ),
            contextLength = contextLength
        )
    }

    /**
     * 공통 LLM 호출 로직
     * JSON 파싱 실패 시 재시도 로직 포함 (최대 2회 재시도)
     */
    private suspend fun <T> callLLM(
        prompt: String,
        responseParser: (String) -> T,
        schema: JsonObject? = null,
        timeoutMs: Long = this.timeoutMs,
        maxRetries: Int = 2,
        logRawJson: Boolean = false,
        /**
         * 호출 결과를 외부에 전달 — ReasoningTraceRecorder 캡처용.
         * 성공·실패 모두 한 번씩 호출. error가 null이면 성공.
         */
        traceSink: ((sentPrompt: String, rawResponse: String, jsonText: String, error: String?) -> Unit)? = null
    ): T {
        var lastError: Exception? = null
        var lastJsonText: String? = null
        var lastSentPrompt: String = prompt
        var lastRawResponse: String = ""

        repeat(maxRetries + 1) { attempt ->
            // watchdog — withTimeout 이 koog 내부 blocking IO 를 cancel 못 하므로
            // timeoutMs 후 ollamaClient.close() 로 HTTP 연결 강제 종료.
            // generateDirectAnswer 와 동일 패턴. 17K+ 자 프롬프트 + 14B 모델 stuck 방지.
            val watchdog = CoroutineScope(Dispatchers.Default).launch {
                delay(timeoutMs)
                logger.warn("⏰ [LLM] callLLM 타임아웃 (${timeoutMs}ms) — HTTP 연결 강제 종료 (attempt ${attempt + 1})")
                kotlin.runCatching { ollamaClient.close() }
            }
            try {
                val model = createLLMModel()
                val enhancedPrompt = if (attempt > 0 && lastError != null) {
                    """
                    $prompt

                    중요: 이전 응답에서 JSON 파싱 오류가 발생했습니다.
                    오류: ${lastError?.message}
                    이전 응답: ${lastJsonText?.take(200) ?: "없음"}

                    반드시 완전하고 유효한 JSON 형식으로만 응답하세요. 모든 필수 필드를 포함하고, 따옴표를 올바르게 이스케이프하세요.
                    """.trimIndent()
                } else {
                    prompt
                }
                lastSentPrompt = enhancedPrompt

                val promptDsl = Prompt.build(id = "llm-call") {
                    user(enhancedPrompt)
                }.let { p ->
                    if (schema != null) {
                        p.withUpdatedParams { this.schema = LLMParams.Schema.JSON.Basic("react-decision", schema) }
                    } else p
                }

                val responses = withTimeout(timeoutMs) {
                    ollamaClient.execute(
                        prompt = promptDsl,
                        model = model,
                        tools = emptyList()
                    )
                }

                val responseText = extractResponseText(responses)
                lastRawResponse = responseText
                val jsonText = JsonExtractor.extract(responseText)
                lastJsonText = jsonText
                if (logRawJson) {
                    logger.info("📋 [RAW responseText (${responseText.length}chars)]: ${responseText.take(500)}")
                    logger.info("📋 [EXTRACTED jsonText (${jsonText.length}chars)]: $jsonText")
                }

                val parsed = responseParser(jsonText)
                runCatching { traceSink?.invoke(lastSentPrompt, lastRawResponse, jsonText, null) }
                return parsed
            } catch (e: Exception) {
                // CancellationException (withTimeout 포함)은 재시도 없이 즉시 전파
                if (e is CancellationException) throw e
                lastError = e
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(500)
                } else {
                    val finalMsg = "LLM 응답 파싱 실패 (${maxRetries + 1}회 시도): ${e.message}"
                    runCatching {
                        traceSink?.invoke(lastSentPrompt, lastRawResponse, lastJsonText ?: "", finalMsg)
                    }
                    throw Exception(finalMsg, e)
                }
            } finally {
                watchdog.cancel()
            }
        }

        throw Exception("LLM 호출 실패")
    }

    /**
     * LLM이 직접 답변 생성 (레이어 없이)
     *
     * withTimeout만으로는 koog OllamaClient 내부의 non-cancellable 스트리밍을 중단 불가.
     * watchdog 코루틴이 timeoutMs 후 HTTP 연결을 강제 종료하여 블로킹 IO를 인터럽트.
     */
    override suspend fun generateDirectAnswer(userQuery: String): String {
        val prompt = promptBuilder.buildDirectAnswerPrompt(userQuery)
        val model = createLLMModel()
        val promptDsl = Prompt.build(id = "direct-answer") {
            user(prompt)
        }

        val watchdog = CoroutineScope(Dispatchers.Default).launch {
            delay(timeoutMs)
            logger.warn("⏰ [LLM] generateDirectAnswer 타임아웃 (${timeoutMs}ms) — 연결 강제 종료")
            kotlin.runCatching { ollamaClient.close() }
        }

        return try {
            val responses = ollamaClient.execute(
                prompt = promptDsl,
                model = model,
                tools = emptyList()
            )
            extractResponseText(responses).trim()
        } catch (e: CancellationException) {
            throw e  // 코루틴 취소 전파
        } catch (e: Exception) {
            throw Exception("LLM generateDirectAnswer 실패: ${e.message}", e)
        } finally {
            watchdog.cancel()
        }
    }

    override suspend fun reviewTree(
        userQuery: String,
        tree: ExecutionTree,
        layerDescriptions: List<LayerDescription>
    ): TreeReview {
        val prompt = promptBuilder.buildTreeReviewPrompt(userQuery, tree, layerDescriptions)
        return callLLM(prompt, { json ->
            jsonConfig.decodeFromString<TreeReview>(json)
        })
    }

    override suspend fun judgeFinish(
        query: String,
        candidateResult: String,
        stepHistory: List<ReActStep>
    ): VerifyOutcome {
        val prompt = promptBuilder.buildFinishJudgePrompt(query, candidateResult, stepHistory)
        return callLLM(prompt, { json ->
            jsonConfig.decodeFromString<VerifyOutcome>(json)
        })
    }

    /**
     * 오래된 ReAct stepHistory를 단일 요약 문장으로 압축
     * 컨텍스트 한계 근접 시 DefaultReActStrategy가 호출
     */
    override suspend fun summarizeHistory(steps: List<ReActStep>): String {
        val historyText = steps.joinToString("\n") { s ->
            val treeDesc = s.tree?.rootNodes?.joinToString(", ") { "${it.layerName}.${it.function}" } ?: "(알 수 없음)"
            "스텝 ${s.stepNumber}: [$treeDesc] → ${s.result.take(200)}"
        }
        val prompt = """다음 ReAct 실행 히스토리를 3~5문장으로 요약하세요.
완료된 작업, 주요 결과, 아직 남은 단서만 포함하세요. 불필요한 세부사항은 제외하세요.

히스토리:
$historyText

요약:"""
        val model = createLLMModel()
        val promptDsl = ai.koog.prompt.dsl.Prompt.build(id = "summarize-history") { user(prompt) }
        val responses = withTimeout(timeoutMs) {
            ollamaClient.execute(prompt = promptDsl, model = model, tools = emptyList())
        }
        return extractResponseText(responses).trim()
    }

    /**
     * ReAct 루프: 다음 액션 결정 (스텝 히스토리 + 레이어 목록 기반)
     * JSON Schema 강제: execute_tree(미니트리) 또는 finish
     */
    override suspend fun decideNextAction(
        query: String,
        stepHistory: List<ReActStep>,
        layerDescriptions: List<LayerDescription>,
        projectContext: Map<String, String>,
        executionId: String?,
        stepNumber: Int?
    ): ReActDecision {
        // 첫 호출 시 임베딩 인덱스 구축 (lazy — 레이어 초기화 완료 후 자연스럽게 트리거됨)
        embeddingIndex?.takeIf { !it.isReady }?.build(layerDescriptions)

        // 임베딩으로 관련 레이어 선택 (null이면 전체 레이어 사용 = fallback)
        val relevantLayers = embeddingIndex?.takeIf { it.isReady }
            ?.findRelevant(query, layerDescriptions)

        val prompt = promptBuilder.buildReActPrompt(
            query = query,
            stepHistory = stepHistory,
            allLayerDescriptions = layerDescriptions,
            projectContext = projectContext,
            relevantLayerDescriptions = relevantLayers
        )
        val availableLayerNames = layerDescriptions.map { it.name }
        val schema = JsonSchemaBuilder.buildReActDecisionSchema(availableLayerNames)
        val contextMode = if (relevantLayers != null) "TBox+ABox(${relevantLayers.size})" else "전체(${layerDescriptions.size})"
        logger.info("🤔 [ReAct] LLM 결정 요청 (스텝 ${stepHistory.size + 1}, 프롬프트 ${prompt.length}자, 컨텍스트: $contextMode)")

        val startNs = System.nanoTime()
        val sink: ((String, String, String, String?) -> Unit)? = if (executionId != null && stepNumber != null) {
            { sent, raw, jsonText, error ->
                ReasoningTraceRecorder.record(
                    executionId = executionId,
                    stepNumber = stepNumber,
                    contextMode = contextMode,
                    prompt = sent,
                    rawResponse = raw,
                    extractedJson = jsonText,
                    parsedDecision = if (error == null) jsonText else null,
                    parseError = error,
                    latencyMs = (System.nanoTime() - startNs) / 1_000_000
                )
            }
        } else null

        return callLLM(
            prompt = prompt,
            schema = schema,
            responseParser = { jsonText ->
                jsonConfig.decodeFromString<ReActDecision>(jsonText)
            },
            logRawJson = true,
            traceSink = sink
        )
    }

    override suspend fun close() {
        ollamaClient.close()
    }
}
