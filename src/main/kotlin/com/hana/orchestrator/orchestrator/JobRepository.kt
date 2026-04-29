package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.domain.entity.ScheduledJob
import kotlinx.serialization.json.Json
import java.io.File

/**
 * ScheduledJob 을 .hana/jobs/{id}.json 으로 저장·로드
 * TreeRepository 와 동일한 atomic write 패턴
 */
class JobRepository(
    private val baseDir: File = File(".hana/jobs")
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    init { baseDir.mkdirs() }

    fun save(job: ScheduledJob) {
        val tmp = File(baseDir, "${job.id}.json.tmp")
        val target = File(baseDir, "${job.id}.json")
        tmp.writeText(json.encodeToString(ScheduledJob.serializer(), job))
        tmp.renameTo(target)
    }

    fun list(): List<ScheduledJob> =
        baseDir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { json.decodeFromString(ScheduledJob.serializer(), it.readText()) }.getOrNull() }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun load(id: String): ScheduledJob? {
        val file = File(baseDir, "$id.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(ScheduledJob.serializer(), file.readText()) }.getOrNull()
    }

    fun delete(id: String): Boolean {
        val file = File(baseDir, "$id.json")
        return file.exists() && file.delete()
    }
}
