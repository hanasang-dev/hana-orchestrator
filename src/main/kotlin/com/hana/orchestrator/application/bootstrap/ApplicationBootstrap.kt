package com.hana.orchestrator.application.bootstrap

import com.hana.orchestrator.application.lifecycle.ApplicationLifecycleManager
import com.hana.orchestrator.application.port.PortManager
import com.hana.orchestrator.application.server.ServerConfigurator
import com.hana.orchestrator.layer.EchoLayer
import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.service.PortAllocator
import com.hana.orchestrator.service.ServiceInfo
import com.hana.orchestrator.service.ServiceRegistry
import io.ktor.server.config.*
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
     * application.conf에서 설정 로드, 환경변수로 오버라이드 가능
     */
    suspend fun start(args: Array<String>) {
        // ApplicationConfig 로드 시도
        val llmConfig = try {
            val config = loadApplicationConfig()
            println("✅ application.conf 로드 성공")
            val loadedConfig = LLMConfig.fromApplicationConfig(config)
            println("📋 LLM 설정: simple=${loadedConfig.simpleModelId}, medium=${loadedConfig.mediumModelId}, complex=${loadedConfig.complexModelId}")
            loadedConfig
        } catch (e: Exception) {
            // application.conf 로드 실패 시 환경변수 사용
            println("⚠️ application.conf 로드 실패, 환경변수 사용: ${e.message}")
            LLMConfig.fromEnvironment()
        }
        
        startWithLLMConfig(llmConfig, args)
    }
    
    /**
     * application.conf 파일 로드
     * Ktor의 HOCON 설정 파일을 로드
     */
    private fun loadApplicationConfig(): ApplicationConfig {
        return try {
            // 클래스패스에서 application.conf 로드
            val resource = javaClass.classLoader.getResource("application.conf")
            if (resource != null) {
                // HOCON 설정 파싱 및 환경변수 치환 해결
                val config = com.typesafe.config.ConfigFactory.parseURL(resource)
                    .resolve() // 환경변수 치환(${?PORT} 등) 해결
                HoconApplicationConfig(config)
            } else {
                // 파일이 없으면 기본 설정으로 생성
                throw Exception("application.conf not found in classpath")
            }
        } catch (e: Exception) {
            throw Exception("Failed to load application.conf: ${e.message}", e)
        }
    }
    
    /**
     * LLMConfig를 직접 받는 시작 메서드 (내부 공통 로직)
     */
    private suspend fun startWithLLMConfig(llmConfig: LLMConfig, args: Array<String>) {
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
        
        // Orchestrator 초기화 (LLM 설정 전달)
        val orchestrator = Orchestrator(llmConfig)
        
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
        serviceInfo: ServiceInfo,
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
    
    private fun printStartupInfo(port: Int, serviceInfo: ServiceInfo) {
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
