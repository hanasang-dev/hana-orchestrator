package com.hana.orchestrator.context

/**
 * 앱 단위 컨텍스트: 영구 + 휘발성.
 */
interface AppContextService {
    fun getPersistentStore(): ContextStore
    fun getVolatileStore(): ContextStore

    /** 프롬프트 조합용: (영구 스냅샷, 휘발 스냅샷). */
    fun getAppContextForPrompt(): Pair<ContextSnapshot, ContextSnapshot>

    /** 요청에 실린 context로 휘발성 덮어쓰기. */
    fun updateVolatileFromRequest(context: Map<String, String>)

    /** 서버(오케스트레이터)가 동작 중인 현재 디렉터리를 휘발성에 넣음. 호출자가 보낼 필요 없음. */
    fun ensureVolatileServerWorkingDirectory()

    /** projectRoot 등 바뀌었을 때 영구 컨텍스트 조건부 갱신. */
    fun refreshPersistentIfNeeded(trigger: PersistentRefreshTrigger)
}
