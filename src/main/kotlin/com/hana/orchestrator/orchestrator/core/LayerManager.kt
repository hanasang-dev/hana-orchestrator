package com.hana.orchestrator.orchestrator.core

import com.hana.orchestrator.layer.CommonLayerInterface
import com.hana.orchestrator.layer.LayerDescription
import com.hana.orchestrator.layer.LayerFactory
import com.hana.orchestrator.layer.LayerInfoLayer
import com.hana.orchestrator.layer.RequiresSelfAction
import com.hana.orchestrator.layer.SelfAction
import com.hana.orchestrator.layer.SelfActionTiming
import com.hana.orchestrator.layer.Shared
import com.hana.orchestrator.layer.SharedLayer
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.orchestrator.ApprovalGate
import com.hana.orchestrator.orchestrator.ApprovalPolicy
import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 레이어 관리 책임
 * SRP: 레이어 등록, 조회, 설명 관리만 담당
 */
class LayerManager(
    private val modelSelectionStrategy: ModelSelectionStrategy? = null,
    private val approvalGate: ApprovalGate? = null
) {
    private val approvalPolicy = ApprovalPolicy(approvalGate)
    // CopyOnWriteArrayList: 읽기(filter/iteration) lock-free, 쓰기 시 복사 — registerLayer 빈도 낮아 적합
    private val layers = java.util.concurrent.CopyOnWriteArrayList<CommonLayerInterface>()
    // ConcurrentHashMap: findLayerByName 등 lock 없이 안전한 읽기
    private val layerNameMap = java.util.concurrent.ConcurrentHashMap<String, CommonLayerInterface>()
    private val cachedDescriptions = mutableSetOf<LayerDescription>() // mutex로 보호
    @Volatile private var isInitialized = false
    private val initMutex = Mutex() // ensureInitialized double-checked locking용
    private val writeMutex = Mutex() // registerLayer / unregisterLayer / cache 쓰기용
    private val logger = createOrchestratorLogger(LayerManager::class.java, null)

    /** @Shared 함수명 → 원본 레이어 인덱스 (null = dirty, 다음 접근 시 재빌드) */
    @Volatile private var sharedFunctionIndex: Map<String, CommonLayerInterface>? = null
    /** describe 캐시 무효화 알림용 SharedLayer 참조 */
    private var sharedLayerRef: SharedLayer? = null

    private var reactiveExecutor: ReactiveExecutor? = null
    private var strategyContext: StrategyContext? = null
    private var llmClientFactory: com.hana.orchestrator.llm.factory.LLMClientFactory? = null

    /**
     * StrategyLayer의 전략 핫로드에 필요한 의존성을 설정한다.
     * Orchestrator 초기화 완료 후, 첫 요청 전에 호출되어야 한다.
     */
    fun wireReactiveExecutor(executor: ReactiveExecutor, ctx: StrategyContext) {
        reactiveExecutor = executor
        strategyContext = ctx
    }

    /**
     * DevelopLayer의 내부 LLM 호출에 필요한 팩토리를 설정한다.
     */
    fun wireLlmClientFactory(factory: com.hana.orchestrator.llm.factory.LLMClientFactory) {
        llmClientFactory = factory
    }
    
    /**
     * 기본 레이어 초기화 (lazy initialization)
     */
    private suspend fun ensureInitialized() {
        if (isInitialized) return          // fast path — @Volatile, lock 불필요
        initMutex.withLock {
            if (isInitialized) return@withLock  // double-checked: 대기 중 다른 코루틴이 완료했을 수 있음

            // LayerInfoLayer 먼저 등록 (다른 레이어 정보 조회용)
            val layerInfoLayer = LayerInfoLayer()
            layerInfoLayer.setLayerManager(this)
            val layerInfoDesc = layerInfoLayer.describe()
            layerNameMap[layerInfoDesc.name] = layerInfoLayer
            layers.add(layerInfoLayer)
            logger.debug("  - 레이어 인스턴스 생성됨: LayerInfoLayer")

            // 기본 레이어 등록
            val projectRootForLayers = java.io.File(System.getProperty("user.dir"))
            val defaultLayers = LayerFactory.createDefaultLayers(modelSelectionStrategy, projectRootForLayers)
            logger.info("🔧 [LayerManager] 기본 레이어 초기화: ${defaultLayers.size}개 레이어 등록")
            defaultLayers.forEach { layer ->
                logger.debug("  - 레이어 인스턴스 생성됨: ${layer::class.simpleName}")
                val desc = layer.describe()
                layerNameMap[desc.name] = layer
            }
            layers.addAll(defaultLayers)

            // SharedLayer에 LayerManager 주입 (동적 @Shared 탐색용)
            defaultLayers.filterIsInstance<SharedLayer>().firstOrNull()?.also { sl ->
                sl.setLayerManager(this)
                sharedLayerRef = sl
            }

            // DevelopLayer에 LayerManager + LLMClientFactory 주입
            defaultLayers.filterIsInstance<com.hana.orchestrator.layer.DevelopLayer>()
                .firstOrNull()?.also { developLayer ->
                    developLayer.setLayerManager(this)
                    llmClientFactory?.let { developLayer.setLlmClientFactory(it) }
                }

            // StrategyLayer에 ReactiveExecutor + StrategyContext 주입 (전략 핫로드용)
            defaultLayers.filterIsInstance<com.hana.orchestrator.layer.StrategyLayer>()
                .firstOrNull()?.also { strategyLayer ->
                    reactiveExecutor?.let { strategyLayer.setReactiveExecutor(it) }
                    strategyContext?.let { strategyLayer.setStrategyContext(it) }
                }

            // CoreEvaluationLayer에 ReactiveExecutor + StrategyContext 주입 (runScenario용)
            defaultLayers.filterIsInstance<com.hana.orchestrator.layer.CoreEvaluationLayer>()
                .firstOrNull()?.also { coreEvalLayer ->
                    reactiveExecutor?.let { coreEvalLayer.setReactiveExecutor(it) }
                    strategyContext?.let { coreEvalLayer.setStrategyContext(it) }
                }

            // AgentLayer에 GoalExecutor 주입 (ReactiveExecutor + historyManager 람다로 감쌈)
            defaultLayers.filterIsInstance<com.hana.orchestrator.layer.AgentLayer>()
                .firstOrNull()?.also { agentLayer ->
                    val executor = reactiveExecutor
                    val hm = strategyContext?.historyManager
                    if (executor != null && hm != null) {
                        agentLayer.setGoalExecutor { goal ->
                            val subId = java.util.UUID.randomUUID().toString()
                            val subStart = System.currentTimeMillis()
                            // historyManager에 서브 실행 등록 (DefaultReActStrategy.execute 진입 전 필수)
                            hm.setCurrentExecution(
                                com.hana.orchestrator.domain.entity.ExecutionHistory.createRunning(subId, goal, subStart)
                            )
                            hm.addLogTo(subId, "🚀 [SubAgent] 실행 시작: $goal")
                            try {
                                val result = executor.execute(goal, subId, subStart)
                                result.result.ifEmpty { result.error ?: "완료 (결과 없음)" }
                            } finally {
                                hm.clearCurrentExecution(subId)
                            }
                        }
                    }
                }

            // 영속 레지스트리에서 이전에 핫로드된 레이어 복원
            val projectRoot = java.io.File(System.getProperty("user.dir"))
            val persistedLayers = com.hana.orchestrator.layer.LayerRegistry.loadAll(
                projectRoot, LayerManager::class.java.classLoader
            )
            var restoredCount = 0
            for (layer in persistedLayers) {
                val desc = layer.describe()
                if (!layerNameMap.containsKey(desc.name)) {
                    layers.add(layer)
                    layerNameMap[desc.name] = layer
                    restoredCount++
                    logger.info("  - 영속 레이어 복원: ${layer::class.simpleName} (${desc.name})")
                }
            }
            if (restoredCount > 0) {
                logger.info("♻️ [LayerManager] 영속 레지스트리에서 ${restoredCount}개 레이어 복원 완료")
            }

            isInitialized = true
            logger.info("✅ [LayerManager] 총 ${layers.size}개 레이어 등록 완료")
        }
    }
    
    /**
     * 기본 레이어 초기화 (public API)
     */
    suspend fun initializeDefaultLayers() {
        ensureInitialized()
    }
    
    /**
     * 레이어 등록
     */
    suspend fun registerLayer(layer: CommonLayerInterface) {
        ensureInitialized()
        val desc = layer.describe()
        writeMutex.withLock {
            layers.add(layer)
            layerNameMap[desc.name] = layer
            cachedDescriptions.clear()
            sharedFunctionIndex = null
            sharedLayerRef?.invalidateCache()
        }
    }

    /**
     * 레이어 등록 해제 (reloadLayer에서 교체 전 사용)
     * @return 실제로 제거됐으면 true, 등록된 적 없으면 false
     */
    suspend fun unregisterLayer(layerName: String): Boolean {
        ensureInitialized()
        return writeMutex.withLock {
            val layer = layerNameMap.remove(layerName) ?: return@withLock false
            layers.remove(layer)
            cachedDescriptions.clear()
            sharedFunctionIndex = null
            sharedLayerRef?.invalidateCache()
            true
        }
    }
    
    /**
     * 레이어 조회 (캐시된 이름 매핑 사용)
     */
    fun findLayerByName(layerName: String): CommonLayerInterface? {
        return layerNameMap[layerName]
    }

    /**
     * @RequiresSelfAction 어노테이션 조회 — Java 리플렉션 사용
     * functionName 함수가 성공한 뒤 자동 실행해야 할 같은 레이어의 함수 이름 반환.
     * 어노테이션 없거나 레이어 미존재 시 null.
     */
    fun findRequiredSelfAction(layerName: String, functionName: String): String? {
        val layer = findLayerByName(layerName) ?: return null
        return layer::class.java.declaredMethods
            .firstOrNull { it.name == functionName }
            ?.getAnnotation(RequiresSelfAction::class.java)
            ?.function
    }

    /**
     * @Shared 표시된 함수를 보유한 레이어 탐색.
     * 결과를 인덱스에 캐시 — 레이어 등록/해제 시 자동 무효화.
     */
    fun findSharedFunctionLayer(functionName: String): CommonLayerInterface? {
        return getSharedIndex()[functionName]
    }

    /** @Shared 인덱스 반환 (dirty 시 재빌드) */
    private fun getSharedIndex(): Map<String, CommonLayerInterface> {
        return sharedFunctionIndex ?: buildSharedIndex().also { sharedFunctionIndex = it }
    }

    private fun buildSharedIndex(): Map<String, CommonLayerInterface> {
        val index = mutableMapOf<String, CommonLayerInterface>()
        for (layer in layers) {
            if (layer is SharedLayer) continue
            layer::class.java.declaredMethods
                .filter { it.isAnnotationPresent(Shared::class.java) }
                .forEach { m ->
                    if (!index.containsKey(m.name)) {
                        index[m.name] = layer
                    } else {
                        logger.warn("⚠️ [LayerManager] @Shared '${m.name}' 중복 — ${layer::class.simpleName} 무시, 첫 번째 유지")
                    }
                }
        }
        logger.debug("🗂️ [LayerManager] @Shared 인덱스 빌드 완료: ${index.keys}")
        return index
    }

    /**
     * @SelfAction(PRE) 어노테이션 조회
     */
    private fun getPreSelfActions(layer: CommonLayerInterface, functionName: String): List<SelfAction> {
        return layer::class.java.declaredMethods
            .firstOrNull { it.name == functionName }
            ?.getAnnotationsByType(SelfAction::class.java)
            ?.filter { it.timing == SelfActionTiming.PRE }
            ?.toList()
            ?: emptyList()
    }
    
    /**
     * 모든 레이어 조회
     */
    fun getAllLayers(): List<CommonLayerInterface> {
        return layers.toList()
    }
    
    /**
     * 모든 레이어 설명 조회 (캐시 사용)
     */
    suspend fun getAllLayerDescriptions(): List<LayerDescription> {
        ensureInitialized()
        // 캐시 확인 (writeMutex 밖에서 읽어도 됨 — 캐시가 있으면 안전)
        val cached = writeMutex.withLock { cachedDescriptions.toList().takeIf { it.isNotEmpty() } }
        if (cached != null) return cached
        // 캐시 없으면 rebuild — describe()는 suspend라 mutex 밖에서 호출
        val snapshot = layers.toList()
        val descriptions = snapshot.map { it.describe() }
        writeMutex.withLock {
            if (cachedDescriptions.isEmpty()) {  // 다른 코루틴이 먼저 채웠을 수 있음
                cachedDescriptions.addAll(descriptions)
            }
        }
        return descriptions
    }
    
    /**
     * 레이어에서 함수 실행 (LLM 생성 트리용 — 승인 게이트 적용)
     * 승인 정책은 레이어의 approvalPreview().kind가 단독 결정:
     *   READ_ONLY  → 항상 스킵
     *   그 외       → ApprovalGate 대기 (scheduledBypass 시 자동 통과)
     */
    suspend fun executeOnLayer(layerName: String, function: String, args: Map<String, Any> = emptyMap()): String {
        ensureInitialized()
        val targetLayer = findLayerByName(layerName)
            ?: return "Layer '$layerName' not found. Available layers: ${layerNameMap.keys.toList()}"

        val normalizedFunction = normalizeFunction(function)
        val enrichedArgs = applySelfActionPre(targetLayer, layerName, normalizedFunction, args)

        return approvalPolicy.guard(targetLayer, layerName, normalizedFunction, enrichedArgs) {
            val result = targetLayer.execute(normalizedFunction, enrichedArgs)
            if (result.startsWith("Unknown function:")) throw IllegalArgumentException(result)
            result
        }
    }

    /**
     * 오케스트레이터 내부용 실행 — 승인 게이트 없음.
     * context store 저장, @SelfAction 연계 등 시스템 내부 동작에만 사용.
     */
    suspend fun executeOnLayerInternal(layerName: String, function: String, args: Map<String, Any> = emptyMap()): String {
        ensureInitialized()
        val targetLayer = findLayerByName(layerName)
            ?: return "Layer '$layerName' not found. Available layers: ${layerNameMap.keys.toList()}"

        val normalizedFunction = normalizeFunction(function)
        val result = targetLayer.execute(normalizedFunction, args)
        if (result.startsWith("Unknown function:")) throw IllegalArgumentException(result)
        return result
    }

    /** snake_case → camelCase 변환 */
    private fun normalizeFunction(function: String): String =
        if (function.contains('_')) function.replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() }
        else function

    /** @SelfAction(PRE) 처리 — 승인 게이트 전 실행, 결과를 args["context"]에 주입 */
    private suspend fun applySelfActionPre(
        layer: CommonLayerInterface,
        layerName: String,
        function: String,
        args: Map<String, Any>
    ): Map<String, Any> {
        val preActions = getPreSelfActions(layer, function)
        if (preActions.isEmpty()) return args
        return preActions.fold(args) { currentArgs, action ->
            val sourceLayer = findSharedFunctionLayer(action.function)
            if (sourceLayer == null) {
                logger.warn("⚠️ [LayerManager] @SelfAction PRE: @Shared '${action.function}' 레이어 없음 — 스킵")
                currentArgs
            } else {
                logger.info("🔀 [LayerManager] @SelfAction PRE: ${sourceLayer::class.simpleName}.${action.function} → $layerName.$function")
                try {
                    val result = sourceLayer.execute(action.function, currentArgs)
                    currentArgs + mapOf("context" to result)
                } catch (e: Exception) {
                    logger.warn("⚠️ [LayerManager] @SelfAction PRE 실패: ${e.message} — 스킵")
                    currentArgs
                }
            }
        }
    }
}
