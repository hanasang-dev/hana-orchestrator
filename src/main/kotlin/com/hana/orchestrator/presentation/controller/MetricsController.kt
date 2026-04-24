package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.orchestrator.Orchestrator
import io.ktor.server.response.*
import io.ktor.server.routing.*

class MetricsController(private val orchestrator: Orchestrator) {

    fun configureRoutes(route: Route) {
        route.get("/metrics") {
            call.respond(orchestrator.computeMetrics())
        }
    }
}
