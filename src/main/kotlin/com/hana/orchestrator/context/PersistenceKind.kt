package com.hana.orchestrator.context

/**
 * 같은 스코프 내 영구 vs 휘발 구분.
 */
enum class PersistenceKind {
    Persistent,
    Volatile
}
