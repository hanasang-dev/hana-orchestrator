package com.hana.orchestrator.llm.embedding

import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ollama 임베딩 API 클라이언트
 * POST /api/embeddings → FloatArray
 */
class EmbeddingClient(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "nomic-embed-text"
) {
    private val logger = createOrchestratorLogger(EmbeddingClient::class.java, null)

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000L
            connectTimeoutMillis = 5_000L
        }
    }

    @Serializable
    private data class EmbedRequest(val model: String, val prompt: String)

    @Serializable
    private data class EmbedResponse(val embedding: List<Float>)

    /**
     * 텍스트를 임베딩 벡터로 변환
     * @return FloatArray (빈 배열 = 실패)
     */
    suspend fun embed(text: String): FloatArray {
        return try {
            val response = httpClient.post("$baseUrl/api/embeddings") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(EmbedRequest.serializer(), EmbedRequest(model, text)))
            }
            val body = response.bodyAsText()
            val parsed = json.decodeFromString(EmbedResponse.serializer(), body)
            parsed.embedding.toFloatArray()
        } catch (e: Exception) {
            logger.warn("⚠️ [Embedding] embed 실패: ${e.message}")
            FloatArray(0)
        }
    }

    fun close() {
        httpClient.close()
    }
}
