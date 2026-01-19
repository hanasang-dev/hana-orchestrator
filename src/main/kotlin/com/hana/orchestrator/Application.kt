package com.hana.orchestrator

import com.hana.orchestrator.orchestrator.Orchestrator
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChatRequest(val message: String)

@Serializable
data class ChatResponse(val response: List<String>)

fun main() {
    val orchestrator = Orchestrator()
    
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        
        routing {
            get("/health") {
                call.respondText("Hana Orchestrator is running")
            }
            
            post("/chat") {
                try {
                    val request = call.receive<ChatRequest>()
                    val results = orchestrator.intelligentExecute(request.message)
                    call.respond(ChatResponse(results))
                } catch (e: Exception) {
                    call.respond(mapOf("error" to e.message))
                }
            }
            
            get("/layers") {
                try {
                    val descriptions = orchestrator.getAllLayerDescriptions()
                    call.respond(descriptions)
                } catch (e: Exception) {
                    call.respond(mapOf("error" to e.message))
                }
            }
            
            post("/layers/{layerName}/execute") {
                try {
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
    }.start(wait = true)
}