package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.orchestrator.Orchestrator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * 세션 관리 REST 컨트롤러
 * SRP: 세션 CRUD 요청만 담당. SessionLayer에 위임.
 */
class SessionController(private val orchestrator: Orchestrator) {

    fun configureRoutes(route: Route) {

        // 세션 목록
        route.get("/sessions") {
            val result = orchestrator.executeOnLayer("session", "listSessions")
            call.respond(mapOf("sessions" to result))
        }

        // 현재 활성 세션 상세
        route.get("/sessions/current") {
            val result = orchestrator.executeOnLayer("session", "getSession")
            if (result.startsWith("ERROR")) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to result))
            } else {
                call.respond(mapOf("session" to result))
            }
        }

        // 새 세션 생성
        route.post("/sessions") {
            val result = orchestrator.executeOnLayer("session", "createSession")
            call.respond(mapOf("result" to result))
        }

        // 세션 활성화
        route.post("/sessions/{id}/activate") {
            val sessionId = call.parameters["id"] ?: return@post call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "sessionId 필수")
            )
            val result = orchestrator.executeOnLayer("session", "activateSession", mapOf("sessionId" to sessionId))
            if (result.startsWith("ERROR")) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to result))
            } else {
                call.respond(mapOf("result" to result))
            }
        }

        // 세션 초기화 (tasks 삭제, ID 유지)
        route.delete("/sessions/{id}/clear") {
            val sessionId = call.parameters["id"] ?: return@delete call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "sessionId 필수")
            )
            val result = orchestrator.executeOnLayer("session", "clearSession", mapOf("sessionId" to sessionId))
            call.respond(mapOf("result" to result))
        }

        // 세션 삭제
        route.delete("/sessions/{id}") {
            val sessionId = call.parameters["id"] ?: return@delete call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "sessionId 필수")
            )
            val result = orchestrator.executeOnLayer("session", "deleteSession", mapOf("sessionId" to sessionId))
            call.respond(mapOf("result" to result))
        }
    }
}
