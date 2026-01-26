package com.hana.orchestrator.layer

import io.ktor.client.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
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
     * Echo 레이어 생성
     */
    fun createEchoLayer(): EchoLayer {
        return EchoLayer()
    }
    
    /**
     * TextGenerator 레이어 생성
     */
    fun createTextGeneratorLayer(): TextGeneratorLayer {
        return TextGeneratorLayer()
    }
    
    /**
     * TextTransformer 레이어 생성
     */
    fun createTextTransformerLayer(): TextTransformerLayer {
        return TextTransformerLayer()
    }
    
    /**
     * TextValidator 레이어 생성
     */
    fun createTextValidatorLayer(): TextValidatorLayer {
        return TextValidatorLayer()
    }
    
    /**
     * 모든 기본 레이어 생성
     */
    fun createDefaultLayers(): List<CommonLayerInterface> {
        return listOf(
            createEchoLayer(),
            createTextGeneratorLayer(),
            createTextTransformerLayer(),
            createTextValidatorLayer()
        )
    }
}