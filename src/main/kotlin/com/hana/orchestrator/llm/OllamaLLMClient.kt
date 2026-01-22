package com.hana.orchestrator.llm

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.hana.orchestrator.orchestrator.ExecutionTree
import com.hana.orchestrator.orchestrator.ExecutionNode
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
     * 사용자 질문과 레이어 정보를 바탕으로 ExecutionTree 구조의 실행 계획을 생성
     */
    suspend fun createExecutionTree(
        userQuery: String,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): ExecutionTree {
        val layersInfo = layerDescriptions.joinToString("\n") { layer ->
            """
            레이어: ${layer.name}
            설명: ${layer.description}
            사용 가능한 함수: ${layer.functions.joinToString(", ")}
            """.trimIndent()
        }

        val prompt = """
        당신은 AI 오케스트레이터입니다. 사용자의 요청에 가장 적절한 레이어와 실행 순서를 트리 구조로 계획해주세요.

        사용자 요청: "$userQuery"

        사용 가능한 레이어들:
        $layersInfo

        다음 JSON 형식으로 응답해주세요:
        {
            "rootNode": {
                "layerName": "레이어이름",
                "function": "함수명",
                "args": {"key": "value"},
                "parallel": false,
                "children": [
                    {
                        "layerName": "자식레이어1",
                        "function": "함수명",
                        "args": {"key": "value"},
                        "parallel": false,
                        "children": []
                    },
                    {
                        "layerName": "자식레이어2",
                        "function": "함수명",
                        "args": {"key": "value"},
                        "parallel": false,
                        "children": []
                    }
                ]
            }
        }
        
        주의사항:
        1. rootNode는 반드시 하나여야 합니다
        2. children 배열로 하위 실행 순서를 정의하세요
        3. parallel이 true면 children들이 병렬 실행됩니다
        4. args에는 "query" 키에 사용자 요청을 포함하세요
        5. JSON 형식만 출력하고 다른 설명은 넣지 마세요
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
            
            // JSON 추출 시도 (응답에 마크다운이나 설명이 포함될 수 있음)
            val jsonText = extractJsonFromResponse(response)
            
            val jsonConfig = Json { 
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }
            
            try {
                val treeResponse = jsonConfig.decodeFromString<ExecutionTreeResponse>(jsonText)
                return treeResponse.toExecutionTree()
            } catch (parseError: Exception) {
                // JSON 파싱 실패 시 재시도 (응답에서 JSON 부분만 추출)
                println("⚠️ JSON 파싱 실패, 재시도 중... 원본 응답: ${response.take(200)}")
                throw parseError
            }
            
        } catch (e: Exception) {
            println("❌ LLM 트리 생성 실패: ${e.message}")
            // 실패 시 기본 트리 생성 (첫 번째 레이어만 실행)
            val firstLayer = layerDescriptions.firstOrNull()
            return if (firstLayer != null) {
                ExecutionTree(
                    rootNode = ExecutionNode(
                        layerName = firstLayer.name,
                        function = firstLayer.functions.firstOrNull() ?: "execute",
                        args = mapOf("query" to userQuery),
                        children = emptyList(),
                        parallel = false,
                        id = "fallback_root"
                    )
                )
            } else {
                ExecutionTree(
                    rootNode = ExecutionNode(
                        layerName = "unknown",
                        function = "execute",
                        args = mapOf("query" to userQuery),
                        children = emptyList(),
                        parallel = false,
                        id = "fallback_unknown"
                    )
                )
            }
        }
    }
    
    /**
     * 레거시 호환성을 위한 메서드 (deprecated)
     */
    @Deprecated("Use createExecutionTree instead")
    suspend fun selectOptimalLayers(
        userQuery: String,
        layerDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): LayerSelectionResult {
        val tree = createExecutionTree(userQuery, layerDescriptions)
        val selectedLayers = collectLayerNames(tree.rootNode)
        return LayerSelectionResult(
            selectedLayers = selectedLayers,
            reasoning = "트리 구조에서 추출",
            executionPlan = "트리 구조대로 실행"
        )
    }
    
    private fun collectLayerNames(node: ExecutionNode): List<String> {
        val result = mutableListOf(node.layerName)
        node.children.forEach { child ->
            result.addAll(collectLayerNames(child))
        }
        return result.distinct()
    }
    
    /**
     * LLM 응답에서 JSON 부분만 추출
     * 마크다운 코드 블록이나 설명 텍스트가 포함될 수 있음
     */
    private fun extractJsonFromResponse(response: String): String {
        // JSON 코드 블록 찾기 (```json ... ```)
        val jsonBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        jsonBlockRegex.find(response)?.let {
            return it.groupValues[1].trim()
        }
        
        // 중괄호로 시작하는 JSON 찾기
        val jsonStart = response.indexOf('{')
        val jsonEnd = response.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1)
        }
        
        // 그 외에는 전체 응답 반환
        return response.trim()
    }
    
    suspend fun close() {
        httpClient.close()
    }
}

@Serializable
data class ExecutionTreeResponse(
    val rootNode: ExecutionNodeResponse
) {
    fun toExecutionTree(): ExecutionTree {
        return ExecutionTree(
            rootNode = rootNode.toExecutionNode(parentPath = "")
        )
    }
}

@Serializable
data class ExecutionNodeResponse(
    val layerName: String,
    val function: String,
    val args: Map<String, String> = emptyMap(),
    val children: List<ExecutionNodeResponse> = emptyList(),
    val parallel: Boolean = false
) {
    fun toExecutionNode(parentPath: String = ""): ExecutionNode {
        val currentPath = if (parentPath.isEmpty()) layerName else "$parentPath/$layerName"
        val nodeId = "node_${currentPath.replace("/", "_")}_${function}"
        
        return ExecutionNode(
            layerName = layerName,
            function = function,
            args = args.mapValues { it.value as Any },
            children = children.mapIndexed { index, child -> 
                child.toExecutionNode("$currentPath[$index]")
            },
            parallel = parallel,
            id = nodeId
        )
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