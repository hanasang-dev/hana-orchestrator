package com.hana.orchestrator.application.lifecycle

import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import com.hana.orchestrator.service.ServiceRegistry
import com.hana.orchestrator.service.ServiceDiscovery
import com.hana.orchestrator.service.OllamaHealthChecker
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.*

/**
 * 애플리케이션 생명주기 관리
 * SRP: 생명주기 관련 로직만 담당 (Heartbeat, Shutdown)
 */
class ApplicationLifecycleManager {
    
    private val logger = createOrchestratorLogger(ApplicationLifecycleManager::class.java, null)
    private var shutdownRequested = false
    private var heartbeatJob: Job? = null
    
    fun isShutdownRequested(): Boolean = shutdownRequested
    
    /**
     * Heartbeat 시작
     */
    fun startHeartbeat(scope: CoroutineScope, serviceId: String): Job {
        val job = scope.launch {
            while (!shutdownRequested && isActive) {
                try {
                    ServiceRegistry.updateHeartbeat(serviceId)
                    delay(30_000)
                } catch (e: Exception) {
                    logger.warn("⚠️ Heartbeat failed: ${e.message}")
                }
            }
        }
        heartbeatJob = job
        return job
    }
    
    /**
     * Shutdown 요청
     */
    fun requestShutdown() {
        shutdownRequested = true
    }
    
    /**
     * Graceful shutdown 실행 (suspend 버전)
     */
    @Volatile
    private var isShuttingDown = false
    
    suspend fun gracefulShutdownAsync(
        server: EmbeddedServer<*, *>,
        serviceId: String,
        heartbeatJob: Job,
        applicationScope: CoroutineScope,
        orchestrator: Orchestrator
    ) {
        // 이미 shutdown이 진행 중이면 중복 실행 방지
        if (isShuttingDown) {
            logger.warn("⚠️ Shutdown already in progress, skipping...")
            return
        }
        
        isShuttingDown = true
        shutdownRequested = true
        logger.info("🛑 Starting graceful shutdown...")
        
        try {
            // 1. Heartbeat 중지
            heartbeatJob.cancel()
            logger.info("✅ Heartbeat stopped")
            
            // 2. 서버 중지 (먼저 실행하여 새로운 요청 차단)
            try {
                server.stop(1000, 5000)
                logger.info("✅ Server stopped")
            } catch (e: Exception) {
                logger.warn("⚠️ Server stop error: ${e.message}")
                // 에러가 발생해도 계속 진행
            }
            
            // 3. Orchestrator 리소스 정리
            try {
                orchestrator.close()
                logger.info("✅ Orchestrator closed")
            } catch (e: Exception) {
                logger.warn("⚠️ Orchestrator close error: ${e.message}")
            }
            
            // 4. Service 등록 해제
            try {
                ServiceRegistry.unregisterService(serviceId)
                logger.info("✅ Service unregistered")
            } catch (e: Exception) {
                logger.warn("⚠️ Service unregister error: ${e.message}")
            }
            
            // 5. Service Discovery 종료
            try {
                ServiceDiscovery.closeAsync()
                logger.info("✅ Service discovery closed")
            } catch (e: Exception) {
                logger.warn("⚠️ Service discovery close error: ${e.message}")
            }
            
            // 6. Ollama Health Checker 리소스 정리
            try {
                OllamaHealthChecker.close()
                logger.info("✅ Ollama health checker closed")
            } catch (e: Exception) {
                logger.warn("⚠️ Ollama health checker close error: ${e.message}")
            }
            
            // 7. Application scope 취소 (마지막에 실행)
            try {
                applicationScope.cancel()
                logger.info("✅ Application scope cancelled")
            } catch (e: Exception) {
                logger.warn("⚠️ Application scope cancel error: ${e.message}")
            }

            logger.info("🎉 Graceful shutdown completed")
            
        } catch (e: Exception) {
            logger.error("⚠️ Shutdown error: ${e.message}", e)
        }
    }
    
    /**
     * Shutdown hook용 (일반 함수 버전)
     */
    fun gracefulShutdown(
        server: EmbeddedServer<*, *>,
        serviceId: String,
        heartbeatJob: Job,
        applicationScope: CoroutineScope,
        orchestrator: Orchestrator
    ) {
        val shutdownThread = Thread {
            runBlocking {
                gracefulShutdownAsync(server, serviceId, heartbeatJob, applicationScope, orchestrator)
            }
        }
        shutdownThread.start()
        shutdownThread.join(10000) // 최대 10초 대기
    }
    
    /**
     * Shutdown hook 설정
     */
    fun setupShutdownHooks(
        server: EmbeddedServer<*, *>,
        serviceId: String,
        heartbeatJob: Job,
        applicationScope: CoroutineScope,
        orchestrator: Orchestrator
    ) {
        Runtime.getRuntime().addShutdownHook(Thread {
            // shutdown hook은 이미 진행 중이면 실행하지 않음
            if (!isShuttingDown) {
                gracefulShutdown(server, serviceId, heartbeatJob, applicationScope, orchestrator)
            }
        })
    }
}
