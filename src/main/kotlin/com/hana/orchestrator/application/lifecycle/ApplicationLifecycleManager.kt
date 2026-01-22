package com.hana.orchestrator.application.lifecycle

import com.hana.orchestrator.orchestrator.Orchestrator
import com.hana.orchestrator.service.ServiceRegistry
import com.hana.orchestrator.service.ServiceDiscovery
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.*

/**
 * 애플리케이션 생명주기 관리
 * SRP: 생명주기 관련 로직만 담당 (Heartbeat, Shutdown)
 */
class ApplicationLifecycleManager {
    
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
    suspend fun gracefulShutdownAsync(
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
            
            // Orchestrator 리소스 정리
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
            gracefulShutdown(server, serviceId, heartbeatJob, applicationScope, orchestrator)
        })
    }
}
