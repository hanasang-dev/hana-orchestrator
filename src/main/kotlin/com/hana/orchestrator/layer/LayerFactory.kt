package com.hana.orchestrator.layer

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * 레이어 관리를 위한 팩토리 객체
 */
object LayerFactory {
    
    /**
     * HTTP 클라이언트 생성
     */
    fun createHttpClient(): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }
    
    /**
     * 원격 레이어 생성
     */
    fun createRemoteLayer(baseUrl: String): RemoteLayer {
        return RemoteLayer(baseUrl, createHttpClient())
    }
    
    /**
     * 파일 처리 레이어 생성
     */
    fun createFileProcessorLayer(): FileProcessorLayer {
        return FileProcessorLayer()
    }
    
    /**
     * 모든 기본 레이어 생성
     */
    fun createDefaultLayers(): List<CommonLayerInterface> {
        return listOf(
            createFileProcessorLayer()
        )
    }
}