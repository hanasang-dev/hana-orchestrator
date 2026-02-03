package com.hana.orchestrator.llm

import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.message.Message
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.domain.entity.ExecutionContext
import com.hana.orchestrator.data.model.response.ExecutionTreeResponse
import com.hana.orchestrator.data.mapper.ExecutionTreeMapper
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.layer.LayerDescription
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withTimeout

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
     * 성능 최적화: 단순하고 빠른 호출, 불필요한 재시도 제거
     */
    private suspend fun <T> callLLM(
        prompt: String,
        responseParser: (String) -> T,
        timeoutMs: Long = this.timeoutMs
    ): T {
        val model = createLLMModel()
        val promptDsl = Prompt.build(id = "llm-call") {
            user(prompt)
        }
        
        // 단순한 1회 시도 - 재시도는 상위 레벨에서 처리
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
        return try {
            responseParser(jsonText)
        } catch (e: Exception) {
            throw Exception("LLM 응답 파싱 실패: ${e.message}", e)
        }
    }
    
    /**
     * 요구사항이 레이어 기능으로 수행 가능한지 사전 검증
     */
    override suspend fun validateQueryFeasibility(
        userQuery: String,
        layerDescriptions: List<LayerDescription>
    ): QueryFeasibility {
        val prompt = promptBuilder.buildFeasibilityCheckPrompt(userQuery, layerDescriptions)
        
        return callLLM(
            prompt = prompt,
            responseParser = { jsonText ->
                jsonConfig.decodeFromString<QueryFeasibility>(jsonText)
            }
        )
    }
    
    /**
     * 사용자 질문과 레이어 정보를 바탕으로 ExecutionTree 구조의 실행 계획을 생성
     */
    override suspend fun createExecutionTree(
        userQuery: String,
        layerDescriptions: List<LayerDescription>
    ): ExecutionTree {
        val prompt = promptBuilder.buildExecutionTreePrompt(userQuery, layerDescriptions)
        
        return callLLM(
            prompt = prompt,
            responseParser = { jsonText ->
                val treeResponse = jsonConfig.decodeFromString<ExecutionTreeResponse>(jsonText)
                ExecutionTreeMapper.toExecutionTree(treeResponse)
            }
        )
    }
    
    /**
     * 실행 결과가 사용자 요구사항에 부합하는지 LLM이 판단
     */
    override suspend fun evaluateResult(
        userQuery: String,
        executionResult: String,
        executionContext: ExecutionContext?
    ): ResultEvaluation {
        val prompt = promptBuilder.buildEvaluationPrompt(userQuery, executionResult, executionContext)
        
        return callLLM(
            prompt = prompt,
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
        layerDescriptions: List<LayerDescription>
    ): RetryStrategy {
        val prompt = promptBuilder.buildRetryStrategyPrompt(userQuery, previousHistory, layerDescriptions)
        
        return callLLM(
            prompt = prompt,
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
        
        return callLLM(
            prompt = prompt,
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
        
        return callLLM(
            prompt = prompt,
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
