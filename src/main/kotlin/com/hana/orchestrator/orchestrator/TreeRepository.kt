package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.presentation.model.execution.ExecutionTreeResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedTree(
    val name: String,
    val query: String,
    val savedAt: Long,
    val tree: ExecutionTreeResponse
)

/**
 * 실행 트리를 .hana/trees/{name}.json 으로 저장·로드
 * FileBackedContextStore 와 동일한 atomic write 패턴 사용
 */
class TreeRepository(
    private val baseDir: File = File(".hana/trees")
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    init { baseDir.mkdirs() }

    fun save(savedTree: SavedTree) {
        val tmp = File(baseDir, "${savedTree.name}.json.tmp")
        val target = File(baseDir, "${savedTree.name}.json")
        tmp.writeText(json.encodeToString(SavedTree.serializer(), savedTree))
        tmp.renameTo(target)
    }

    fun list(): List<SavedTree> =
        baseDir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { json.decodeFromString(SavedTree.serializer(), it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.savedAt }
            ?: emptyList()

    fun load(name: String): SavedTree? {
        val file = File(baseDir, "$name.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(SavedTree.serializer(), file.readText()) }.getOrNull()
    }

    fun delete(name: String): Boolean {
        val file = File(baseDir, "$name.json")
        return file.exists() && file.delete()
    }
}
