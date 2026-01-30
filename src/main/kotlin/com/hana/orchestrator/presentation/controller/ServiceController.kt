package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.application.lifecycle.ApplicationLifecycleManager
import com.hana.orchestrator.presentation.model.service.ServiceStatusResponse
import com.hana.orchestrator.presentation.model.service.LLMStatusResponse
import com.hana.orchestrator.presentation.model.service.LLMProviderStatus
import com.hana.orchestrator.service.ServiceInfo
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.llm.LLMProvider
import kotlinx.coroutines.runBlocking
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service 관련 엔드포인트 컨트롤러
 * SRP: Service 정보 및 Shutdown 요청 처리만 담당
 */
class ServiceController(
    private val serviceInfo: ServiceInfo,
    private val lifecycleManager: ApplicationLifecycleManager,
    private val llmConfig: LLMConfig
) {
    
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
            val simpleStatus = runBlocking {
                checkLLMStatus(
                    provider = llmConfig.simpleProvider,
                    modelId = llmConfig.simpleModelId,
                    baseUrl = llmConfig.simpleModelBaseUrl,
                    apiKey = llmConfig.simpleApiKey
                )
            }
            
            val mediumStatus = runBlocking {
                checkLLMStatus(
                    provider = llmConfig.mediumProvider,
                    modelId = llmConfig.mediumModelId,
                    baseUrl = llmConfig.mediumModelBaseUrl,
                    apiKey = llmConfig.mediumApiKey
                )
            }
            
            val complexStatus = runBlocking {
                checkLLMStatus(
                    provider = llmConfig.complexProvider,
                    modelId = llmConfig.complexModelId,
                    baseUrl = llmConfig.complexModelBaseUrl,
                    apiKey = llmConfig.complexApiKey
                )
            }
            
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
                
                println("🛑 Shutdown requested via API: $reason")
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
                val ready = checkOllamaStatus(baseUrl)
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
            LLMProvider.OPENAI -> {
                val ready = apiKey != null && apiKey.isNotBlank()
                LLMProviderStatus(
                    provider = "OPENAI",
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
            LLMProvider.ANTHROPIC -> {
                val ready = apiKey != null && apiKey.isNotBlank()
                LLMProviderStatus(
                    provider = "ANTHROPIC",
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
    }
    
    /**
     * Ollama 서버 상태 확인
     */
    private suspend fun checkOllamaStatus(baseUrl: String): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/api/tags")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.requestMethod = "GET"
                
                val responseCode = connection.responseCode
                connection.disconnect()
                
                responseCode == 200
            } catch (e: Exception) {
                false
            }
        }
    }
}
