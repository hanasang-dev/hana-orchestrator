package com.hana.orchestrator.service

import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ollama 서버 상태 확인 유틸리티
 * DRY: 중복된 Ollama 상태 확인 로직을 한 곳에 집중
 * SRP: Ollama 상태 확인만 담당
 */
object OllamaHealthChecker {
    private val logger = createOrchestratorLogger(OllamaHealthChecker::class.java, null)
    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 2000
        }
    }
    
    /**
     * Ollama 서버가 준비되었는지 확인
     * 
     * @param baseUrl Ollama 서버 URL (예: http://localhost:11434)
     * @return 준비되었으면 true, 아니면 false
     */
    suspend fun isReady(baseUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val response: HttpResponse = httpClient.get("$baseUrl/api/tags")
                response.status.value == 200
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * 여러 Ollama 인스턴스가 준비될 때까지 대기
     * 
     * @param urls Ollama 서버 URL 목록
     * @param maxWaitSeconds 최대 대기 시간 (초)
     * @return 모든 인스턴스가 준비되었으면 true, 아니면 false
     */
    suspend fun waitForInstances(urls: List<String>, maxWaitSeconds: Long): Boolean {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val maxWaitMs = maxWaitSeconds * 1000
            val readyUrls = mutableSetOf<String>()
            
            while (System.currentTimeMillis() - startTime < maxWaitMs && readyUrls.size < urls.size) {
                urls.forEach { url ->
                    if (url !in readyUrls && isReady(url)) {
                        readyUrls.add(url)
                        logger.info("✅ Ollama 준비 완료: $url")
                    }
                }
                
                if (readyUrls.size < urls.size) {
                    kotlinx.coroutines.delay(1000)
                }
            }
            
            readyUrls.size == urls.size
        }
    }
    
    /**
     * 리소스 정리 (애플리케이션 종료 시 호출)
     */
    suspend fun close() {
        httpClient.close()
    }
}
