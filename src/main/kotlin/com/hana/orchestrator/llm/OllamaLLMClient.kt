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
import kotlinx.serialization.json.Json

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
    private val contextLength: Long = 40_960L
) {
    // 공통 JSON 설정 (캡슐화)
    private val jsonConfig = Json { 
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    
    // 프롬프트 생성기 (SRP: 프롬프트 생성 책임 분리)
    private val promptBuilder = LLMPromptBuilder()
    
    // 폴백 팩토리 (SRP: 폴백 트리 생성 책임 분리)
    private val fallbackFactory = FallbackTreeFactory()
    
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
     */
    private suspend fun <T> callLLM(
        prompt: String,
        responseParser: (String) -> T,
        fallback: () -> T
    ): T {
        return try {
            val model = createLLMModel()
            val agent = AIAgent(
                promptExecutor = simpleOllamaAIExecutor(),
                llmModel = model
            )
            
            val response = agent.run(prompt)
            val jsonText = JsonExtractor.extract(response)
            responseParser(jsonText)
        } catch (e: Exception) {
            handleLLMError(e)
            fallback()
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
            },
            fallback = {
                fallbackFactory.createFallbackTree(userQuery, layerDescriptions)
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
            },
            fallback = {
                ResultEvaluation(
                    isSatisfactory = false,
                    reason = "LLM 평가 실패",
                    needsRetry = true
                )
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
            },
            fallback = {
                RetryStrategy(
                    shouldStop = true,
                    reason = "LLM 재처리 방안 생성 실패",
                    newTree = null
                )
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
            },
            fallback = {
                ComparisonResult(
                    isSignificantlyDifferent = true,
                    reason = "LLM 비교 실패"
                )
            }
        )
    }
    
    suspend fun close() {
        // 리소스 정리 (현재는 사용하지 않지만 확장성을 위해 유지)
    }
}
