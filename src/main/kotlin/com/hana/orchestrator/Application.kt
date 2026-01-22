package com.hana.orchestrator

import com.hana.orchestrator.layer.LayerFactory
import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.service.ServiceRegistry
import com.hana.orchestrator.service.ServiceDiscovery
import com.hana.orchestrator.service.PortAllocator
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.hana.orchestrator.presentation.model.chat.ChatRequest
import com.hana.orchestrator.presentation.model.chat.ChatResponse
import com.hana.orchestrator.presentation.model.service.ServiceStatusResponse
import com.hana.orchestrator.presentation.mapper.ChatRequestMapper
import com.hana.orchestrator.presentation.mapper.ExecutionResultMapper
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

var shutdownRequested = false
lateinit var currentServiceInfo: com.hana.orchestrator.service.ServiceInfo
lateinit var heartbeatJob: Job

/**
 * suspend main 사용 - runBlocking 없이!
 * Kotlin 1.3+부터 지원하며, 컴파일러가 내부적으로 처리합니다.
 */
suspend fun main(args: Array<String>) {
    println("🚀 Starting Hana Orchestrator...")
    
    // 명령줄 인자 파싱
    val cliPort = parsePort(args)
    val skipCleanup = args.contains("--skip-cleanup")

    if (!skipCleanup) {
        println("🧹 Checking for existing Hana services...")
        val cleanupResult = PortAllocator.cleanupHanaPorts()
        println("✅ Cleanup completed: ${cleanupResult.successfulShutdowns}/${cleanupResult.foundServices} services stopped")
    }
    
    // 나머지 초기화도 suspend 함수로
    startApplication(cliPort)
}

/**
 * 실제 애플리케이션 시작 로직 (suspend 함수)
 */
