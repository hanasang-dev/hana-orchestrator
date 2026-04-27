package com.hana.orchestrator.llm

import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.message.Message
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.layer.LayerDescription
import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import kotlinx.serialization.json.Json
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
        )
    )

    // 프롬프트 생성기 (SRP: 프롬프트 생성 책임 분리)
    private val promptBuilder = LLMPromptBuilder()

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
        logRawJson: Boolean = false
    ): T {
        var lastError: Exception? = null
        var lastJsonText: String? = null

        repeat(maxRetries + 1) { attempt ->
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

                val promptDsl = Prompt.build(id = "llm-call") {
                    user(enhancedPrompt)
                }

                val responses = withTimeout(timeoutMs) {
                    ollamaClient.execute(
                        prompt = promptDsl,
                        model = model,
                        tools = emptyList()
                    )
                }

                val responseText = when (val firstResponse = responses.firstOrNull()) {
                    is Message.Assistant -> firstResponse.content
                    is Message.Tool.Call -> firstResponse.content
                    else -> throw Exception("LLM 응답이 비어있습니다")
                }

                val jsonText = JsonExtractor.extract(responseText)
                lastJsonText = jsonText
                if (logRawJson) {
                    logger.info("📋 [RAW responseText (${responseText.length}chars)]: ${responseText.take(500)}")
                    logger.info("📋 [EXTRACTED jsonText (${jsonText.length}chars)]: $jsonText")
                }

                return responseParser(jsonText)
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(500)
                } else {
                    throw Exception("LLM 응답 파싱 실패 (${maxRetries + 1}회 시도): ${e.message}", e)
                }
            }
        }

        throw Exception("LLM 호출 실패")
    }

    /**
     * LLM이 직접 답변 생성 (레이어 없이)
     */
    override suspend fun generateDirectAnswer(userQuery: String): String {
        val prompt = promptBuilder.buildDirectAnswerPrompt(userQuery)

        val model = createLLMModel()
        val promptDsl = Prompt.build(id = "direct-answer") {
            user(prompt)
        }

        val responses = withTimeout(timeoutMs) {
            ollamaClient.execute(
                prompt = promptDsl,
                model = model,
                tools = emptyList()
            )
        }

        val responseText = when (val firstResponse = responses.firstOrNull()) {
            is Message.Assistant -> firstResponse.content
            is Message.Tool.Call -> firstResponse.content
            else -> throw Exception("LLM 응답이 비어있습니다")
        }

        return responseText.trim()
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

    /**
     * ReAct 루프: 다음 액션 결정 (스텝 히스토리 + 레이어 목록 기반)
     * JSON Schema 강제: execute_tree(미니트리) 또는 finish
     */
    override suspend fun decideNextAction(
        query: String,
        stepHistory: List<ReActStep>,
        layerDescriptions: List<LayerDescription>,
        projectContext: Map<String, String>
    ): ReActDecision {
        val prompt = promptBuilder.buildReActPrompt(query, stepHistory, layerDescriptions, projectContext)
        val availableLayerNames = layerDescriptions.map { it.name }
        val schema = JsonSchemaBuilder.buildReActDecisionSchema(availableLayerNames)
        logger.info("🤔 [ReAct] LLM 결정 요청 (스텝 ${stepHistory.size + 1})")
        return callLLM(
            prompt = prompt,
            schema = schema,
            responseParser = { jsonText ->
                jsonConfig.decodeFromString<ReActDecision>(jsonText)
            },
            logRawJson = true
        )
    }

    override suspend fun close() {
        ollamaClient.close()
    }
}
