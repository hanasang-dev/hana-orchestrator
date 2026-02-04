package com.hana.orchestrator.context

/**
 * 앱 단위 컨텍스트 기본 구현.
 * workingDirectory·projectRoot는 요청 시 ensureVolatileServerWorkingDirectory()로 서버 cwd를 넣음.
 */
class DefaultAppContextService(
    private val persistentStore: ContextStore,
    private val volatileStore: ContextStore,
    private val projectRulesLoader: ProjectRulesLoader = ProjectRulesLoader()
) : AppContextService {

    /** 마지막으로 영구 갱신에 사용한 projectRoot. 같으면 스킵. */
    private var lastRefreshedProjectRoot: String? = null

    override fun getPersistentStore(): ContextStore = persistentStore
    override fun getVolatileStore(): ContextStore = volatileStore

    override fun getAppContextForPrompt(): Pair<ContextSnapshot, ContextSnapshot> =
        persistentStore.snapshot() to volatileStore.snapshot()

    override fun updateVolatileFromRequest(context: Map<String, String>) {
        if (context.isNotEmpty()) volatileStore.putAll(context)
    }

    override fun ensureVolatileServerWorkingDirectory() {
        val cwd = System.getProperty("user.dir") ?: "."
        volatileStore.put("workingDirectory", cwd)
        val snap = volatileStore.snapshot()
        if (!snap.containsKey("projectRoot")) volatileStore.put("projectRoot", cwd)
    }

    override fun refreshPersistentIfNeeded(trigger: PersistentRefreshTrigger) {
        val root = trigger.projectRoot?.trim() ?: return
        if (root == lastRefreshedProjectRoot) return

        persistentStore.clear()
        val rules = projectRulesLoader.load(root)
        if (rules.isNotBlank()) {
            persistentStore.put("projectRules", rules)
        }
        lastRefreshedProjectRoot = root
    }
}
