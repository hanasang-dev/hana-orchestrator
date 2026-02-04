package com.hana.orchestrator.context

/**
 * 영구 컨텍스트 갱신 조건.
 * projectRoot가 바뀌었을 때 등에 사용.
 */
data class PersistentRefreshTrigger(val projectRoot: String?)
