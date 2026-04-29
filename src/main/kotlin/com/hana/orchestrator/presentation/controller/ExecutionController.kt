package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.orchestrator.Orchestrator
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * 실행 관리 컨트롤러
 * SRP: 실행 취소 등 실행 상태 조작만 담당
 */
class ExecutionController(private val orchestrator: Orchestrator) {

    fun configureRoutes(route: Route) {
        route.post("/execution/current/cancel") {
            val cancelled = orchestrator.cancelCurrentExecution()
            call.respond(mapOf("cancelled" to cancelled))
        }
    }
}
