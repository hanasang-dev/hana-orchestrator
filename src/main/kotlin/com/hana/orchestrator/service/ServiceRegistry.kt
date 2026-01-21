package com.hana.orchestrator.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*

/**
 * OS 독립적 서비스 등록 관리자
 * 파일 기반으로 서비스 정보를 저장하고 관리
 */
object ServiceRegistry {
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    private val serviceDir = File(System.getProperty("user.home"))
        .resolve(".hana")
        .resolve("services")
    
    init {
        serviceDir.mkdirs()
    }
    
    /**
     * 새 서비스 등록
     */
    fun registerService(port: Int, name: String = "hana-orchestrator"): ServiceInfo {
        cleanupStaleServices()
        
        val serviceInfo = ServiceInfo(
            id = UUID.randomUUID().toString(),
            name = name,
            pid = ProcessHandle.current().pid(),
            port = port,
            startTime = System.currentTimeMillis(),
            lastHeartbeat = System.currentTimeMillis(),
            version = "1.0.0"
        )
        
        val serviceFile = serviceDir.resolve("${serviceInfo.id}.json")
        serviceFile.writeText(json.encodeToString(serviceInfo))
        
        return serviceInfo
    }
    
    /**
     * 서비스 정보 갱신 (하트비트)
     */
    fun updateHeartbeat(serviceId: String): Boolean {
        return try {
            val serviceFile = serviceDir.resolve("$serviceId.json")
            if (serviceFile.exists()) {
                val serviceInfo = json.decodeFromString<ServiceInfo>(serviceFile.readText())
                val updatedInfo = serviceInfo.copy(lastHeartbeat = System.currentTimeMillis())
                serviceFile.writeText(json.encodeToString(updatedInfo))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 서비스 정보 조회
     */
    fun getService(serviceId: String): ServiceInfo? {
        return try {
            val serviceFile = serviceDir.resolve("$serviceId.json")
            if (serviceFile.exists()) {
                json.decodeFromString<ServiceInfo>(serviceFile.readText())
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 모든 활성 서비스 목록 조회
     */
    fun getAllServices(): List<ServiceInfo> {
        cleanupStaleServices()
        
        return serviceDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<ServiceInfo>(file.readText())
                } catch (e: Exception) {
                    file.delete() // 깨진 파일 정리
                    null
                }
            }
            ?.sortedBy { it.startTime }
            ?: emptyList()
    }
    
    /**
     * 서비스 등록 해제
     */
    fun unregisterService(serviceId: String) {
        try {
            val serviceFile = serviceDir.resolve("$serviceId.json")
            serviceFile.delete()
        } catch (e: Exception) {
            // 무시 (정리 중)
        }
    }
    
    /**
     * 오래된 서비스 정리 (5분 이상 하트비트 없음)
     */
    private fun cleanupStaleServices() {
        val now = System.currentTimeMillis()
        val staleThreshold = 5 * 60 * 1000L // 5분
        
        serviceDir.listFiles()?.forEach { file ->
            if (file.extension == "json") {
                try {
                    val info = json.decodeFromString<ServiceInfo>(file.readText())
                    if (now - info.lastHeartbeat > staleThreshold) {
                        file.delete()
                        println("🧹 Cleaned up stale service: ${info.name} (${info.id})")
                    }
                } catch (e: Exception) {
                    file.delete() // 깨진 파일 정리
                }
            }
        }
    }
    
    /**
     * 모든 서비스 정보 정리 (개발 환경용)
     */
    fun clearAllServices() {
        serviceDir.listFiles()?.forEach { file ->
            try {
                file.delete()
            } catch (e: Exception) {
                // 무시
            }
        }
    }
}

@Serializable
data class ServiceInfo(
    val id: String,                    // 고유 식별자
    val name: String,                  // 서비스 이름
    val pid: Long,                     // 프로세스 ID
    val port: Int,                     // 실행 포트
    val startTime: Long,                // 시작 시간 (timestamp)
    val lastHeartbeat: Long,           // 마지막 하트비트 (timestamp)
    val version: String                // 버전 정보
)