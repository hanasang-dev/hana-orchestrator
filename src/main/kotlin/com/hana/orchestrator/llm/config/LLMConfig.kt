package com.hana.orchestrator.llm.config

import io.ktor.server.config.*

/**
 * LLM 모델 설정
 * Ktor의 ApplicationConfig를 통해 application.conf에서 로드
 * 환경변수로 오버라이드 가능
 */
data class LLMConfig(
    /**
     * 간단한 작업용 모델 (validateQueryFeasibility, extractParameters)
     */
    val simpleModelId: String,
    val simpleModelContextLength: Long,
    
    /**
     * 중간 작업용 모델 (evaluateResult, compareExecutions)
     */
    val mediumModelId: String,
    val mediumModelContextLength: Long,
    
    /**
     * 복잡한 작업용 모델 (createExecutionTree, suggestRetryStrategy)
     */
    val complexModelId: String,
    val complexModelContextLength: Long,
    
    /**
     * LLM 호출 타임아웃 (밀리초)
     */
    val timeoutMs: Long
) {
    companion object {
        /**
         * 환경변수에서 직접 로드
         * 환경변수가 없으면 기본값 사용
         */
        fun fromEnvironment(): LLMConfig {
            return LLMConfig(
                simpleModelId = System.getenv("LLM_SIMPLE_MODEL") ?: "qwen3:8b",
                simpleModelContextLength = System.getenv("LLM_SIMPLE_CONTEXT")?.toLongOrNull() ?: 40_960L,
                mediumModelId = System.getenv("LLM_MEDIUM_MODEL") ?: "qwen3:8b",
                mediumModelContextLength = System.getenv("LLM_MEDIUM_CONTEXT")?.toLongOrNull() ?: 40_960L,
                complexModelId = System.getenv("LLM_COMPLEX_MODEL") ?: "qwen3:8b",
                complexModelContextLength = System.getenv("LLM_COMPLEX_CONTEXT")?.toLongOrNull() ?: 40_960L,
                timeoutMs = System.getenv("LLM_TIMEOUT_MS")?.toLongOrNull() ?: 120_000L
            )
        }
        
        /**
         * ApplicationConfig에서 로드
         * 환경변수가 있으면 환경변수 우선 (환경변수로 오버라이드 가능)
         * 
         * @param config Ktor ApplicationConfig
         * @return LLMConfig 인스턴스
         */
        fun fromApplicationConfig(config: ApplicationConfig): LLMConfig {
            val llmConfig = config.config("llm")
            val simpleConfig = llmConfig.config("simple")
            val mediumConfig = llmConfig.config("medium")
            val complexConfig = llmConfig.config("complex")
            
            return LLMConfig(
                // 환경변수 우선, 없으면 application.conf, 없으면 기본값
                simpleModelId = System.getenv("LLM_SIMPLE_MODEL") 
                    ?: simpleConfig.propertyOrNull("modelId")?.getString()
                    ?: "qwen3:8b",
                simpleModelContextLength = System.getenv("LLM_SIMPLE_CONTEXT")?.toLongOrNull()
                    ?: simpleConfig.propertyOrNull("contextLength")?.getString()?.toLongOrNull()
                    ?: 40_960L,
                mediumModelId = System.getenv("LLM_MEDIUM_MODEL")
                    ?: mediumConfig.propertyOrNull("modelId")?.getString()
                    ?: "qwen3:8b",
                mediumModelContextLength = System.getenv("LLM_MEDIUM_CONTEXT")?.toLongOrNull()
                    ?: mediumConfig.propertyOrNull("contextLength")?.getString()?.toLongOrNull()
                    ?: 40_960L,
                complexModelId = System.getenv("LLM_COMPLEX_MODEL")
                    ?: complexConfig.propertyOrNull("modelId")?.getString()
                    ?: "qwen3:8b",
                complexModelContextLength = System.getenv("LLM_COMPLEX_CONTEXT")?.toLongOrNull()
                    ?: complexConfig.propertyOrNull("contextLength")?.getString()?.toLongOrNull()
                    ?: 40_960L,
                timeoutMs = System.getenv("LLM_TIMEOUT_MS")?.toLongOrNull()
                    ?: llmConfig.propertyOrNull("timeoutMs")?.getString()?.toLongOrNull()
                    ?: 120_000L
            )
        }
    }
}
