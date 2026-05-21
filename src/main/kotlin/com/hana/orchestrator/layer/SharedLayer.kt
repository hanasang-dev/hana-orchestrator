package com.hana.orchestrator.layer

import com.hana.orchestrator.orchestrator.core.LayerManager

/**
 * 공유 레이어 — @Shared 표시된 함수들의 통합 접근점.
 *
 * 레이어 격리 원칙을 유지하면서 선택된 함수를 교차 접근 가능하게 함.
 * 구현은 원본 레이어에 위임하고, describe()도 원본 레이어 설명을 그대로 사용.
 *
 * LayerManager가 초기화 후 setLayerManager()를 호출하여 의존성을 주입.
 * 레이어 등록/해제 시 LayerManager가 invalidateCache()를 호출하여 캐시를 무효화.
 */
class SharedLayer : CommonLayerInterface {

    private var layerManager: LayerManager? = null

    /** describe() 결과 캐시 — 레이어 등록/해제 시 invalidateCache()로 초기화 */
    @Volatile private var describeCache: LayerDescription? = null

    fun setLayerManager(lm: LayerManager) {
        layerManager = lm
    }

    /** 레이어 등록/해제 시 LayerManager가 호출 */
    fun invalidateCache() {
        describeCache = null
    }

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        val lm = layerManager
            ?: return "ERROR: SharedLayer에 '$function' 함수 없음. @Shared 표시된 함수만 접근 가능."
        val source = lm.findSharedFunctionLayer(function)
            ?: return "ERROR: SharedLayer에 '$function' 함수 없음. @Shared 표시된 함수만 접근 가능."
        return source.execute(function, args)
    }

    override suspend fun describe(): LayerDescription {
        describeCache?.let { return it }

        val lm = layerManager ?: return LayerDescription(
            name = "shared",
            description = "공유 레이어 (@Shared 함수 통합 접근점)",
            functions = emptyList()
        )

        val functions = mutableListOf<String>()
        val functionDetails = mutableMapOf<String, FunctionDescription>()

        for (layer in lm.getAllLayers()) {
            if (layer is SharedLayer) continue
            val sharedMethods = layer::class.java.declaredMethods
                .filter { it.isAnnotationPresent(Shared::class.java) }
            if (sharedMethods.isEmpty()) continue

            val layerDesc = layer.describe()
            sharedMethods.forEach { method ->
                functions.add(method.name)
                layerDesc.functionDetails[method.name]?.let { functionDetails[method.name] = it }
            }
        }

        return LayerDescription(
            name = "shared",
            description = "공유 레이어 — @Shared 표시된 함수들의 통합 접근점. 구현은 원본 레이어에 위임.",
            functions = functions,
            functionDetails = functionDetails
        ).also { describeCache = it }
    }
}
