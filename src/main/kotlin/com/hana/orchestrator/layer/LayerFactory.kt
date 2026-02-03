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
     * LLM 레이어 생성
     */
    fun createLLMLayer(modelSelectionStrategy: com.hana.orchestrator.llm.strategy.ModelSelectionStrategy): LLMLayer {
        return LLMLayer(modelSelectionStrategy)
    }
    
    /**
     * 파일 시스템 레이어 생성
     */
    fun createFileSystemLayer(): FileSystemLayer {
        return FileSystemLayer()
    }
    
    /**
     * 모든 기본 레이어 생성
     */
    fun createDefaultLayers(modelSelectionStrategy: com.hana.orchestrator.llm.strategy.ModelSelectionStrategy? = null): List<CommonLayerInterface> {
        val layers = mutableListOf<CommonLayerInterface>(
            createEchoLayer(),
            createTextTransformerLayer(),
            createTextValidatorLayer(),
            createFileSystemLayer()
        )
        
        // LLMLayer는 ModelSelectionStrategy가 필요하므로 선택적으로 추가
        modelSelectionStrategy?.let {
            layers.add(createLLMLayer(it))
        }
        
        return layers
    }
}