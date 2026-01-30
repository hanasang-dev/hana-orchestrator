package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.application.lifecycle.ApplicationLifecycleManager
import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import com.hana.orchestrator.presentation.model.service.ServiceStatusResponse
import com.hana.orchestrator.presentation.model.service.LLMStatusResponse
import com.hana.orchestrator.presentation.model.service.LLMProviderStatus
import com.hana.orchestrator.service.ServiceInfo
import com.hana.orchestrator.service.OllamaHealthChecker
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.llm.LLMProvider
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Service 관련 엔드포인트 컨트롤러
 * SRP: Service 정보 및 Shutdown 요청 처리만 담당
 */
class ServiceController(
    private val serviceInfo: ServiceInfo,
    private val lifecycleManager: ApplicationLifecycleManager,
    private val llmConfig: LLMConfig
) {
    private val logger = createOrchestratorLogger(ServiceController::class.java, null)
    
    fun configureRoutes(route: Route) {
        // 서비스 정보 엔드포인트
        route.get("/service-info") {
            call.respond(serviceInfo)
        }
        
        // 상태 엔드포인트
        route.get("/status") {
            val uptime = System.currentTimeMillis() - serviceInfo.startTime
            val status = ServiceStatusResponse(
                id = serviceInfo.id,
                name = serviceInfo.name,
                port = serviceInfo.port,
                uptime = uptime,
                status = if (lifecycleManager.isShutdownRequested()) "shutting_down" else "running"
            )
            call.respond(status)
        }
        
        // LLM 상태 엔드포인트 (확장성: 각 복잡도별 provider 상태 확인)
        route.get("/llm-status") {
            val simpleStatus = checkLLMStatus(
                provider = llmConfig.simpleProvider,
                modelId = llmConfig.simpleModelId,
                baseUrl = llmConfig.simpleModelBaseUrl,
                apiKey = llmConfig.simpleApiKey
            )
            
            val mediumStatus = checkLLMStatus(
                provider = llmConfig.mediumProvider,
                modelId = llmConfig.mediumModelId,
                baseUrl = llmConfig.mediumModelBaseUrl,
                apiKey = llmConfig.mediumApiKey
            )
            
            val complexStatus = checkLLMStatus(
                provider = llmConfig.complexProvider,
                modelId = llmConfig.complexModelId,
                baseUrl = llmConfig.complexModelBaseUrl,
                apiKey = llmConfig.complexApiKey
            )
            
            call.respond(LLMStatusResponse(
                simple = simpleStatus,
                medium = mediumStatus,
                complex = complexStatus,
                allReady = simpleStatus.ready && mediumStatus.ready && complexStatus.ready
            ))
        }
        
        // 하위 호환성: docker-status 엔드포인트를 llm-status로 리다이렉트
        route.get("/docker-status") {
            call.respond(mapOf(
                "message" to "이 엔드포인트는 더 이상 사용되지 않습니다. /llm-status를 사용하세요.",
                "redirect" to "/llm-status"
            ))
        }
        
        // 그레이스풀 셧다운 엔드포인트
        route.post("/shutdown") {
            try {
                val request = call.receive<Map<String, String>>()
                val reason = request["reason"] ?: "API request"
                
                logger.info("🛑 Shutdown requested via API: $reason")
                lifecycleManager.requestShutdown()
                
                // runServer의 루프가 종료되면 자동으로 gracefulShutdownAsync가 호출됨
                // 여기서는 shutdown 요청만 하고 응답을 반환
                
                call.respond(mapOf(
                    "message" to "Shutdown initiated",
                    "reason" to reason,
                    "serviceId" to serviceInfo.id
                ))
            } catch (e: Exception) {
                call.respond(mapOf("error" to e.message))
            }
        }
    }
    
    /**
     * LLM Provider 상태 확인
     * 확장성: 새로운 Provider 추가 시 when 절에만 추가하면 됨
     */
    private suspend fun checkLLMStatus(
        provider: LLMProvider,
        modelId: String,
        baseUrl: String,
        apiKey: String?
    ): LLMProviderStatus {
        return when (provider) {
            LLMProvider.OLLAMA -> {
                val ready = OllamaHealthChecker.isReady(baseUrl)
                LLMProviderStatus(
                    provider = "OLLAMA",
                    modelId = modelId,
                    ready = ready,
                    reason = if (!ready) "Ollama 서버에 연결할 수 없습니다" else null,
                    details = mapOf(
                        "baseUrl" to baseUrl,
                        "modelId" to modelId
                    )
                )
            }
            LLMProvider.OPENAI,
            LLMProvider.ANTHROPIC -> {
                // 클라우드 API는 API 키 존재 여부로 준비 상태 확인
                checkCloudApiStatus(provider.name, modelId, baseUrl, apiKey)
            }
        }
    }
    
    /**
     * 클라우드 API Provider 상태 확인 (공통 로직)
     * DRY: OPENAI와 ANTHROPIC의 중복 로직 추출
     */
    private fun checkCloudApiStatus(
        providerName: String,
        modelId: String,
        baseUrl: String,
        apiKey: String?
    ): LLMProviderStatus {
        val ready = apiKey != null && apiKey.isNotBlank()
        return LLMProviderStatus(
            provider = providerName,
            modelId = modelId,
            ready = ready,
            reason = if (!ready) "API 키가 설정되지 않았습니다" else null,
            details = mapOf(
                "baseUrl" to baseUrl,
                "modelId" to modelId,
                "apiKeySet" to (if (ready) "true" else "false")
            )
        )
    }
    
}
