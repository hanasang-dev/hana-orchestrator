package com.hana.orchestrator.llm

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.data.model.response.ExecutionTreeResponse
import com.hana.orchestrator.data.mapper.ExecutionTreeMapper
import com.hana.orchestrator.llm.config.LLMConfig
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Ollama 로컬 LLM 통신을 위한 모듈
 * SRP: LLM 통신 및 응답 파싱만 담당
 * 
 * OOP 원칙:
 * - SRP: LLM 통신 책임만 담당, 프롬프트 생성/폴백 처리는 별도 클래스로 분리
 * - DRY: 공통 LLM 호출 로직 추출
 * - 캡슐화: 내부 구현 세부사항 숨김
 */
class OllamaLLMClient(
    private val modelId: String = "qwen3:8b",
    private val contextLength: Long = 40_960L,
    private val timeoutMs: Long = 120_000L
) {
    /**
     * 설정 기반 생성자
     */
    constructor(config: LLMConfig, modelId: String, contextLength: Long) : this(
        modelId = modelId,
        contextLength = contextLength,
        timeoutMs = config.timeoutMs
    )
    // 공통 JSON 설정 (캡슐화)
    private val jsonConfig = Json { 
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    
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
     * DRY: 반복되는 호출 패턴 공통화
     * Template Method 패턴 적용
     * 타임아웃: 120초로 제한하여 무한 대기 방지
     * 실패 시 예외를 throw하여 폴백 없이 실패 처리
     */
    private suspend fun <T> callLLM(
        prompt: String,
        responseParser: (String) -> T,
        timeoutMs: Long = this.timeoutMs
    ): T {
        val model = createLLMModel()
        val agent = AIAgent(
            promptExecutor = simpleOllamaAIExecutor(),
            llmModel = model
        )
        
        val response = try {
            withTimeout(timeoutMs) {
                agent.run(prompt)
            }
        } catch (e: TimeoutCancellationException) {
            println("⏱️ LLM 호출 타임아웃 (${timeoutMs}ms 초과)")
            handleLLMError(e)
            throw Exception("LLM 호출 타임아웃: ${timeoutMs}ms 초과", e)
        } catch (e: Exception) {
            handleLLMError(e)
            throw Exception("LLM 호출 실패: ${e.message}", e)
        }
        
        val jsonText = JsonExtractor.extract(response)
        return try {
            responseParser(jsonText)
        } catch (e: Exception) {
            throw Exception("LLM 응답 파싱 실패: ${e.message}", e)
        }
    }
    
    /**
     * LLM 에러 처리
     * SRP: 에러 처리 로직 분리
     */
    private fun handleLLMError(error: Exception) {
        println("❌ LLM 호출 실패: ${error.message}")
    }
    
    /**
     * 요구사항이 레이어 기능으로 수행 가능한지 사전 검증
     */
    suspend fun validateQueryFeasibility(
        userQuery: String,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
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
    suspend fun createExecutionTree(
        userQuery: String,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
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
    suspend fun evaluateResult(
        userQuery: String,
        executionResult: String,
        executionContext: com.hana.orchestrator.domain.entity.ExecutionContext?
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
    suspend fun suggestRetryStrategy(
        userQuery: String,
        previousHistory: ExecutionHistory,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): RetryStrategy {
        val prompt = promptBuilder.buildRetryStrategyPrompt(userQuery, previousHistory, layerDescriptions)
        
        return callLLM(
            prompt = prompt,
            responseParser = { jsonText ->
                val retryResponse = jsonConfig.decodeFromString<RetryStrategyResponse>(jsonText)
                parseRetryStrategy(retryResponse)
            }
        )
    }
    
    /**
     * 재처리 방안 파싱
     * SRP: 파싱 로직 분리
     */
    private fun parseRetryStrategy(retryResponse: RetryStrategyResponse): RetryStrategy {
        return if (retryResponse.shouldStop) {
            RetryStrategy(
                shouldStop = true,
                reason = retryResponse.reason,
                newTree = null
            )
        } else {
            val newTree = ExecutionTreeMapper.toExecutionTree(retryResponse.newTree)
            RetryStrategy(
                shouldStop = false,
                reason = retryResponse.reason,
                newTree = newTree
            )
        }
    }
    
    /**
     * 이전 실행과 현재 실행을 비교하여 유의미한 변경이 있는지 LLM이 판단
     */
    suspend fun compareExecutions(
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
    suspend fun extractParameters(
        parentResult: String,
        childLayerName: String,
        childFunctionName: String,
        childFunctionDetails: com.hana.orchestrator.layer.FunctionDescription,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
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
    
    suspend fun close() {
        // 리소스 정리 (현재는 사용하지 않지만 확장성을 위해 유지)
    }
}
