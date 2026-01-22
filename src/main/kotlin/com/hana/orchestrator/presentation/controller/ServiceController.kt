package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.application.lifecycle.ApplicationLifecycleManager
import com.hana.orchestrator.presentation.model.service.ServiceStatusResponse
import com.hana.orchestrator.service.ServiceInfo
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
    private val lifecycleManager: ApplicationLifecycleManager
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
}
