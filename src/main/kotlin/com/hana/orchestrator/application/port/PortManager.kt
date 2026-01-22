package com.hana.orchestrator.application.port

import com.hana.orchestrator.service.PortAllocator
import com.hana.orchestrator.service.PortAllocationResult
import kotlinx.coroutines.delay

/**
 * 포트 할당 및 관리 책임
 * SRP: 포트 관련 로직만 담당
 */
class PortManager {
    
    /**
     * 명령줄 인자에서 포트 파싱
     */
    fun parsePort(args: Array<String>): Int? {
        val argsList = args.toList()
        val portIndex = argsList.indexOfFirst { it == "--port" || it == "-p" }
        return if (portIndex >= 0 && portIndex < argsList.size - 1) {
            argsList[portIndex + 1].toIntOrNull()?.takeIf { it in 1..65535 }
        } else {
            null
        }
    }
    
    /**
     * 지정된 포트가 사용 가능해질 때까지 대기
     */
    suspend fun waitForPortAvailable(port: Int, maxWaitMs: Int = 10000, checkIntervalMs: Int = 200) {
        val startTime = System.currentTimeMillis()
        var attempts = 0
        
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (PortAllocator.isPortAvailable(port)) {
                if (attempts > 0) {
                    println("⏳ Port $port is now available (waited ${attempts * checkIntervalMs}ms)")
                }
                return
            }
            attempts++
            delay(checkIntervalMs.toLong())
        }
        
        println("⚠️ Port $port still in use after ${maxWaitMs}ms, continuing anyway...")
    }
    
    /**
     * 사용 가능한 포트를 찾되, 사용 불가능하면 해제될 때까지 재시도
     */
    suspend fun findAvailablePortWithRetry(
        startPort: Int,
        maxAttempts: Int,
        maxWaitMs: Int = 10000,
        checkIntervalMs: Int = 200
    ): PortAllocationResult {
        var attempts = 0
        
        while (attempts < maxAttempts) {
            val port = startPort + attempts
            if (PortAllocator.isPortAvailable(port)) {
                return PortAllocationResult(
                    port = port,
                    success = true,
                    attempts = attempts + 1,
                    message = "Port $port is available"
                )
            }
            
            // 포트가 사용 중이면 해제될 때까지 대기
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                if (PortAllocator.isPortAvailable(port)) {
                    return PortAllocationResult(
                        port = port,
                        success = true,
                        attempts = attempts + 1,
                        message = "Port $port became available after waiting"
                    )
                }
                delay(checkIntervalMs.toLong())
            }
            
            attempts++
        }
        
        return PortAllocationResult(
            port = -1,
            success = false,
            attempts = maxAttempts,
            message = "No available port found in range $startPort-${startPort + maxAttempts - 1}"
        )
    }
    
    /**
     * 포트 할당 (지정된 포트 또는 자동 할당)
     */
    suspend fun allocatePort(cliPort: Int?): PortAllocationResult {
        return cliPort?.let { specifiedPort ->
            waitForPortAvailable(specifiedPort, maxWaitMs = 10000)
            PortAllocationResult(specifiedPort, true, 0, "Using specified port $specifiedPort")
        } ?: run {
            findAvailablePortWithRetry(startPort = 8080, maxAttempts = 100, maxWaitMs = 10000)
        }
    }
}
