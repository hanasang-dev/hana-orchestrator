package com.hana.orchestrator.service

import java.net.ServerSocket

/**
 * OS 독립적 포트 할당 관리자
 * OS별 포트 관리 명령어 없이 순수 자바로 포트 상태 확인
 */
object PortAllocator {
    
    /**
     * 사용 가능한 포트 찾기 (지정된 범위 내)
     */
    fun findAvailablePort(
        startPort: Int = 8080, 
        maxAttempts: Int = 100
    ): PortAllocationResult {
        repeat(maxAttempts) { attempt ->
            val port = startPort + attempt
            if (isPortAvailable(port)) {
                return PortAllocationResult(
                    port = port,
                    success = true,
                    attempts = attempt + 1,
                    message = "Port $port is available"
                )
            }
        }
        
        return PortAllocationResult(
            port = -1,
            success = false,
            attempts = maxAttempts,
            message = "No available port found in range $startPort-${startPort + maxAttempts - 1}"
        )
    }
    
    /**
     * 특정 포트 사용 가능 여부 확인
     */
    fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { socket ->
                // 소켓이 성공적으로 생성되면 포트 사용 가능
                socket.localPort == port
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 여러 포트 동시에 확인
     */
    fun findMultipleAvailablePorts(
        count: Int,
        startPort: Int = 8080,
        maxRange: Int = 200
    ): PortAllocationResult {
        val availablePorts = mutableListOf<Int>()
        var attempts = 0
        
        for (port in startPort until startPort + maxRange) {
            attempts++
            if (isPortAvailable(port)) {
                availablePorts.add(port)
                if (availablePorts.size == count) {
                    break
                }
            }
        }
        
        return if (availablePorts.size >= count) {
            PortAllocationResult(
                port = availablePorts.first(),
                success = true,
                attempts = attempts,
                message = "Found ${availablePorts.size} available ports: ${availablePorts.joinToString(", ")}"
            )
        } else {
            PortAllocationResult(
                port = -1,
                success = false,
                attempts = attempts,
                message = "Only found ${availablePorts.size} available ports, needed $count"
            )
        }
    }
    
    /**
     * 포트 사용 상태 상세 정보
     */
    fun getPortStatus(port: Int): PortStatus {
        return when {
            !isValidPort(port) -> PortStatus.INVALID
            isPortAvailable(port) -> PortStatus.AVAILABLE
            else -> PortStatus.IN_USE
        }
    }
    
    /**
     * 유효한 포트 번호인지 확인
     */
    private fun isValidPort(port: Int): Boolean {
        return port in 1..65535
    }
    
    /**
     * 개발 환경용 포트 정리 (Hana 서비스가 사용 중인 포트만)
     * 포트 범위를 스캔해서 실제로 실행 중인 모든 Hana 서비스를 찾아서 정리
     * ServiceRegistry에 등록되지 않았어도 실행 중이면 정리
     */
    suspend fun cleanupHanaPorts(): PortCleanupResult {
        println("🔍 Scanning for running Hana services...")
        
        // 포트 범위를 스캔해서 실제로 실행 중인 모든 Hana 서비스 찾기
        // ServiceRegistry에 의존하지 않고 실제 HTTP 응답으로 확인
        val runningServices = ServiceDiscovery.findHanaServices(startPort = 8080, maxRange = 100)
        
        println("🔍 Found ${runningServices.size} running Hana services")
        runningServices.forEach { service ->
            println("  📋 Running: ${service.serviceInfo.name} (포트: ${service.port}, ID: ${service.serviceInfo.id})")
        }
        
        if (runningServices.isEmpty()) {
            println("  ✅ No running services to shutdown")
            return PortCleanupResult(
                foundServices = 0,
                successfulShutdowns = 0,
                failedShutdowns = 0,
                results = emptyList()
            )
        }
        
        println("  🛑 Shutting down ${runningServices.size} running services...")
        
        // 실행 중인 서비스 모두 종료
        val shutdownResults = runningServices.map { service ->
            ServiceDiscovery.gracefulShutdownService(service)
        }
        
        val successfulShutdowns = shutdownResults.count { it.success }
        val failedShutdowns = shutdownResults.count { !it.success }
        
        println("  ✅ Shutdown complete: $successfulShutdowns succeeded, $failedShutdowns failed")
        
        return PortCleanupResult(
            foundServices = runningServices.size,
            successfulShutdowns = successfulShutdowns,
            failedShutdowns = failedShutdowns,
            results = shutdownResults
        )
    }
    
    /**
     * 특정 포트 범위에서 Hana 서비스 찾기
     */
    suspend fun findHanaPortsInUse(
        startPort: Int = 8080,
        endPort: Int = 8180
    ): List<Int> {
        val hanaServices = ServiceDiscovery.findHanaServices(startPort, endPort - startPort + 1)
        return hanaServices.map { it.port }
    }
}

/**
 * 포트 할당 결과
 */
data class PortAllocationResult(
    val port: Int,
    val success: Boolean,
    val attempts: Int,
    val message: String
)

/**
 * 포트 상태 열거형
 */
enum class PortStatus {
    AVAILABLE,   // 사용 가능
    IN_USE,      // 사용 중
    INVALID      // 유효하지 않은 포트
}

/**
 * 포트 정리 결과
 */
data class PortCleanupResult(
    val foundServices: Int,
    val successfulShutdowns: Int,
    val failedShutdowns: Int,
    val results: List<com.hana.orchestrator.service.ShutdownResult>
)