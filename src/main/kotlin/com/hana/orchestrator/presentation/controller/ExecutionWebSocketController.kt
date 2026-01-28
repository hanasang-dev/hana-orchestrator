package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.presentation.mapper.ExecutionHistoryMapper.toExecutionState
import com.hana.orchestrator.presentation.model.execution.ExecutionState
import com.hana.orchestrator.presentation.model.execution.ExecutionUpdateMessage
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * 실행 이력 실시간 업데이트를 위한 WebSocket 컨트롤러
 * Flow 기반 Observer 패턴으로 상태 변경 시 즉시 전송
 */
class ExecutionWebSocketController(
    private val orchestrator: Orchestrator
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val connections = mutableSetOf<DefaultWebSocketSession>()
    
    fun configureRoutes(route: Route) {
        route.webSocket("/ws/executions") {
            val session = this
            connections.add(session)
            try {
                coroutineScope {
                    // 연결 시 현재 상태 전송
                    sendCurrentState(session)
                    
                    // Flow를 구독하여 실시간 업데이트 수신
                    val updateJob = launch {
                        orchestrator.executionUpdates.collectLatest { updatedHistory ->
                            // 업데이트된 실행 상태를 모든 연결된 클라이언트에 전송
                            broadcastStateUpdate()
                        }
                    }
                    
                    // 클라이언트로부터 메시지 수신 대기 (연결 유지)
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            // 클라이언트가 요청하면 현재 상태 전송
                            if (text == "refresh") {
                                sendCurrentState(session)
                            }
                        }
                    }
                    
                    updateJob.cancel()
                }
            } catch (e: ClosedReceiveChannelException) {
                // 연결 종료
            } catch (e: Exception) {
                println("WebSocket error: ${e.message}")
            } finally {
                connections.remove(session)
            }
        }
    }
    
    private suspend fun sendCurrentState(session: DefaultWebSocketSession) {
        try {
            val message = createUpdateMessage()
            session.send(json.encodeToString(message))
        } catch (e: Exception) {
            println("Error sending state: ${e.message}")
        }
    }
    
    /**
     * 상태 업데이트를 모든 연결된 클라이언트에 브로드캐스트
     */
    private suspend fun broadcastStateUpdate() {
        val message = json.encodeToString(createUpdateMessage())
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
    
    /**
     * 현재 상태를 기반으로 업데이트 메시지 생성
     */
    private fun createUpdateMessage(): ExecutionUpdateMessage {
        val limit = 50
        val history = orchestrator.getExecutionHistory(limit)
        val current = orchestrator.getCurrentExecution()
        
        return ExecutionUpdateMessage(
            history = history.map { it.toExecutionState() },
            current = current?.toExecutionState()
        )
    }
}

