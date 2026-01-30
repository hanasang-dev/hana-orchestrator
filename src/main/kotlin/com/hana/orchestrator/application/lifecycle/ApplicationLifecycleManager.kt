package com.hana.orchestrator.application.lifecycle

import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.service.ServiceRegistry
import com.hana.orchestrator.service.ServiceDiscovery
import com.hana.orchestrator.service.DockerComposeManager
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.*

/**
 * 애플리케이션 생명주기 관리
 * SRP: 생명주기 관련 로직만 담당 (Heartbeat, Shutdown)
 */
class ApplicationLifecycleManager {
    
    private var shutdownRequested = false
    private var heartbeatJob: Job? = null
    private val dockerComposeManager = DockerComposeManager()
    
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
                    println("⚠️  Heartbeat failed: ${e.message}")
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
            println("⚠️  Shutdown already in progress, skipping...")
            return
        }
        
        isShuttingDown = true
        shutdownRequested = true
        println("\n🛑 Starting graceful shutdown...")
        
        try {
            // 1. Heartbeat 중지
            heartbeatJob.cancel()
            println("✅ Heartbeat stopped")
            
            // 2. 서버 중지 (먼저 실행하여 새로운 요청 차단)
            try {
                server.stop(1000, 5000)
                println("✅ Server stopped")
            } catch (e: Exception) {
                println("⚠️  Server stop error: ${e.message}")
                // 에러가 발생해도 계속 진행
            }
            
            // 3. Orchestrator 리소스 정리
            try {
                orchestrator.close()
                println("✅ Orchestrator closed")
            } catch (e: Exception) {
                println("⚠️  Orchestrator close error: ${e.message}")
            }
            
            // 4. Service 등록 해제
            try {
                ServiceRegistry.unregisterService(serviceId)
                println("✅ Service unregistered")
            } catch (e: Exception) {
                println("⚠️  Service unregister error: ${e.message}")
            }
            
            // 5. Service Discovery 종료
            try {
                ServiceDiscovery.closeAsync()
                println("✅ Service discovery closed")
            } catch (e: Exception) {
                println("⚠️  Service discovery close error: ${e.message}")
            }
            
            // 6. Docker Compose 서비스 종료
            try {
                val ollamaServices = listOf("ollama-simple", "ollama-medium", "ollama-complex")
                dockerComposeManager.stopServices(ollamaServices)
                println("✅ Docker Compose services stopped")
            } catch (e: Exception) {
                println("⚠️  Docker Compose stop error: ${e.message}")
            }
            
            // 7. Application scope 취소 (마지막에 실행)
            try {
                applicationScope.cancel()
                println("✅ Application scope cancelled")
            } catch (e: Exception) {
                println("⚠️  Application scope cancel error: ${e.message}")
            }

            println("🎉 Graceful shutdown completed")
            
        } catch (e: Exception) {
            println("⚠️  Shutdown error: ${e.message}")
            e.printStackTrace()
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
