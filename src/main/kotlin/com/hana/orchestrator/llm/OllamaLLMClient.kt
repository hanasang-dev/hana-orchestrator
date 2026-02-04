package com.hana.orchestrator.llm

import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.message.Message
import com.hana.orchestrator.domain.entity.ExecutionNode
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.data.model.response.ExecutionTreeResponse
import com.hana.orchestrator.data.mapper.ExecutionTreeMapper
import com.hana.orchestrator.context.AppContextService
import com.hana.orchestrator.context.DefaultPromptComposer
import com.hana.orchestrator.context.LLMTaskType
import com.hana.orchestrator.context.PromptComposer
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.layer.LayerDescription
import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withTimeout
import ai.koog.prompt.params.LLMParams
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
    // OllamaClient 생성자의 첫 번째 파라미터가 baseUrl: String
    // 타임아웃 설정: 적절한 타임아웃으로 설정
    private val ollamaClient = OllamaClient(
        baseUrl = baseUrl,
        timeoutConfig = ConnectionTimeoutConfig(
            connectTimeoutMillis = 5_000L,       // 연결 타임아웃: 5초
            requestTimeoutMillis = timeoutMs,     // 요청 타임아웃: 설정값 사용
            socketTimeoutMillis = timeoutMs      // 소켓 타임아웃: 요청 타임아웃과 동일
        )
    )
    
    // 프롬프트 생성기 (SRP: 프롬프트 생성 책임 분리)
    private val promptBuilder = LLMPromptBuilder()
    private val promptComposer: PromptComposer = DefaultPromptComposer()
    
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
     * 2026년 기준: Ollama Structured Outputs를 활용하여 스키마를 강제
     * - 프롬프트만으로는 부족하므로 format 파라미터로 스키마 전달
     * - 파싱 실패 시 프롬프트에 에러 정보를 포함하여 재요청
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
                    // 재시도 시: 이전 에러 정보를 포함하여 프롬프트 개선
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
                
                // TODO: Ollama Structured Outputs 지원 추가 필요
                // 현재는 프롬프트에 스키마 정보를 포함하는 방식 사용
                // schema 파라미터는 준비되었지만 LLMParams.Schema.JSON 생성 방법 확인 필요
                val promptDsl = Prompt.build(id = "llm-call") {
                    user(enhancedPrompt)
                    // 향후 추가: params { schema = LLMParams.Schema.JSON(schema) }
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
                    logger.info("📋 [트리생성] LLM 원본 반환 (직접 출력):\n$jsonText")
                }

                return responseParser(jsonText)
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    // 재시도 전에 잠시 대기 (LLM이 이전 응답을 처리할 시간 제공)
                    kotlinx.coroutines.delay(500)
                } else {
                    // 최대 재시도 횟수 초과
                    throw Exception("LLM 응답 파싱 실패 (${maxRetries + 1}회 시도): ${e.message}", e)
                }
            }
        }
        
        // 이론적으로 도달 불가능하지만 컴파일러를 위해
        throw Exception("LLM 호출 실패")
    }
    
    /**
     * 사용자 질문과 레이어 정보를 바탕으로 ExecutionTree 구조의 실행 계획을 생성
     */
    /** 트리 생성 전용 타임아웃(ms). 복잡한 프롬프트에서 LLM이 오래 걸릴 때 전체 요청이 멈춘 것처럼 보이지 않도록 제한. */
    private val treeCreationTimeoutMs = minOf(timeoutMs, 60_000L)

    /** 생성된 트리 구조를 로그로 남겨, 사용자 요구 대비 LLM이 뭘 반환했는지 확인 가능하게 함. */
    private fun logCreatedTreeStructure(tree: ExecutionTree) {
        val totalNodes = tree.allNodes().size
        val chain = tree.rootNodes.joinToString(" → ") { root ->
            sequence {
                var n: ExecutionNode? = root
                while (n != null) {
                    yield("${n.layerName}.${n.function}")
                    n = n.children.firstOrNull()
                }
            }.toList().joinToString(" → ")
        }
        logger.info("📋 [트리생성] LLM이 반환한 트리: 루트 ${tree.rootNodes.size}개, 총 노드 ${totalNodes}개 | 체인: $chain")
    }

    override suspend fun createExecutionTree(
        userQuery: String,
        layerDescriptions: List<LayerDescription>,
        appContextService: AppContextService?
    ): ExecutionTree {
        val body = promptBuilder.buildExecutionTreePrompt(userQuery, layerDescriptions)
        val prompt = if (appContextService != null) {
            promptComposer.compose(LLMTaskType.CREATE_TREE, appContextService, body)
        } else {
            body
        }
        logger.info("📋 [트리생성] LLM 프롬프트 길이: ${prompt.length}자")
        logger.info("📋 [트리생성] LLM 프롬프트:\n$prompt")
        val availableLayerNames = layerDescriptions.map { it.name }
        val schema = JsonSchemaBuilder.buildExecutionTreeSchema(availableLayerNames)
        
        logger.info("🔄 [트리생성] LLM 호출 시작 (타임아웃: ${treeCreationTimeoutMs}ms)")
        return try {
            val tree = callLLM(
                prompt = prompt,
                schema = schema,
                responseParser = { jsonText ->
                    val treeResponse = jsonConfig.decodeFromString<ExecutionTreeResponse>(jsonText)
                    ExecutionTreeMapper.toExecutionTree(treeResponse)
                },
                timeoutMs = treeCreationTimeoutMs,
                logRawJson = true
            )
            logger.info("✅ [트리생성] LLM 호출 완료")
            logCreatedTreeStructure(tree)
            tree
        } catch (e: Exception) {
            logger.error("❌ [트리생성] LLM 호출 실패: ${e.message}")
            throw e
        }
    }
    
    override suspend fun evaluateResult(
        userQuery: String,
        executionResult: String,
        executionSummary: String?
    ): ResultEvaluation {
        val prompt = promptBuilder.buildEvaluationPrompt(userQuery, executionResult, executionSummary)
        logger.info("📋 [평가] LLM에 넘기는 프롬프트:\n$prompt")
        val schema = JsonSchemaBuilder.buildResultEvaluationSchema()
        
        return callLLM(
            prompt = prompt,
            schema = schema,
            responseParser = { jsonText ->
                jsonConfig.decodeFromString<ResultEvaluation>(jsonText)
            }
        )
    }
    
    /**
     * 실패한 실행에 대한 재처리 방안을 LLM이 제시
     */
    override suspend fun suggestRetryStrategy(
        userQuery: String,
        previousHistory: ExecutionHistory,
        layerDescriptions: List<LayerDescription>,
        previousExecutedWorkSummary: String?
    ): RetryStrategy {
        val prompt = promptBuilder.buildRetryStrategyPrompt(userQuery, previousHistory, layerDescriptions, previousExecutedWorkSummary)
        val availableLayerNames = layerDescriptions.map { it.name }
        val schema = JsonSchemaBuilder.buildRetryStrategySchema(availableLayerNames)
        
        return callLLM(
            prompt = prompt,
            schema = schema,
            responseParser = { jsonText ->
                // 배열로 반환된 경우 처리 (예: [{"layerName":...}] -> {"newTree": {"rootNodes": [...]}})
                val normalizedJson = if (jsonText.trimStart().startsWith("[")) {
                    // 배열을 객체로 변환
                    val arrayContent = jsonText.trim().removeSurrounding("[", "]")
                    """{"shouldStop":false,"reason":"재처리 방안","newTree":{"rootNodes":[$arrayContent]}}"""
                } else {
                    jsonText
                }
                val retryResponse = jsonConfig.decodeFromString<RetryStrategyResponse>(normalizedJson)
                parseRetryStrategy(retryResponse)
            }
        )
    }
    
    /**
     * 재처리 방안 파싱
     * SRP: 파싱 로직 분리
     */
    private fun parseRetryStrategy(retryResponse: RetryStrategyResponse): RetryStrategy {
        return if (retryResponse.shouldStop || retryResponse.newTree == null) {
            RetryStrategy(
                shouldStop = true,
                reason = retryResponse.reason.ifEmpty { "재처리 불가능" },
                newTree = null
            )
        } else {
            val newTree = ExecutionTreeMapper.toExecutionTree(retryResponse.newTree)
            RetryStrategy(
                shouldStop = false,
                reason = retryResponse.reason.ifEmpty { "재처리 방안 제시" },
                newTree = newTree
            )
        }
    }
    
    /**
     * 이전 실행과 현재 실행을 비교하여 유의미한 변경이 있는지 LLM이 판단
     */
    override suspend fun compareExecutions(
        userQuery: String,
        previousTree: ExecutionTree?,
        previousResult: String,
        currentTree: ExecutionTree,
        currentResult: String
    ): ComparisonResult {
        val prompt = promptBuilder.buildComparisonPrompt(
            userQuery, previousTree, previousResult, currentTree, currentResult
        )
        val schema = JsonSchemaBuilder.buildComparisonResultSchema()
        
        return callLLM(
            prompt = prompt,
            schema = schema,
            responseParser = { jsonText ->
                jsonConfig.decodeFromString<ComparisonResult>(jsonText)
            }
        )
    }
    
    /**
     * 부모 레이어의 실행 결과를 받아서 자식 레이어 함수의 파라미터로 변환
     * 예: 파일생성 레이어가 "file.txt" 반환 -> 인코딩 레이어의 "filePath" 파라미터로 변환
     */
    override suspend fun extractParameters(
        parentResult: String,
        childLayerName: String,
        childFunctionName: String,
        childFunctionDetails: com.hana.orchestrator.layer.FunctionDescription,
        layerDescriptions: List<LayerDescription>
    ): Map<String, Any> {
        val prompt = promptBuilder.buildParameterExtractionPrompt(
            parentResult = parentResult,
            childLayerName = childLayerName,
            childFunctionName = childFunctionName,
            childFunctionDetails = childFunctionDetails,
            layerDescriptions = layerDescriptions
        )
        
        return callLLM(
            prompt = prompt,
            responseParser = { jsonText ->
                val paramsResponse = jsonConfig.decodeFromString<Map<String, String>>(jsonText)
                // String을 적절한 타입으로 변환
                paramsResponse.mapValues { (key, value) ->
                    val paramInfo = childFunctionDetails.parameters[key]
                    when (paramInfo?.type) {
                        "Int", "kotlin.Int" -> value.toIntOrNull() ?: value
                        "Long", "kotlin.Long" -> value.toLongOrNull() ?: value
                        "Double", "kotlin.Double" -> value.toDoubleOrNull() ?: value
                        "Boolean", "kotlin.Boolean" -> value.toBooleanStrictOrNull() ?: value
                        else -> value // String 또는 기타
                    }
                }
            }
        )
    }
    
    /**
     * 레이어로 실행 불가능한 요청에 대해 LLM이 직접 답변할 수 있는지 확인
     */
    override suspend fun checkIfLLMCanAnswerDirectly(userQuery: String): LLMDirectAnswerCapability {
        val prompt = promptBuilder.buildLLMDirectAnswerCapabilityPrompt(userQuery)
        val schema = JsonSchemaBuilder.buildLLMDirectAnswerCapabilitySchema()
        
        return callLLM(
            prompt = prompt,
            schema = schema,
            responseParser = { jsonText ->
                jsonConfig.decodeFromString<LLMDirectAnswerCapability>(jsonText)
            }
        )
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
    
    override suspend fun close() {
        // Ollama 클라이언트 리소스 정리
        ollamaClient.close()
    }
}
