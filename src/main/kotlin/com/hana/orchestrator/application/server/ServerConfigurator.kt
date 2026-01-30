package com.hana.orchestrator.application.server

import com.hana.orchestrator.application.lifecycle.ApplicationLifecycleManager
import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.presentation.controller.ChatController
import com.hana.orchestrator.presentation.controller.HealthController
import com.hana.orchestrator.presentation.controller.LayerController
import com.hana.orchestrator.presentation.controller.ServiceController
import com.hana.orchestrator.presentation.controller.ExecutionWebSocketController
import com.hana.orchestrator.service.ServiceInfo
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.pipeline.*
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
    private val applicationScope: CoroutineScope
) {
    
    fun createServer(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        return embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = false
                    prettyPrint = false
                    // 한글 등 비ASCII 문자를 그대로 출력하도록 설정
                })
            }
            
            install(WebSockets) {
                pingPeriod = kotlin.time.Duration.parse("15s")
                timeout = kotlin.time.Duration.parse("15s")
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            
            routing {
                // 정적 파일 서빙 (UI)
                staticResources("/", "static", "index.html")
                
                // 컨트롤러들 설정
                HealthController(lifecycleManager).configureRoutes(this)
                ServiceController(serviceInfo, lifecycleManager, orchestrator.config).configureRoutes(this)
                ChatController(orchestrator, lifecycleManager).configureRoutes(this)
                LayerController(orchestrator, lifecycleManager).configureRoutes(this)
                ExecutionWebSocketController(orchestrator).configureRoutes(this)
            }
        }
    }
}
