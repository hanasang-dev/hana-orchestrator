package com.hana.orchestrator.application.server

import com.hana.orchestrator.application.lifecycle.ApplicationLifecycleManager
import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.presentation.controller.ChatController
import com.hana.orchestrator.presentation.controller.HealthController
import com.hana.orchestrator.presentation.controller.LayerController
import com.hana.orchestrator.presentation.controller.ServiceController
import com.hana.orchestrator.service.ServiceInfo
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json

/**
 * Ktor 서버 설정 및 라우팅 구성
 * SRP: 서버 설정 및 라우팅만 담당
 */
class ServerConfigurator(
    private val port: Int,
    private val orchestrator: Orchestrator,
    private val serviceInfo: ServiceInfo,
    private val lifecycleManager: ApplicationLifecycleManager,
    private val applicationScope: CoroutineScope,
    private val shutdownCallback: suspend () -> Unit
) {
    
    fun createServer(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        return embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            
            routing {
                // 컨트롤러들 설정
                HealthController(lifecycleManager).configureRoutes(this)
                ServiceController(serviceInfo, lifecycleManager, applicationScope, shutdownCallback, orchestrator).configureRoutes(this)
                ChatController(orchestrator, lifecycleManager).configureRoutes(this)
                LayerController(orchestrator, lifecycleManager).configureRoutes(this)
            }
        }
    }
}
