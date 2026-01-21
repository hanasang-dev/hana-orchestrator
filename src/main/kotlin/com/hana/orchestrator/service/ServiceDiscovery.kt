package com.hana.orchestrator.service

import io.ktor.client.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * OS 독립적 서비스 발견 및 관리
 * HTTP 기반으로 실행 중인 서비스를 탐지하고 정리
 */
object ServiceDiscovery {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        

        
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 5000
            connectTimeoutMillis = 3000
        }
    }
    
    /**
     * 지정된 포트 범위에서 Hana Orchestrator 서비스 탐지
     */
    suspend fun findHanaServices(
        startPort: Int = 8080, 
        maxRange: Int = 100
    ): List<RunningService> {
        return try {
            (startPort until startPort + maxRange).mapNotNull { port ->
                checkServiceOnPort(port)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 특정 포트에서 서비스 실행 여부 확인
     */
    private suspend fun checkServiceOnPort(port: Int): RunningService? {
        return try {
            val response = httpClient.get("http://localhost:$port/service-info").bodyAsText()
            
            val serviceInfo = json.decodeFromString<ServiceInfo>(response)
            RunningService(port, serviceInfo)
            
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 기존 실행 중인 모든 Hana 서비스 정리 (그레이스풀 셧다운)
     */
    suspend fun stopExistingServices(): List<ShutdownResult> {
        val services = findHanaServices()
        val results = mutableListOf<ShutdownResult>()
        
        println("🔍 Found ${services.size} existing Hana services")
        
        services.forEach { service ->
            val result = gracefulShutdownService(service)
            results.add(result)
        }
        
        return results
    }
    
    /**
     * 단일 서비스 그레이스풀 셧다운
     */
    suspend fun gracefulShutdownService(service: RunningService): ShutdownResult {
        println("  🛑 [ServiceDiscovery] 서비스 종료 시도: ${service.serviceInfo.name} (포트: ${service.port}, ID: ${service.serviceInfo.id})")
        return try {
            println("  📡 [ServiceDiscovery] /shutdown API 호출 중...")
            val response = httpClient.post("http://localhost:${service.port}/shutdown") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("reason" to "New service starting"))
            }.bodyAsText()
            println("  ✅ [ServiceDiscovery] /shutdown API 응답: $response")
            
            // 셧다운 확인 (최대 3초 대기)
            var attempts = 0
            while (attempts < 6) {
                kotlinx.coroutines.delay(500)
                val stillRunning = isServiceRunning(service.port)
                println("  🔍 [ServiceDiscovery] 종료 확인 시도 ${attempts + 1}/6: 서비스 실행 중=${stillRunning}")
                if (!stillRunning) {
                    println("  ✅ [ServiceDiscovery] 서비스 종료 확인됨: 포트 ${service.port}")
                    return ShutdownResult(
                        port = service.port,
                        serviceId = service.serviceInfo.id,
                        success = true,
                        message = "Gracefully shutdown",
                        reason = response
                    )
                }
                attempts++
            }
            
            // 그레이스풀 셧다운 실패 - 레지스트리에서만 정리
            println("  ⚠️ [ServiceDiscovery] 서비스 종료 타임아웃: 포트 ${service.port} (레지스트리에서만 정리)")
            ServiceRegistry.unregisterService(service.serviceInfo.id)
            
            ShutdownResult(
                port = service.port,
                serviceId = service.serviceInfo.id,
                success = false,
                message = "Graceful shutdown timeout, cleaned from registry",
                reason = response
            )
            
        } catch (e: Exception) {
            // HTTP 셧다운 실패 - 레지스트리에서만 정리
            println("  ❌ [ServiceDiscovery] 서비스 종료 실패: 포트 ${service.port} - ${e.message}")
            ServiceRegistry.unregisterService(service.serviceInfo.id)
            
            ShutdownResult(
                port = service.port,
                serviceId = service.serviceInfo.id,
                success = false,
                message = "HTTP shutdown failed, cleaned from registry",
                reason = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * 서비스 실행 상태 확인
     * /status 엔드포인트에서 실제 상태를 확인
     */
    suspend fun isServiceRunning(port: Int): Boolean {
        return try {
            val response = httpClient.get("http://localhost:$port/status").bodyAsText()
            val statusInfo = json.decodeFromString<Map<String, Any>>(response)
            val status = statusInfo["status"] as? String
            // "running" 상태일 때만 true, "shutting_down"이면 false
            status == "running"
        } catch (e: Exception) {
            // 예외 발생 시 서비스가 실행 중이지 않은 것으로 간주
            false
        }
    }
    
    /**
     * 서비스 상태 확인
     */
    suspend fun checkServiceHealth(port: Int): ServiceHealth {
        return try {
            val healthResponse = httpClient.get("http://localhost:$port/health")
            val infoResponse = httpClient.get("http://localhost:$port/service-info").bodyAsText()
            
            val serviceInfo = json.decodeFromString<ServiceInfo>(infoResponse)
            
            ServiceHealth(
                port = port,
                isHealthy = true,
                serviceInfo = serviceInfo,
                message = "Service is running and healthy"
            )
            
        } catch (e: Exception) {
            ServiceHealth(
                port = port,
                isHealthy = false,
                serviceInfo = null,
                message = "Service check failed: ${e.message}"
            )
        }
    }
    
    /**
     * suspend 함수 버전의 close (일반적인 경우 사용)
     * 이 함수를 우선적으로 사용하세요.
     */
    suspend fun closeAsync() {
        httpClient.close()
    }
    
    /**
     * 일반 함수 버전 (shutdown hook에서만 사용)
     * shutdown hook에서는 suspend 함수를 직접 호출할 수 없으므로
     * 최소한의 runBlocking만 사용 (별도 스레드에서)
     */
    fun close() {
        // shutdown hook에서는 suspend 함수를 호출할 수 없으므로
        // 최소한의 runBlocking만 사용 (별도 스레드에서)
        val thread = Thread {
            kotlinx.coroutines.runBlocking {
                httpClient.close()
            }
        }
        thread.start()
        thread.join(5000) // 최대 5초 대기
    }
}

/**
 * 실행 중인 서비스 정보
 */
data class RunningService(
    val port: Int,
    val serviceInfo: ServiceInfo
)

/**
 * 셧다운 결과
 */
data class ShutdownResult(
    val port: Int,
    val serviceId: String,
    val success: Boolean,
    val message: String,
    val reason: String
)

/**
 * 서비스 헬스 상태
 */
data class ServiceHealth(
    val port: Int,
    val isHealthy: Boolean,
    val serviceInfo: ServiceInfo?,
    val message: String
)