package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.orchestrator.ClarificationGate
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * 사용자 질문 답변 엔드포인트
 * SRP: 질문 요청에 대한 HTTP 응답 처리만 담당
 */
class ClarificationController(private val clarificationGate: ClarificationGate) {

    @Serializable
    private data class AnswerRequest(val answer: String)

    fun configureRoutes(route: Route) {
        route.get("/clarification/pending") {
            call.respond(clarificationGate.getPending())
        }

        route.post("/clarification/{id}/answer") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
            val body = call.receive<AnswerRequest>()
            val success = clarificationGate.answer(id, body.answer)
            call.respond(mapOf("success" to success))
        }
    }
}
