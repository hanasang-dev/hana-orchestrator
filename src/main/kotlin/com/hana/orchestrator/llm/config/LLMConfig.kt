package com.hana.orchestrator.llm.config

import com.hana.orchestrator.llm.LLMProvider
import io.ktor.server.config.*

/**
 * LLM 모델 설정
 * Ktor의 ApplicationConfig를 통해 application.conf에서 로드
 * 환경변수로 오버라이드 가능
 * 
 * 확장성: 각 복잡도별로 다른 프로바이더 사용 가능 (예: SIMPLE=OLLAMA, MEDIUM=OPENAI)
 * 
 * 각 복잡도별로 독립적인 설정:
 * - SIMPLE 작업: simpleProvider, simpleModelId, simpleBaseUrl, simpleApiKey 사용
 * - MEDIUM 작업: mediumProvider, mediumModelId, mediumBaseUrl, mediumApiKey 사용
 * - COMPLEX 작업: complexProvider, complexModelId, complexBaseUrl, complexApiKey 사용
 */
data class LLMConfig(
    /**
     * 간단한 작업용 모델 (validateQueryFeasibility, extractParameters)
     */
    val simpleProvider: LLMProvider,
    val simpleModelId: String,
    val simpleModelContextLength: Long,
    val simpleModelBaseUrl: String,
    val simpleApiKey: String? = null, // 클라우드 API용 (환경변수로만 설정, 보안)
    
    /**
     * 중간 작업용 모델 (evaluateResult, compareExecutions)
     */
    val mediumProvider: LLMProvider,
    val mediumModelId: String,
    val mediumModelContextLength: Long,
    val mediumModelBaseUrl: String,
    val mediumApiKey: String? = null, // 클라우드 API용 (환경변수로만 설정, 보안)
    
    /**
     * 복잡한 작업용 모델 (createExecutionTree, suggestRetryStrategy)
     */
    val complexProvider: LLMProvider,
    val complexModelId: String,
    val complexModelContextLength: Long,
    val complexModelBaseUrl: String,
    val complexApiKey: String? = null, // 클라우드 API용 (환경변수로만 설정, 보안)
    
    /**
     * LLM 호출 타임아웃 (밀리초)
     */
    val timeoutMs: Long
) {
    companion object {
        // 기본값 상수 (DRY: 중복 제거 및 일관성 유지)
        private const val DEFAULT_SIMPLE_MODEL = "gemma2:2b"
        private const val DEFAULT_MEDIUM_MODEL = "llama3.1:8b"
        private const val DEFAULT_COMPLEX_MODEL = "llama3.1:8b"
        private const val DEFAULT_SIMPLE_CONTEXT_LENGTH = 8_192L
        private const val DEFAULT_MEDIUM_CONTEXT_LENGTH = 128_000L
        private const val DEFAULT_COMPLEX_CONTEXT_LENGTH = 128_000L
        private const val DEFAULT_BASE_URL = "http://localhost:11434"
        private const val DEFAULT_TIMEOUT_MS = 120_000L
        
        /**
         * 환경변수에서 직접 로드
         * 환경변수가 없으면 기본값 사용
         */
        fun fromEnvironment(): LLMConfig {
            return LLMConfig(
                simpleProvider = LLMProvider.fromString(System.getenv("LLM_SIMPLE_PROVIDER")),
                simpleModelId = System.getenv("LLM_SIMPLE_MODEL") ?: DEFAULT_SIMPLE_MODEL,
                simpleModelContextLength = System.getenv("LLM_SIMPLE_CONTEXT")?.toLongOrNull() ?: DEFAULT_SIMPLE_CONTEXT_LENGTH,
                simpleModelBaseUrl = System.getenv("LLM_SIMPLE_BASE_URL") ?: DEFAULT_BASE_URL,
                simpleApiKey = System.getenv("LLM_SIMPLE_API_KEY"),
                mediumProvider = LLMProvider.fromString(System.getenv("LLM_MEDIUM_PROVIDER")),
                mediumModelId = System.getenv("LLM_MEDIUM_MODEL") ?: DEFAULT_MEDIUM_MODEL,
                mediumModelContextLength = System.getenv("LLM_MEDIUM_CONTEXT")?.toLongOrNull() ?: DEFAULT_MEDIUM_CONTEXT_LENGTH,
                mediumModelBaseUrl = System.getenv("LLM_MEDIUM_BASE_URL") ?: DEFAULT_BASE_URL,
                mediumApiKey = System.getenv("LLM_MEDIUM_API_KEY"),
                complexProvider = LLMProvider.fromString(System.getenv("LLM_COMPLEX_PROVIDER")),
                complexModelId = System.getenv("LLM_COMPLEX_MODEL") ?: DEFAULT_COMPLEX_MODEL,
                complexModelContextLength = System.getenv("LLM_COMPLEX_CONTEXT")?.toLongOrNull() ?: DEFAULT_COMPLEX_CONTEXT_LENGTH,
                complexModelBaseUrl = System.getenv("LLM_COMPLEX_BASE_URL") ?: DEFAULT_BASE_URL,
                complexApiKey = System.getenv("LLM_COMPLEX_API_KEY"),
                timeoutMs = System.getenv("LLM_TIMEOUT_MS")?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS
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
                simpleProvider = LLMProvider.fromString(
                    System.getenv("LLM_SIMPLE_PROVIDER")
                        ?: simpleConfig.propertyOrNull("provider")?.getString()
                ),
                simpleModelId = System.getenv("LLM_SIMPLE_MODEL") 
                    ?: simpleConfig.propertyOrNull("modelId")?.getString()
                    ?: DEFAULT_SIMPLE_MODEL,
                simpleModelContextLength = System.getenv("LLM_SIMPLE_CONTEXT")?.toLongOrNull()
                    ?: simpleConfig.propertyOrNull("contextLength")?.getString()?.toLongOrNull()
                    ?: DEFAULT_SIMPLE_CONTEXT_LENGTH,
                simpleModelBaseUrl = System.getenv("LLM_SIMPLE_BASE_URL")
                    ?: simpleConfig.propertyOrNull("baseUrl")?.getString()
                    ?: DEFAULT_BASE_URL,
                simpleApiKey = System.getenv("LLM_SIMPLE_API_KEY"),
                mediumProvider = LLMProvider.fromString(
                    System.getenv("LLM_MEDIUM_PROVIDER")
                        ?: mediumConfig.propertyOrNull("provider")?.getString()
                ),
                mediumModelId = System.getenv("LLM_MEDIUM_MODEL")
                    ?: mediumConfig.propertyOrNull("modelId")?.getString()
                    ?: DEFAULT_MEDIUM_MODEL,
                mediumModelContextLength = System.getenv("LLM_MEDIUM_CONTEXT")?.toLongOrNull()
                    ?: mediumConfig.propertyOrNull("contextLength")?.getString()?.toLongOrNull()
                    ?: DEFAULT_MEDIUM_CONTEXT_LENGTH,
                mediumModelBaseUrl = System.getenv("LLM_MEDIUM_BASE_URL")
                    ?: mediumConfig.propertyOrNull("baseUrl")?.getString()
                    ?: DEFAULT_BASE_URL,
                mediumApiKey = System.getenv("LLM_MEDIUM_API_KEY"),
                complexProvider = LLMProvider.fromString(
                    System.getenv("LLM_COMPLEX_PROVIDER")
                        ?: complexConfig.propertyOrNull("provider")?.getString()
                ),
                complexModelId = System.getenv("LLM_COMPLEX_MODEL")
                    ?: complexConfig.propertyOrNull("modelId")?.getString()
                    ?: DEFAULT_COMPLEX_MODEL,
                complexModelContextLength = System.getenv("LLM_COMPLEX_CONTEXT")?.toLongOrNull()
                    ?: complexConfig.propertyOrNull("contextLength")?.getString()?.toLongOrNull()
                    ?: DEFAULT_COMPLEX_CONTEXT_LENGTH,
                complexModelBaseUrl = System.getenv("LLM_COMPLEX_BASE_URL")
                    ?: complexConfig.propertyOrNull("baseUrl")?.getString()
                    ?: DEFAULT_BASE_URL,
                complexApiKey = System.getenv("LLM_COMPLEX_API_KEY"),
                timeoutMs = System.getenv("LLM_TIMEOUT_MS")?.toLongOrNull()
                    ?: llmConfig.propertyOrNull("timeoutMs")?.getString()?.toLongOrNull()
                    ?: DEFAULT_TIMEOUT_MS
            )
        }
    }
}
