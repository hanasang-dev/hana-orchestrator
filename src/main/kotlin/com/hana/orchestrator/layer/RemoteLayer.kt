package com.hana.orchestrator.layer

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/**
 * 원격 레이어 구현 (HTTP 기반)
 * 오케스트레이터가 다른 서버의 레이어와 통신할 때 사용
 */
class RemoteLayer(
    val baseUrl: String,
    private val httpClient: HttpClient
) : CommonLayerInterface {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun describe(): LayerDescription {
        try {
            val response = httpClient.get("$baseUrl/describe").bodyAsText()
            return json.decodeFromString(LayerDescription.serializer(), response)
        } catch (e: Exception) {
            throw LayerExecutionException("Failed to describe layer: ${e.message}")
        }
    }

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        val requestBody = LayerRequest(function, args.mapValues { it.value.toString() })

        try {
            val response = httpClient.post("$baseUrl/do") {
                setBody(requestBody)
                contentType(ContentType.Application.Json)
            }.bodyAsText()

            val layerResponse = json.decodeFromString(LayerResponse.serializer(), response)
            return if (layerResponse.success) {
                layerResponse.result ?: "" // Handle null result
            } else {
                throw LayerExecutionException(layerResponse.error ?: "Unknown error")
            }
        } catch (e: Exception) {
            throw LayerExecutionException("Failed to execute function: ${e.message}")
        }
    }
}

/**
 * 레이어 실행 중 발생하는 예외
 */
class LayerExecutionException(message: String) : Exception(message)