package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.domain.entity.JobSchedule
import com.hana.orchestrator.domain.entity.ScheduledJob
import com.hana.orchestrator.orchestrator.JobRepository
import com.hana.orchestrator.orchestrator.JobScheduler
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CreateJobRequest(
    val name: String,
    val query: String,
    val treeId: String? = null,
    val schedule: JobSchedule,
    val enabled: Boolean = true,
    val includeMetrics: Boolean = false
)

@Serializable
data class UpdateJobRequest(
    val name: String? = null,
    val query: String? = null,
    val treeId: String? = null,
    val schedule: JobSchedule? = null,
    val enabled: Boolean? = null,
    val includeMetrics: Boolean? = null
)

/**
 * 스케줄 작업 CRUD + 수동 트리거 컨트롤러
 */
class SchedulerController(
    private val jobRepository: JobRepository,
    private val jobScheduler: JobScheduler
) {

    fun configureRoutes(route: Route) {

        // 목록 조회
        route.get("/jobs") {
            call.respond(jobRepository.list())
        }

        // 단건 조회
        route.get("/jobs/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
            val job = jobRepository.load(id) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
            call.respond(job)
        }

        // 생성
        route.post("/jobs") {
            val req = call.receive<CreateJobRequest>()
            val job = ScheduledJob(
                id = UUID.randomUUID().toString(),
                name = req.name,
                query = req.query,
                treeId = req.treeId,
                schedule = req.schedule,
                enabled = req.enabled,
                includeMetrics = req.includeMetrics
            )
            val scheduled = jobScheduler.scheduleNext(job)
            jobRepository.save(scheduled)
            call.respond(HttpStatusCode.Created, scheduled)
        }

        // 수정
        route.patch("/jobs/{id}") {
            val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
            val existing = jobRepository.load(id) ?: return@patch call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
            val req = call.receive<UpdateJobRequest>()
            val updated = existing.copy(
                name = req.name ?: existing.name,
                query = req.query ?: existing.query,
                treeId = if (req.treeId != null) req.treeId else existing.treeId,
                schedule = req.schedule ?: existing.schedule,
                enabled = req.enabled ?: existing.enabled,
                includeMetrics = req.includeMetrics ?: existing.includeMetrics
            )
            val rescheduled = if (req.schedule != null) jobScheduler.scheduleNext(updated) else updated
            jobRepository.save(rescheduled)
            call.respond(rescheduled)
        }

        // 삭제
        route.delete("/jobs/{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
            jobRepository.delete(id)
            call.respond(mapOf("success" to true))
        }

        // 수동 즉시 실행
        route.post("/jobs/{id}/trigger") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
            val triggered = jobScheduler.triggerNow(id)
            if (triggered) call.respond(mapOf("triggered" to true))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
        }
    }
}
