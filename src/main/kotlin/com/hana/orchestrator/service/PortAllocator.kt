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
     * ServiceRegistry를 통해 등록된 서비스만 정리 (성능 최적화)
     * 하드코딩된 포트 범위 스캔 대신 파일 기반 레지스트리 사용
     */
    suspend fun cleanupHanaPorts(): PortCleanupResult {
        // ServiceRegistry에서 등록된 서비스 목록 가져오기 (파일 기반, 빠름)
        val registeredServices = ServiceRegistry.getAllServices()
        
        println("🔍 Found ${registeredServices.size} registered Hana services")
        registeredServices.forEach { service ->
            println("  📋 Registered: ${service.name} (포트: ${service.port}, ID: ${service.id})")
        }
        
        // 실제로 실행 중인 서비스만 필터링 (HTTP 확인)
        val runningServices = registeredServices.filter { serviceInfo ->
            try {
                val isRunning = ServiceDiscovery.isServiceRunning(serviceInfo.port)
                println("  🔍 Port ${serviceInfo.port}: ${if (isRunning) "running" else "not running"}")
                isRunning
            } catch (e: Exception) {
                println("  ⚠️ Port ${serviceInfo.port}: check failed - ${e.message}")
                false
            }
        }
        
        println("  ✅ Found ${runningServices.size} running services to shutdown")
        
        // 실행 중인 서비스만 종료
        val shutdownResults = runningServices.map { serviceInfo ->
            val runningService = RunningService(
                port = serviceInfo.port,
                serviceInfo = serviceInfo
            )
            ServiceDiscovery.gracefulShutdownService(runningService)
        }
        
        val successfulShutdowns = shutdownResults.count { it.success }
        val failedShutdowns = shutdownResults.count { !it.success }
        
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