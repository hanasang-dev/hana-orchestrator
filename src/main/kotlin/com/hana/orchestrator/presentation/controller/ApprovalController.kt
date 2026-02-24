package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.orchestrator.ApprovalGate
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * 파일 수정 승인/거절 엔드포인트
 * SRP: 승인 요청에 대한 HTTP 응답 처리만 담당
 */
class ApprovalController(private val approvalGate: ApprovalGate) {

    fun configureRoutes(route: Route) {
        route.post("/approval/{id}/approve") {
            val id = call.parameters["id"]
                ?: return@post call.respond(mapOf("error" to "id required"))
            val success = approvalGate.approve(id)
            call.respond(mapOf("success" to success))
        }

        route.post("/approval/{id}/reject") {
            val id = call.parameters["id"]
                ?: return@post call.respond(mapOf("error" to "id required"))
            val success = approvalGate.reject(id)
            call.respond(mapOf("success" to success))
        }

        route.get("/approval/pending") {
            call.respond(approvalGate.getPending())
        }
    }
}
