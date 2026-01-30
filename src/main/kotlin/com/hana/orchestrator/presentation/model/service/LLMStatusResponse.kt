package com.hana.orchestrator.presentation.model.service

import kotlinx.serialization.Serializable

/**
 * LLM Client 상태 응답
 * 확장성: 각 복잡도별로 다른 provider 사용 가능하므로 각각의 상태를 확인
 */
@Serializable
data class LLMStatusResponse(
    /**
     * 각 복잡도별 LLM 상태
     */
    val simple: LLMProviderStatus,
    val medium: LLMProviderStatus,
    val complex: LLMProviderStatus,
    
    /**
     * 모든 LLM이 준비되었는지 여부
     */
    val allReady: Boolean
)

/**
 * 특정 복잡도별 LLM Provider 상태
 */
@Serializable
data class LLMProviderStatus(
    /**
     * Provider 타입 (OLLAMA, OPENAI, ANTHROPIC 등)
     */
    val provider: String,
    
    /**
     * 사용 중인 모델 ID
     */
    val modelId: String,
    
    /**
     * LLM이 준비되었는지 여부
     */
    val ready: Boolean,
    
    /**
     * 준비되지 않은 경우 이유
     */
    val reason: String? = null,
    
    /**
     * 추가 정보 (예: Ollama의 경우 baseUrl, 클라우드 API의 경우 API 키 존재 여부 등)
     */
    val details: Map<String, String> = emptyMap()
)
