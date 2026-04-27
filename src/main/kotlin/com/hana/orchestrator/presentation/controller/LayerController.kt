package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.application.lifecycle.ApplicationLifecycleManager
import com.hana.orchestrator.layer.LayerRequest
import com.hana.orchestrator.layer.LayerResponse
import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.presentation.mapper.ExecutionHistoryMapper.toExecutionHistoryResponse
import com.hana.orchestrator.presentation.model.chat.ChatRequest
import com.hana.orchestrator.presentation.model.execution.ExecutionHistoryListResponse
import com.hana.orchestrator.presentation.model.layer.RegisterRemoteLayerResponse
import io.ktor.http.*
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
        
        // 원격 레이어용 엔드포인트 (RemoteLayer가 호출)
        route.get("/describe") {
            try {
                val description = orchestrator.describe()
                call.respond(description)
            } catch (e: Exception) {
                call.respond(mapOf("error" to e.message))
            }
        }
        
        route.post("/do") {
            try {
                if (lifecycleManager.isShutdownRequested()) {
                    call.respond(LayerResponse(success = false, result = "", error = "Service is shutting down"))
                    return@post
                }
                
                val request = call.receive<LayerRequest>()
                val args = request.arguments.mapValues { it.value as Any }
                
                // function이 레이어 이름인지 확인
                val layerDescriptions = orchestrator.getAllLayerDescriptions()
                val targetLayer = layerDescriptions.find { desc -> desc.name == request.function }
                
                val result = if (targetLayer != null) {
                    // 레이어 이름이면 해당 레이어의 기본 함수 실행
                    val defaultFunction = targetLayer.functions.firstOrNull() ?: "execute"
                    orchestrator.executeOnLayer(request.function, defaultFunction, args)
                } else {
                    // 레이어 이름이 아니면 모든 레이어에서 함수를 찾아서 실행
                    // 먼저 orchestrator 레이어에서 찾기 (원격 레이어가 등록된 경우)
                    val orchestratorLayer = layerDescriptions.find { desc -> desc.name == "orchestrator" }
                    if (orchestratorLayer != null && orchestratorLayer.functions.contains(request.function)) {
                        orchestrator.executeOnLayer("orchestrator", request.function, args)
                    } else {
                        // 다른 레이어에서 함수 찾기
                        val layerWithFunction = layerDescriptions.find { desc -> desc.functions.contains(request.function) }
                        if (layerWithFunction != null) {
                            orchestrator.executeOnLayer(layerWithFunction.name, request.function, args)
                        } else {
                            // 찾지 못하면 Orchestrator의 execute 호출 (레거시 호환)
                            orchestrator.execute(request.function, args)
                        }
                    }
                }
                
                call.respond(LayerResponse(success = true, result = result))
            } catch (e: Exception) {
                call.respond(LayerResponse(success = false, result = "", error = e.message))
            }
        }
        
        // 원격 레이어 등록 엔드포인트
        route.post("/layers/register-remote") {
            try {
                if (lifecycleManager.isShutdownRequested()) {
                    call.respond(mapOf("error" to "Service is shutting down"))
                    return@post
                }
                
                val request = call.receive<Map<String, String>>()
                val baseUrl = request["baseUrl"] ?: return@post call.respond(
                    mapOf("error" to "baseUrl is required")
                )
                
                val remoteLayer = com.hana.orchestrator.layer.LayerFactory.createRemoteLayer(baseUrl)
                orchestrator.registerLayer(remoteLayer)
                
                val description = remoteLayer.describe()
                call.respond(RegisterRemoteLayerResponse(
                    success = true,
                    message = "Remote layer registered",
                    layerName = description.name,
                    baseUrl = baseUrl
                ))
            } catch (e: Exception) {
                call.respond(mapOf("error" to e.message))
            }
        }

        // 실행 이력 조회 엔드포인트
        route.get("/executions") {
            try {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val history = orchestrator.getExecutionHistory(limit)
                val current = orchestrator.getCurrentExecution()

                val historyResponse = history.map { it.toExecutionHistoryResponse() }
                val currentResponse = current?.toExecutionHistoryResponse()

                call.respond(ExecutionHistoryListResponse(
                    history = historyResponse,
                    current = currentResponse
                ))
            } catch (e: Exception) {
                call.respond(mapOf("error" to e.message))
            }
        }

        // 실행 이력 삭제 엔드포인트
        route.delete("/executions/{id}") {
            val id = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
            orchestrator.deleteExecution(id)
            call.respond(mapOf("success" to true))
        }

    }
}
