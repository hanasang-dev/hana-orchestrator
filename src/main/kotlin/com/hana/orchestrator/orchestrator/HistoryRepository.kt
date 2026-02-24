package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.domain.entity.ExecutionHistory
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 실행 이력 파일 영속화
 * SRP: 이력 파일 저장/로드만 담당
 */
class HistoryRepository(
    private val baseDir: File = File(".hana/history")
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init { baseDir.mkdirs() }

    fun save(history: ExecutionHistory) {
        val tmp = File(baseDir, "${history.id}.json.tmp")
        val target = File(baseDir, "${history.id}.json")
        tmp.writeText(json.encodeToString(ExecutionHistory.serializer(), history))
        tmp.renameTo(target)
    }

    fun loadRecent(limit: Int = 50): List<ExecutionHistory> =
        baseDir.listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.mapNotNull { runCatching { json.decodeFromString(ExecutionHistory.serializer(), it.readText()) }.getOrNull() }
            ?: emptyList()
}