private suspend fun startApplication(cliPort: Int?) {
    
    // 포트 할당
    val portResult = cliPort?.let { specifiedPort ->
        // 지정된 포트가 사용 가능해질 때까지 대기
        waitForPortAvailable(specifiedPort, maxWaitMs = 10000)
        com.hana.orchestrator.service.PortAllocationResult(specifiedPort, true, 0, "Using specified port $specifiedPort")
    } ?: run {
        // 포트를 지정하지 않았으면 사용 가능한 포트 찾기 (자동으로 재시도)
        findAvailablePortWithRetry(startPort = 8080, maxAttempts = 100, maxWaitMs = 10000)
    }
    
    if (!portResult.success) {
        println("❌ Failed to allocate port: ${portResult.message}")
        return
    }
    
    val port = portResult.port
    println("📍 Port allocated: $port (attempted ${portResult.attempts} time(s))")
    
    // 서비스 등록
    currentServiceInfo = ServiceRegistry.registerService(port)
    println("📝 Service registered: ${currentServiceInfo.id}")
    
    val orchestrator = Orchestrator()
    
    // EchoLayer 등록 (Orchestrator는 자기 자신을 등록하지 않음)
    orchestrator.registerLayer(com.hana.orchestrator.layer.EchoLayer())
    
    // Application scope 생성 (메모리 누수 방지)
    val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    heartbeatJob = startHeartbeat(applicationScope, currentServiceInfo.id)
    
    // startApplication이 suspend 함수이므로 여기서도 suspend 함수 호출 가능

    // 7. 서버 시작
    lateinit var server: EmbeddedServer<*, *>
    val finalOrchestrator = orchestrator // 클로저를 위한 변수
    server = embeddedServer(Netty, port = port) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        
        routing {
            get("/health") {
                if (shutdownRequested) {
                    call.respond(mapOf("status" to "shutting_down"))
                } else {
                    call.respondText("OK")
                }
            }
            
            // 서비스 정보 엔드포인트
            get("/service-info") {
                call.respond(currentServiceInfo)
            }
            
            // 상태 엔드포인트
            get("/status") {
                val uptime = System.currentTimeMillis() - currentServiceInfo.startTime
                val status = ServiceStatusResponse(
                    id = currentServiceInfo.id,
                    name = currentServiceInfo.name,
                    port = currentServiceInfo.port,
                    uptime = uptime,
                    status = if (shutdownRequested) "shutting_down" else "running"
                )
                call.respond(status)
            }
            
            // 그레이스풀 셧다운 엔드포인트
            post("/shutdown") {
                try {
                    val request = call.receive<Map<String, String>>()
                    val reason = request["reason"] ?: "API request"
                    
                    println("🛑 Shutdown requested via API: $reason")
                    shutdownRequested = true
                    
                    applicationScope.launch {
                        delay(1000)
                        gracefulShutdownAsync(server, currentServiceInfo.id, heartbeatJob, applicationScope, finalOrchestrator)
                    }
                    
                    call.respond(mapOf(
                        "message" to "Shutdown initiated",
                        "reason" to reason,
                        "serviceId" to currentServiceInfo.id
                    ))
                } catch (e: Exception) {
                    call.respond(mapOf("error" to e.message))
                }
            }
            
            post("/chat") {
                try {
                    if (shutdownRequested) {
                        call.respond(mapOf("error" to "Service is shutting down"))
                        return@post
                    }
                    
                    val request = call.receive<ChatRequest>()
                    // Presentation → Domain 변환
                    val chatDto = ChatRequestMapper.toDto(request)
                    
                    // Orchestrator 실행 (도메인 모델 반환)
                    val executionResult = orchestrator.executeOrchestration(chatDto.message)
                    
                    // Domain → Presentation 변환
                    val response = ExecutionResultMapper.toChatResponse(executionResult)
                    call.respond(response)
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
                    if (shutdownRequested) {
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
        }
    }
    
    // 그레이스풀 셧다운 훅 설정
    setupShutdownHooks(server, currentServiceInfo.id, heartbeatJob, applicationScope, finalOrchestrator)
    
    // 시작 정보 출력
    val startTime = System.currentTimeMillis()
    println("\n" + "=".repeat(60))
    println("🌟 Hana Orchestrator Started Successfully!")
    println("=".repeat(60))
    println("📍 Service ID: ${currentServiceInfo.id}")
    println("🌐 Server URL: http://localhost:$port")
    println("💬 Chat API: http://localhost:$port/chat")
    println("❤️  Health Check: http://localhost:$port/health")
    println("📊 Service Status: http://localhost:$port/status")
    println("🔧 Service Info: http://localhost:$port/service-info")
    println("⏱️  Startup Time: ${System.currentTimeMillis() - startTime}ms")
    println("=".repeat(60))
    println("Press Ctrl+C to gracefully shutdown\n")
    
    try {
        // wait = false로 시작하고, shutdownRequested가 true가 될 때까지 대기
        server.start(wait = false)
        
        // 서버가 종료될 때까지 대기 (shutdownRequested가 true가 되면 종료)
        // shutdownRequested는 /shutdown 엔드포인트에서 설정됨
        while (!shutdownRequested) {
            kotlinx.coroutines.delay(1000)
        }
        
        // shutdownRequested가 true가 되면 graceful shutdown 실행
        gracefulShutdownAsync(server, currentServiceInfo.id, heartbeatJob, applicationScope, finalOrchestrator)
    } catch (e: Exception) {
        println("❌ Server error: ${e.message}")
        // suspend 함수이므로 직접 호출 가능 (runBlocking 불필요!)
        gracefulShutdownAsync(server, currentServiceInfo.id, heartbeatJob, applicationScope, finalOrchestrator)
    }
}

private fun parsePort(args: Array<String>): Int? {
    val argsList = args.toList()
    val portIndex = argsList.indexOfFirst { it == "--port" || it == "-p" }
    return if (portIndex >= 0 && portIndex < argsList.size - 1) {
        argsList[portIndex + 1].toIntOrNull()?.takeIf { it in 1..65535 }
    } else {
        null
    }
}

/**
 * 포트가 사용 가능해질 때까지 대기
 */
private suspend fun waitForPortAvailable(port: Int, maxWaitMs: Int = 10000, checkIntervalMs: Int = 200) {
    val startTime = System.currentTimeMillis()
    var attempts = 0
    
    while (System.currentTimeMillis() - startTime < maxWaitMs) {
        if (com.hana.orchestrator.service.PortAllocator.isPortAvailable(port)) {
            if (attempts > 0) {
                println("⏳ Port $port is now available (waited ${attempts * checkIntervalMs}ms)")
            }
            return
        }
        attempts++
        kotlinx.coroutines.delay(checkIntervalMs.toLong())
    }
    
    // 타임아웃이 발생해도 계속 진행 (포트 할당 로직에서 다시 확인)
    println("⚠️ Port $port still in use after ${maxWaitMs}ms, continuing anyway...")
}

/**
 * 포트를 찾되, 사용 불가능하면 해제될 때까지 재시도
 */
private suspend fun findAvailablePortWithRetry(
    startPort: Int,
    maxAttempts: Int,
    maxWaitMs: Int = 10000,
    checkIntervalMs: Int = 200
): com.hana.orchestrator.service.PortAllocationResult {
    var attempts = 0
    
    while (attempts < maxAttempts) {
        val port = startPort + attempts
        if (com.hana.orchestrator.service.PortAllocator.isPortAvailable(port)) {
            return com.hana.orchestrator.service.PortAllocationResult(
                port = port,
                success = true,
                attempts = attempts + 1,
                message = "Port $port is available"
            )
        }
        
        // 포트가 사용 중이면 해제될 때까지 대기
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (com.hana.orchestrator.service.PortAllocator.isPortAvailable(port)) {
                return com.hana.orchestrator.service.PortAllocationResult(
                    port = port,
                    success = true,
                    attempts = attempts + 1,
                    message = "Port $port became available after waiting"
                )
            }
            kotlinx.coroutines.delay(checkIntervalMs.toLong())
        }
        
        attempts++
    }
    
    return com.hana.orchestrator.service.PortAllocationResult(
        port = -1,
        success = false,
        attempts = maxAttempts,
        message = "No available port found in range $startPort-${startPort + maxAttempts - 1}"
    )
}

private fun startHeartbeat(scope: CoroutineScope, serviceId: String): Job {
    return scope.launch {
        while (!shutdownRequested && isActive) {
            try {
                ServiceRegistry.updateHeartbeat(serviceId)
                delay(30_000)
            } catch (e: Exception) {
                println("⚠️  Heartbeat failed: ${e.message}")
            }
        }
    }
}

private fun setupShutdownHooks(
    server: EmbeddedServer<*, *>,
    serviceId: String,
    heartbeatJob: Job,
    applicationScope: CoroutineScope,
    orchestrator: Orchestrator
) {
    Runtime.getRuntime().addShutdownHook(Thread {
        gracefulShutdown(server, serviceId, heartbeatJob, applicationScope, orchestrator)
    })
}

/**
 * suspend 함수 버전의 graceful shutdown
 */
private suspend fun gracefulShutdownAsync(
    server: EmbeddedServer<*, *>,
    serviceId: String,
    heartbeatJob: Job,
    applicationScope: CoroutineScope,
    orchestrator: Orchestrator
) {
    if (shutdownRequested) return
    
    shutdownRequested = true
    println("\n🛑 Starting graceful shutdown...")
    
    try {
        heartbeatJob.cancel()
        println("✅ Heartbeat stopped")
        
        // Application scope 취소
        applicationScope.cancel()
        println("✅ Application scope cancelled")
        
        // Orchestrator 리소스 정리 (suspend 함수이므로 직접 호출)
        try {
            orchestrator.close()
            println("✅ Orchestrator closed")
        } catch (e: Exception) {
            println("⚠️  Orchestrator close error: ${e.message}")
        }
        
        server.stop(1000, 5000)
        println("✅ Server stopped")
        
        ServiceRegistry.unregisterService(serviceId)
        println("✅ Service unregistered")
        
        ServiceDiscovery.closeAsync()
        println("✅ Service discovery closed")

        println("🎉 Graceful shutdown completed")
        
    } catch (e: Exception) {
        println("⚠️  Shutdown error: ${e.message}")
    }
}

/**
 * 일반 함수 버전 (shutdown hook에서만 사용)
 * shutdown hook에서는 suspend 함수를 직접 호출할 수 없으므로
 * 최소한의 runBlocking만 사용
 */
private fun gracefulShutdown(
    server: EmbeddedServer<*, *>,
    serviceId: String,
    heartbeatJob: Job,
    applicationScope: CoroutineScope,
    orchestrator: Orchestrator
) {
    // shutdown hook에서는 suspend 함수를 호출할 수 없으므로
    // 최소한의 runBlocking만 사용 (별도 스레드에서)
    val shutdownThread = Thread {
        kotlinx.coroutines.runBlocking {
            gracefulShutdownAsync(server, serviceId, heartbeatJob, applicationScope, orchestrator)
        }
    }
    shutdownThread.start()
    shutdownThread.join(10000) // 최대 10초 대기
}