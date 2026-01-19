package com.hana.orchestrator.llm

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.ktor.client.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ollama 로컬 LLM 통신을 위한 모듈
 */
class OllamaLLMClient(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "llama3.2"
) {
    private val httpClient = HttpClient() {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
        }
    }

    /**
     * 사용자 질문과 레이어 정보를 바탕으로 적절한 레이어 선택을 요청
     */
    suspend fun selectOptimalLayers(
        userQuery: String,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): LayerSelectionResult {
        val layersInfo = layerDescriptions.joinToString("\n") { layer ->
            """
            레이어: ${layer.name}
            설명: ${layer.description}
            실행 순서: ${layer.layerDepth}
            사용 가능한 함수: ${layer.functions.joinToString(", ")}
            """.trimIndent()
        }

        val prompt = """
        당신은 AI 오케스트레이터입니다. 사용자의 요청에 가장 적절한 레이어와 실행 순서를 선택해주세요.

        사용자 요청: "$userQuery"

        사용 가능한 레이어들:
        $layersInfo

        다음 JSON 형식으로 응답해주세요:
        {
            "selectedLayers": ["레이어이름1", "레이어이름2"],
            "reasoning": "선택 이유",
            "executionPlan": "실행 계획"
        }
        
        주의사항:
        1. layerDepth가 낮은 순서대로 정렬하세요
        2. 꼭 필요한 레이어만 선택하세요
        3. JSON 형식만 출력하고 다른 설명은 넣지 마세요
        """.trimIndent()

        try {
            val customModel = LLModel(
                provider = LLMProvider.Ollama,
                id = "qwen3:8b",
                capabilities = listOf(
                    LLMCapability.Temperature,
                    LLMCapability.Schema.JSON.Basic,
                    LLMCapability.Tools
                ),
                contextLength = 40_960
            )

            val agent = AIAgent(
                promptExecutor = simpleOllamaAIExecutor(),
                llmModel = customModel
            )
            
            val response = agent.run(prompt)
            
            val jsonConfig = Json { ignoreUnknownKeys = true; isLenient = true }
            return jsonConfig.decodeFromString<LayerSelectionResponse>(response).toResult()
            
        } catch (e: Exception) {
            return LayerSelectionResult(
                selectedLayers = layerDescriptions.sortedBy { it.layerDepth }.map { it.name },
                reasoning = "LLM 통신 실패로 인한 기본 동작",
                executionPlan = "모든 레이어를 layerDepth 순서대로 실행"
            )
        }
    }
    
    suspend fun close() {
        httpClient.close()
    }
}

@Serializable
data class LayerSelectionResponse(
    val selectedLayers: List<String>,
    val reasoning: String,
    val executionPlan: String
) {
    fun toResult(): LayerSelectionResult {
        return LayerSelectionResult(
            selectedLayers = selectedLayers,
            reasoning = reasoning,
            executionPlan = executionPlan
        )
    }
}

data class LayerSelectionResult(
    val selectedLayers: List<String>,
    val reasoning: String,
    val executionPlan: String
)