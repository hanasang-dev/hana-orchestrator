package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.application.lifecycle.ApplicationLifecycleManager
import com.hana.orchestrator.domain.dto.ChatDto
import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.presentation.mapper.ChatRequestMapper
import com.hana.orchestrator.presentation.mapper.ExecutionResultMapper
import com.hana.orchestrator.presentation.model.chat.ChatRequest
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Chat 엔드포인트 컨트롤러
 * SRP: Chat 관련 HTTP 요청 처리만 담당
 */
class ChatController(
    private val orchestrator: Orchestrator,
    private val lifecycleManager: ApplicationLifecycleManager
) {
    
    fun configureRoutes(route: Route) {
        route.post("/chat") {
            try {
                if (lifecycleManager.isShutdownRequested()) {
                    call.respond(mapOf("error" to "Service is shutting down"))
                    return@post
                }
                
                val request = call.receive<ChatRequest>()
                // Presentation → Domain 변환
                val chatDto = ChatRequestMapper.toDto(request)
                
                // Orchestrator 실행 (도메인 모델 반환)
                val executionResult = orchestrator.executeOrchestration(chatDto.message)
                
                // Domain → Presentation 변환
                val response = ExecutionResultMapper.toChatResponse(executionResult)
                call.respond(response)
            } catch (e: Exception) {
                call.respond(mapOf("error" to e.message))
            }
        }
    }
}
