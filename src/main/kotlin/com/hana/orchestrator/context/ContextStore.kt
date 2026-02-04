package com.hana.orchestrator.context

/** 읽기 전용 스냅샷. 키-값 맵. */
typealias ContextSnapshot = Map<String, String>

/**
 * 특정 스코프·영구성의 컨텍스트 저장소.
 * 확장 시 스코프/영구성별 구현 추가.
 */
interface ContextStore {
    fun getScope(): ContextScope
    fun getPersistenceKind(): PersistenceKind

    /** 현재 스냅샷 (읽기 전용). */
    fun snapshot(): ContextSnapshot

    fun put(key: String, value: String)
    fun putAll(entries: Map<String, String>)
    fun clear()
}
