package com.hana.orchestrator.llm.factory

import com.hana.orchestrator.llm.LLMClient
import com.hana.orchestrator.llm.LLMProvider
import com.hana.orchestrator.llm.OllamaLLMClient
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.llm.LLMTaskComplexity

/**
 * LLM 클라이언트 팩토리
 * 
 * 복잡도별로 클라이언트를 생성하는 책임을 가짐
 * 
 * 설계 목적:
 * 1. 병렬 처리 지원: 필요할 때마다 새로운 클라이언트 인스턴스 생성 가능
 * 2. 확장성: 향후 클라이언트 풀링, 캐싱 등으로 확장 가능
 * 3. SRP: 클라이언트 생성 로직을 한 곳에 집중
 * 4. Provider 기반 확장: 각 복잡도별로 다른 프로바이더 사용 가능
 */
interface LLMClientFactory {
    /**
     * 복잡도에 따라 적절한 LLM 클라이언트를 생성
     * 
     * @param complexity 작업 복잡도
     * @return 새로 생성된 LLM 클라이언트 인스턴스
     */
    fun createClient(complexity: LLMTaskComplexity): LLMClient
    
    /**
     * 간단한 작업용 클라이언트 생성
     */
    fun createSimpleClient(): LLMClient
    
    /**
     * 중간 작업용 클라이언트 생성
     */
    fun createMediumClient(): LLMClient
    
    /**
     * 복잡한 작업용 클라이언트 생성
     */
    fun createComplexClient(): LLMClient
}

/**
 * 기본 LLM 클라이언트 팩토리 구현
 * 
 * Provider에 따라 적절한 클라이언트를 생성
 * 
 * 확장성: 향후 OpenAI, Anthropic 등 추가 시 이 Factory만 수정하면 됨
 */
class DefaultLLMClientFactory(
    private val config: LLMConfig
) : LLMClientFactory {
    
    override fun createClient(complexity: LLMTaskComplexity): LLMClient {
        return when (complexity) {
            LLMTaskComplexity.SIMPLE -> createSimpleClient()
            LLMTaskComplexity.MEDIUM -> createMediumClient()
            LLMTaskComplexity.COMPLEX -> createComplexClient()
        }
    }
    
    override fun createSimpleClient(): LLMClient {
        return createClientByProvider(
            provider = config.simpleProvider,
            modelId = config.simpleModelId,
            contextLength = config.simpleModelContextLength,
            baseUrl = config.simpleModelBaseUrl,
            apiKey = config.simpleApiKey,
            complexityName = "SIMPLE"
        )
    }
    
    override fun createMediumClient(): LLMClient {
        return createClientByProvider(
            provider = config.mediumProvider,
            modelId = config.mediumModelId,
            contextLength = config.mediumModelContextLength,
            baseUrl = config.mediumModelBaseUrl,
            apiKey = config.mediumApiKey,
            complexityName = "MEDIUM"
        )
    }
    
    override fun createComplexClient(): LLMClient {
        return createClientByProvider(
            provider = config.complexProvider,
            modelId = config.complexModelId,
            contextLength = config.complexModelContextLength,
            baseUrl = config.complexModelBaseUrl,
            apiKey = config.complexApiKey,
            complexityName = "COMPLEX"
        )
    }
    
    /**
     * Provider에 따라 적절한 클라이언트 생성
     * 
     * 확장성: 새로운 Provider 추가 시 when 절에만 추가하면 됨
     * 각 복잡도별로 독립적인 설정 사용 (provider, modelId, baseUrl, apiKey)
     */
    private fun createClientByProvider(
        provider: LLMProvider,
        modelId: String,
        contextLength: Long,
        baseUrl: String,
        apiKey: String? = null,
        complexityName: String
    ): LLMClient {
        return when (provider) {
            LLMProvider.OLLAMA -> OllamaLLMClient(
                config = config,
                modelId = modelId,
                contextLength = contextLength,
                baseUrl = baseUrl
            )
            LLMProvider.OPENAI,
            LLMProvider.ANTHROPIC -> {
                // 클라우드 API는 API 키 필수
                if (apiKey == null || apiKey.isBlank()) {
                    val envVarName = "LLM_${complexityName}_API_KEY"
                    throw IllegalArgumentException(
                        "${provider.name} provider 사용 시 $envVarName 환경변수가 필요합니다."
                    )
                }
                throw UnsupportedOperationException(
                    "${provider.name} provider는 아직 지원되지 않습니다. 향후 구현 예정입니다."
                )
            }
        }
    }
}
