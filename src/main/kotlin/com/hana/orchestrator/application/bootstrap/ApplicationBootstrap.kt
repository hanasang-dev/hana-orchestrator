package com.hana.orchestrator.application.bootstrap

import com.hana.orchestrator.application.lifecycle.ApplicationLifecycleManager
import com.hana.orchestrator.application.port.PortManager
import com.hana.orchestrator.application.server.ServerConfigurator
import com.hana.orchestrator.context.ContextScope
import com.hana.orchestrator.context.DefaultAppContextService
import com.hana.orchestrator.context.FileBackedContextStore
import com.hana.orchestrator.context.InMemoryContextStore
import com.hana.orchestrator.context.PersistenceKind
import com.hana.orchestrator.layer.EchoLayer
import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import com.hana.orchestrator.llm.config.LLMConfig
import com.hana.orchestrator.service.PortAllocator
import com.hana.orchestrator.service.ServiceInfo
import com.hana.orchestrator.service.ServiceRegistry
import com.hana.orchestrator.service.OllamaHealthChecker
import com.hana.orchestrator.llm.LLMProvider
import io.ktor.server.config.*
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.*
import java.io.File

/**
 * 애플리케이션 초기화 및 시작
 * SRP: 애플리케이션 부트스트랩만 담당
 */
class ApplicationBootstrap {
    
    private val logger = createOrchestratorLogger(ApplicationBootstrap::class.java, null)
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
            logger.info("✅ application.conf 로드 성공")
            val loadedConfig = LLMConfig.fromApplicationConfig(config)
            logger.info("📋 LLM 설정: simple=${loadedConfig.simpleModelId}, medium=${loadedConfig.mediumModelId}, complex=${loadedConfig.complexModelId}")
            loadedConfig
        } catch (e: Exception) {
            // application.conf 로드 실패 시 환경변수 사용
            logger.warn("⚠️ application.conf 로드 실패, 환경변수 사용: ${e.message}")
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
        logger.info("🚀 Starting Hana Orchestrator...")
        
        // 명령줄 인자 파싱
        val cliPort = portManager.parsePort(args)
        val skipCleanup = args.contains("--skip-cleanup")
        
        // 로컬 Ollama 인스턴스 확인 및 준비
        // 확장성: OLLAMA provider만 확인, 향후 클라우드 API는 확인 불필요
        val ollamaUrls = mutableListOf<String>()
        if (llmConfig.simpleProvider == LLMProvider.OLLAMA) {
            ollamaUrls.add(llmConfig.simpleModelBaseUrl)
        }
        if (llmConfig.mediumProvider == LLMProvider.OLLAMA) {
            ollamaUrls.add(llmConfig.mediumModelBaseUrl)
        }
        if (llmConfig.complexProvider == LLMProvider.OLLAMA) {
            ollamaUrls.add(llmConfig.complexModelBaseUrl)
        }
        
        // 중복 제거 (단일 Ollama 인스턴스 사용 시)
        val uniqueOllamaUrls = ollamaUrls.distinct()
        
        if (uniqueOllamaUrls.isNotEmpty()) {
            logger.info("🔍 로컬 Ollama 인스턴스 확인 중...")
            val allReady = OllamaHealthChecker.waitForInstances(uniqueOllamaUrls, maxWaitSeconds = 30)
            if (!allReady) {
                logger.warn("⚠️ 일부 Ollama 인스턴스가 준비되지 않았지만 계속 진행합니다.")
                logger.info("💡 해결 방법:")
                logger.info("   1. Ollama를 설치하고 실행하세요: brew install ollama && ollama serve")
                logger.info("   2. 또는 필요한 모델을 미리 다운로드하세요: ollama pull gemma2:2b llama3.1:8b")
            } else {
                logger.info("✅ 모든 Ollama 인스턴스 준비 완료")
            }
        } else {
            logger.info("ℹ️ Ollama provider를 사용하지 않습니다 (클라우드 API 사용 중)")
        }
        
        // 기존 서비스 정리
        if (!skipCleanup) {
            logger.info("🧹 Checking for existing Hana services...")
            val cleanupResult = PortAllocator.cleanupHanaPorts()
            logger.info("✅ Cleanup completed: ${cleanupResult.successfulShutdowns}/${cleanupResult.foundServices} services stopped")
        }
        
        // 포트 할당
        val portResult = portManager.allocatePort(cliPort)
        if (!portResult.success) {
            logger.error("❌ Failed to allocate port: ${portResult.message}")
            return
        }
        
        val port = portResult.port
        logger.info("📍 Port allocated: $port (attempted ${portResult.attempts} time(s))")
        
        // 서비스 등록
        val serviceInfo = ServiceRegistry.registerService(port)
        logger.info("📝 Service registered: ${serviceInfo.id}")
        
        // 앱 컨텍스트: 영구는 JSON 파일, 휘발은 메모리. 기동 시 휘발성에 workingDirectory 설정
        val contextDir = File(System.getProperty("user.dir") ?: ".", ".hana/context")
        val persistentFile = File(contextDir, "persistent-context.json")
        val persistentStore = FileBackedContextStore(ContextScope.App, PersistenceKind.Persistent, persistentFile)
        val volatileStore = InMemoryContextStore(ContextScope.App, PersistenceKind.Volatile)
        val appContextService = DefaultAppContextService(persistentStore, volatileStore)

        // Orchestrator 초기화 (LLM 설정 + 앱 컨텍스트)
        val orchestrator = Orchestrator(llmConfig, appContextService)
        
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
        logger.info("\n" + "=".repeat(60))
        logger.info("🌟 Hana Orchestrator Started Successfully!")
        logger.info("=".repeat(60))
        logger.info("📍 Service ID: ${serviceInfo.id}")
        logger.info("🌐 Server URL: http://localhost:$port")
        logger.info("💬 Chat API: http://localhost:$port/chat")
        logger.info("❤️  Health Check: http://localhost:$port/health")
        logger.info("📊 Service Status: http://localhost:$port/status")
        logger.info("🔧 Service Info: http://localhost:$port/service-info")
        logger.info("⏱️  Startup Time: ${System.currentTimeMillis() - startTime}ms")
        logger.info("=".repeat(60))
        logger.info("Press Ctrl+C to gracefully shutdown\n")
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
            logger.error("❌ Server error: ${e.message}", e)
            lifecycleManager.gracefulShutdownAsync(server, serviceId, heartbeatJob, applicationScope, orchestrator)
        }
    }
    
}
