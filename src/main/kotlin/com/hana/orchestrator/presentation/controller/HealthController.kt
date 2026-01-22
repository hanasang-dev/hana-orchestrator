package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.application.lifecycle.ApplicationLifecycleManager
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Health 체크 엔드포인트 컨트롤러
 * SRP: Health 관련 HTTP 요청 처리만 담당
 */
class HealthController(
    private val lifecycleManager: ApplicationLifecycleManager
) {
    
    fun configureRoutes(route: Route) {
        route.get("/health") {
            if (lifecycleManager.isShutdownRequested()) {
                call.respond(mapOf("status" to "shutting_down"))
            } else {
                call.respondText("OK")
            }
        }
    }
}
