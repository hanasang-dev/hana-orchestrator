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
    private val baseUrl: String,
    private val httpClient: HttpClient
) : CommonLayerInterface {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    override suspend fun describe(): LayerDescription {
        val response = httpClient.get("$baseUrl/describe").bodyAsText()
        return json.decodeFromString(LayerDescription.serializer(), response)
    }
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        val stringArgs = args.mapValues { it.value.toString() }
        val request = LayerRequest(function, stringArgs)
        val response = httpClient.post("$baseUrl/do") {
            setBody(request)
            contentType(ContentType.Application.Json)
        }.bodyAsText()
        
        val layerResponse = json.decodeFromString(LayerResponse.serializer(), response)
        return if (layerResponse.success) {
            layerResponse.result
        } else {
            throw LayerExecutionException(layerResponse.error ?: "Unknown error")
        }
    }
}

/**
 * 레이어 실행 중 발생하는 예외
 */
class LayerExecutionException(message: String) : Exception(message)