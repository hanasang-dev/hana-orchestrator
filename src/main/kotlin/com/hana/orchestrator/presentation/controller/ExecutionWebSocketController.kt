package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.orchestrator.ApprovalGate
import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import com.hana.orchestrator.presentation.mapper.ExecutionHistoryMapper.toExecutionState
import com.hana.orchestrator.presentation.model.execution.ExecutionUpdateMessage
import com.hana.orchestrator.presentation.model.execution.ProgressUpdate
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest  // used by executionUpdates
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * 실행 이력 실시간 업데이트를 위한 WebSocket 컨트롤러
 * Flow 기반 Observer 패턴으로 상태 변경 시 즉시 전송
 */
class ExecutionWebSocketController(
    private val orchestrator: Orchestrator
) {
    private val logger = createOrchestratorLogger(ExecutionWebSocketController::class.java, null)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val connections = mutableSetOf<DefaultWebSocketSession>()

    fun configureRoutes(route: Route) {
        route.webSocket("/ws/executions") {
            val session = this
            connections.add(session)
            try {
                coroutineScope {
                    // 연결 시 현재 상태 전송
                    sendCurrentState(session)

                    // 실행 상태 업데이트 구독
                    val updateJob = launch {
                        orchestrator.executionUpdates.collectLatest {
                            broadcastToAll(json.encodeToString(createUpdateMessage()))
                        }
                    }

                    // 진행 상태 업데이트 구독
                    val progressJob = launch {
                        orchestrator.progressUpdates.collect { progress ->
                            broadcastToAll(json.encodeToString(progress))
                        }
                    }

                    // 파일 수정 승인 요청 구독
                    val approvalJob = launch {
                        orchestrator.approvalGate.requests.collect { request ->
                            broadcastToAll(json.encodeToString(request))
                        }
                    }

                    // 클라이언트로부터 메시지 수신 대기 (연결 유지)
                    for (frame in incoming) {
                        if (frame is Frame.Text && frame.readText() == "refresh") {
                            sendCurrentState(session)
                        }
                    }

                    updateJob.cancel()
                    progressJob.cancel()
                    approvalJob.cancel()
                }
            } catch (e: ClosedReceiveChannelException) {
                // 연결 종료
            } catch (e: Exception) {
                logger.error("WebSocket error: ${e.message}", e)
            } finally {
                connections.remove(session)
            }
        }
    }

    private suspend fun sendCurrentState(session: DefaultWebSocketSession) {
        try {
            session.send(json.encodeToString(createUpdateMessage()))
        } catch (e: Exception) {
            logger.error("Error sending state: ${e.message}", e)
        }
    }

    private fun createUpdateMessage(): ExecutionUpdateMessage {
        val history = orchestrator.getExecutionHistory(50)
        val current = orchestrator.getCurrentExecution()
        return ExecutionUpdateMessage(
            history = history.map { it.toExecutionState() },
            current = current?.toExecutionState()
        )
    }

    private suspend fun broadcastToAll(message: String) {
        val toRemove = mutableListOf<DefaultWebSocketSession>()
        for (connection in connections) {
            try {
                connection.send(message)
            } catch (e: Exception) {
                toRemove.add(connection)
            }
        }
        connections.removeAll(toRemove)
    }
}
