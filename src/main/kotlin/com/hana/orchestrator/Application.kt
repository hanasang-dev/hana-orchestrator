package com.hana.orchestrator

import com.hana.orchestrator.orchestrator.Orchestrator
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.*

@Serializable
data class EchoRequest(val message: String)

@Serializable
data class EchoResponse(val echo: String)

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
            
            post("/echo") {
                try {
                    val request = call.receive<EchoRequest>()
                    val result = orchestrator.executeOnLayer("echo-layer", "echo", mapOf("message" to request.message))
                    call.respond(EchoResponse(result))
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
                    val request = call.receive<EchoRequest>()
                    val result = orchestrator.executeOnLayer(layerName, "echo", mapOf("message" to request.message))
                    call.respond(mapOf("result" to result))
                } catch (e: Exception) {
                    call.respond(mapOf("error" to e.message))
                }
            }
        }
    }.start(wait = true)
}