package com.hana.orchestrator.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 파일에 저장할 때 사용하는 래퍼. JSON 직렬화용. */
@Serializable
private data class PersistentContextFile(val data: Map<String, String> = emptyMap())

/**
 * 영구 컨텍스트를 JSON 파일로 저장하는 ContextStore.
 * 기동 시 파일에서 로드, put/putAll/clear 시 파일에 저장.
 * 동시 접근 시 lock으로 보호.
 */
class FileBackedContextStore(
    private val scope: ContextScope,
    private val persistenceKind: PersistenceKind,
    private val file: File
) : ContextStore {

    private val lock = Any()
    private val map = ConcurrentHashMap<String, String>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }

    init {
        loadFromFile()
    }

    override fun getScope(): ContextScope = scope
    override fun getPersistenceKind(): PersistenceKind = persistenceKind

    override fun snapshot(): ContextSnapshot = synchronized(lock) { map.toMap() }

    override fun put(key: String, value: String) {
        synchronized(lock) {
            map[key] = value
            saveToFile()
        }
    }

    override fun putAll(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            map.putAll(entries)
            saveToFile()
        }
    }

    override fun clear() {
        synchronized(lock) {
            map.clear()
            saveToFile()
        }
    }

    private fun loadFromFile() {
        if (!file.isFile) return
        try {
            val content = file.readText().ifBlank { "{\"data\":{}}" }
            val parsed = json.decodeFromString(PersistentContextFile.serializer(), content)
            map.clear()
            if (parsed.data.isNotEmpty()) map.putAll(parsed.data)
        } catch (_: Exception) {
            // 파싱 실패 시 빈 상태 유지
        }
    }

    private fun saveToFile() {
        try {
            file.parentFile?.mkdirs()
            val content = json.encodeToString(PersistentContextFile.serializer(), PersistentContextFile(map.toMap()))
            val temp = File(file.absolutePath + ".tmp")
            temp.writeText(content)
            temp.setLastModified(System.currentTimeMillis())
            if (!temp.renameTo(file)) {
                file.writeText(content)
            }
        } catch (_: Exception) {
            // 저장 실패 시 메모리 상태만 유지
        }
    }
}
