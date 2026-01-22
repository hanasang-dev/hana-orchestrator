package com.hana.orchestrator.application.bootstrap

import com.hana.orchestrator.application.lifecycle.ApplicationLifecycleManager
import com.hana.orchestrator.application.port.PortManager
import com.hana.orchestrator.application.server.ServerConfigurator
import com.hana.orchestrator.layer.EchoLayer
import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.service.PortAllocator
import com.hana.orchestrator.service.ServiceRegistry
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.*

/**
 * 애플리케이션 초기화 및 시작
 * SRP: 애플리케이션 부트스트랩만 담당
 */
class ApplicationBootstrap {
    
    private val portManager = PortManager()
    private val lifecycleManager = ApplicationLifecycleManager()
    
    /**
     * 애플리케이션 시작
     */
    suspend fun start(args: Array<String>) {
        println("🚀 Starting Hana Orchestrator...")
        
        // 명령줄 인자 파싱
        val cliPort = portManager.parsePort(args)
        val skipCleanup = args.contains("--skip-cleanup")
        
        // 기존 서비스 정리
        if (!skipCleanup) {
            println("🧹 Checking for existing Hana services...")
            val cleanupResult = PortAllocator.cleanupHanaPorts()
            println("✅ Cleanup completed: ${cleanupResult.successfulShutdowns}/${cleanupResult.foundServices} services stopped")
        }
        
        // 포트 할당
        val portResult = portManager.allocatePort(cliPort)
        if (!portResult.success) {
            println("❌ Failed to allocate port: ${portResult.message}")
            return
        }
        
        val port = portResult.port
        println("📍 Port allocated: $port (attempted ${portResult.attempts} time(s))")
        
        // 서비스 등록
        val serviceInfo = ServiceRegistry.registerService(port)
        println("📝 Service registered: ${serviceInfo.id}")
        
        // Orchestrator 초기화
        val orchestrator = Orchestrator()
        orchestrator.registerLayer(EchoLayer())
        
        // Application scope 생성
        val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val heartbeatJob = lifecycleManager.startHeartbeat(applicationScope, serviceInfo.id)
        
        // 서버 생성 및 시작
        val server = createAndStartServer(port, orchestrator, serviceInfo, applicationScope)
        
        // Shutdown hook 설정
        lifecycleManager.setupShutdownHooks(server, serviceInfo.id, heartbeatJob, applicationScope, orchestrator)
        
        // 시작 정보 출력
        printStartupInfo(port, serviceInfo)
        
        // 서버 실행 및 대기
        runServer(server, serviceInfo.id, heartbeatJob, applicationScope, orchestrator)
    }
    
    private fun createAndStartServer(
        port: Int,
        orchestrator: Orchestrator,
        serviceInfo: com.hana.orchestrator.service.ServiceInfo,
        applicationScope: CoroutineScope
    ): EmbeddedServer<*, *> {
        val serverConfigurator = ServerConfigurator(
            port = port,
            orchestrator = orchestrator,
            serviceInfo = serviceInfo,
            lifecycleManager = lifecycleManager,
            applicationScope = applicationScope
        )
        
        val server = serverConfigurator.createServer()
        server.start(wait = false)
        return server
    }
    
    private fun printStartupInfo(port: Int, serviceInfo: com.hana.orchestrator.service.ServiceInfo) {
        val startTime = System.currentTimeMillis()
        println("\n" + "=".repeat(60))
        println("🌟 Hana Orchestrator Started Successfully!")
        println("=".repeat(60))
        println("📍 Service ID: ${serviceInfo.id}")
        println("🌐 Server URL: http://localhost:$port")
        println("💬 Chat API: http://localhost:$port/chat")
        println("❤️  Health Check: http://localhost:$port/health")
        println("📊 Service Status: http://localhost:$port/status")
        println("🔧 Service Info: http://localhost:$port/service-info")
        println("⏱️  Startup Time: ${System.currentTimeMillis() - startTime}ms")
        println("=".repeat(60))
        println("Press Ctrl+C to gracefully shutdown\n")
    }
    
    private suspend fun runServer(
        server: EmbeddedServer<*, *>,
        serviceId: String,
        heartbeatJob: Job,
        applicationScope: CoroutineScope,
        orchestrator: Orchestrator
    ) {
        try {
            // 서버가 종료될 때까지 대기
            while (!lifecycleManager.isShutdownRequested()) {
                delay(1000)
            }
            
            // Graceful shutdown 실행
            lifecycleManager.gracefulShutdownAsync(server, serviceId, heartbeatJob, applicationScope, orchestrator)
        } catch (e: Exception) {
            println("❌ Server error: ${e.message}")
            lifecycleManager.gracefulShutdownAsync(server, serviceId, heartbeatJob, applicationScope, orchestrator)
        }
    }
}
