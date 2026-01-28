package com.hana.orchestrator.llm.factory

import com.hana.orchestrator.llm.LLMClient
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
 * 현재는 매번 새로운 인스턴스를 생성하지만,
 * 향후 풀링이나 캐싱 전략을 추가할 수 있음
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
        return OllamaLLMClient(
            config.simpleModelId,
            config.simpleModelContextLength,
            config.timeoutMs
        )
    }
    
    override fun createMediumClient(): LLMClient {
        return OllamaLLMClient(
            config.mediumModelId,
            config.mediumModelContextLength,
            config.timeoutMs
        )
    }
    
    override fun createComplexClient(): LLMClient {
        return OllamaLLMClient(
            config.complexModelId,
            config.complexModelContextLength,
            config.timeoutMs
        )
    }
}
