package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.application.lifecycle.ApplicationLifecycleManager
import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.presentation.model.chat.ChatRequest
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Layer 관련 엔드포인트 컨트롤러
 * SRP: Layer 관련 HTTP 요청 처리만 담당
 */
class LayerController(
    private val orchestrator: Orchestrator,
    private val lifecycleManager: ApplicationLifecycleManager
) {
    
    fun configureRoutes(route: Route) {
        route.get("/layers") {
            try {
                val descriptions = orchestrator.getAllLayerDescriptions()
                call.respond(descriptions)
            } catch (e: Exception) {
                call.respond(mapOf("error" to e.message))
            }
        }
        
        route.post("/layers/{layerName}/execute") {
            try {
                if (lifecycleManager.isShutdownRequested()) {
                    call.respond(mapOf("error" to "Service is shutting down"))
                    return@post
                }
                
                val layerName = call.parameters["layerName"] ?: return@post call.respond(
                    mapOf("error" to "Layer name is required")
                )
                val request = call.receive<ChatRequest>()
                val result = orchestrator.executeOnLayer(layerName, "echo", mapOf("message" to request.message))
                call.respond(mapOf("result" to result))
            } catch (e: Exception) {
                call.respond(mapOf("error" to e.message))
            }
        }
    }
}
