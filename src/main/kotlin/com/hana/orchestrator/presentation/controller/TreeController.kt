package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.orchestrator.SavedTree
import com.hana.orchestrator.orchestrator.TreeRepository
import com.hana.orchestrator.presentation.mapper.ExecutionTreeMapper.toDomain
import com.hana.orchestrator.presentation.model.execution.ExecutionTreeResponse
import com.hana.orchestrator.presentation.mapper.ExecutionResultMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class TreeActionRequest(
    val query: String,
    val tree: ExecutionTreeResponse
)

@Serializable
data class SaveTreeRequest(
    val name: String,
    val query: String,
    val tree: ExecutionTreeResponse
)

/**
 * 트리 편집기 API 컨트롤러
 * SRP: 사용자가 수정한 트리의 검토/실행/저장/로드 처리 담당
 */
class TreeController(
    private val orchestrator: Orchestrator,
    private val treeRepository: TreeRepository
) {

    fun configureRoutes(route: Route) {
        route.post("/tree/review") {
            try {
                val request = call.receive<TreeActionRequest>()
                val tree = request.tree.toDomain()
                val review = orchestrator.reviewTree(request.query, tree)
                call.respond(review)
            } catch (e: Exception) {
                call.respond(mapOf("error" to e.message))
            }
        }

        route.post("/tree/execute") {
            try {
                val request = call.receive<TreeActionRequest>()
                val tree = request.tree.toDomain()
                val result = orchestrator.executeCustomTree(request.query, tree)
                val response = ExecutionResultMapper.toChatResponse(result)
                call.respond(response)
            } catch (e: Exception) {
                call.respond(mapOf("error" to e.message))
            }
        }

        route.post("/trees/save") {
            try {
                val request = call.receive<SaveTreeRequest>()
                val savedTree = SavedTree(
                    name = request.name,
                    query = request.query,
                    savedAt = System.currentTimeMillis(),
                    tree = request.tree
                )
                treeRepository.save(savedTree)
                call.respond(mapOf("success" to "true", "name" to request.name))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        route.get("/trees") {
            call.respond(treeRepository.list())
        }

        route.get("/trees/{name}") {
            val name = call.parameters["name"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "name required")
            )
            val tree = treeRepository.load(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found: $name"))
            call.respond(tree)
        }
    }
}
