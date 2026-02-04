package com.hana.orchestrator.context

import java.util.concurrent.ConcurrentHashMap

/**
 * 인메모리 컨텍스트 저장소.
 * 동시 요청에서 안전하게 사용 (ConcurrentHashMap).
 */
class InMemoryContextStore(
    private val scope: ContextScope,
    private val persistenceKind: PersistenceKind,
    initialEntries: Map<String, String> = emptyMap()
) : ContextStore {

    private val map = ConcurrentHashMap<String, String>().apply { putAll(initialEntries) }

    override fun getScope(): ContextScope = scope
    override fun getPersistenceKind(): PersistenceKind = persistenceKind

    override fun snapshot(): ContextSnapshot = map.toMap()

    override fun put(key: String, value: String) {
        map[key] = value
    }

    override fun putAll(entries: Map<String, String>) {
        map.putAll(entries)
    }

    override fun clear() {
        map.clear()
    }
}
