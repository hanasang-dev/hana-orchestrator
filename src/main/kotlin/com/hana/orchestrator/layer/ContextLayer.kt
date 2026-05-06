package com.hana.orchestrator.layer

import java.util.concurrent.ConcurrentHashMap

/**
 * 컨텍스트 저장소 레이어 — 대형 콘텐츠를 키-값으로 저장하고 필요할 때 꺼내는 용도.
 *
 * 목적: ReAct 루프의 stepHistory 비대화 방지.
 * 큰 결과(파일 소스, 분석 결과 등)를 여기 저장하고 프롬프트엔 키만 기록.
 * 필요할 때 get(key)으로 꺼냄.
 *
 * 키 네이밍 컨벤션:
 * - 레이어 소스: "layer:{LayerName}"  (예: "layer:Echo")
 * - 규칙 파일:   "rules:{name}"       (예: "rules:layer")
 * - 스텝 결과:   "step:{n}:{tag}"     (예: "step:3:improveResult")
 * - 분석 결과:   "analysis:{tag}"
 *
 * 세션 단위 휘발성 저장소 (앱 재시작 시 초기화).
 */
@Layer
class ContextLayer : CommonLayerInterface {

    /**
     * 키-값 저장. 기존 키가 있으면 덮어씀.
     * @param key 저장 키 (네이밍 컨벤션: "layer:Echo", "step:3:result" 등)
     * @param value 저장할 내용 (파일 소스, 분석 결과 등 크기 제한 없음)
     */
    @LayerFunction
    suspend fun put(key: String, value: String): String {
        store[key] = value
        return "OK: 저장 완료 (key=\"$key\", ${value.length}자)"
    }

    /**
     * 키로 저장된 내용 조회. 없으면 에러 반환.
     * @param key 조회할 키
     */
    @LayerFunction
    suspend fun get(key: String): String {
        return store[key] ?: "ERROR: 키 없음: \"$key\". context.list()로 가용 키 확인"
    }

    /**
     * 저장된 모든 키 목록 반환. 내용은 포함하지 않음.
     */
    @LayerFunction
    suspend fun list(): String {
        if (store.isEmpty()) return "저장된 항목 없음"
        return store.entries.joinToString("\n") { (k, v) -> "- $k (${v.length}자)" }
    }

    /**
     * 특정 키 삭제.
     * @param key 삭제할 키
     */
    @LayerFunction
    suspend fun delete(key: String): String {
        return if (store.remove(key) != null) "OK: 삭제 완료 (key=\"$key\")"
        else "ERROR: 키 없음: \"$key\""
    }

    /**
     * 저장소 전체 초기화.
     */
    @LayerFunction
    suspend fun clear(): String {
        val count = store.size
        store.clear()
        return "OK: 전체 초기화 ($count 항목 삭제)"
    }

    override suspend fun describe(): LayerDescription {
        return ContextLayer_Description.layerDescription
    }

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "put" -> {
                val key = args["key"] as? String ?: return "ERROR: key 필수"
                val value = args["value"] as? String ?: return "ERROR: value 필수"
                put(key, value)
            }
            "get" -> {
                val key = args["key"] as? String ?: return "ERROR: key 필수"
                get(key)
            }
            "list" -> list()
            "delete" -> {
                val key = args["key"] as? String ?: return "ERROR: key 필수"
                delete(key)
            }
            "clear" -> clear()
            else -> "Unknown function: $function. Available: put, get, list, delete, clear"
        }
    }

    companion object {
        /** 세션 공유 저장소 — ContextLayer 인스턴스 여러 개여도 같은 store 공유 */
        private val store = ConcurrentHashMap<String, String>()

        /** 앱 시작 시 레이어 소스·규칙 파일 자동 populate */
        fun populate(projectRoot: java.io.File) {
            val layerDir = java.io.File(projectRoot, "src/main/kotlin/com/hana/orchestrator/layer")

            // 레이어 소스 파일들
            layerDir.listFiles { f ->
                f.name.endsWith("Layer.kt") && !f.name.endsWith(".bak")
            }?.forEach { file ->
                val layerName = file.nameWithoutExtension.removeSuffix("Layer")
                store["layer:$layerName"] = file.readText()
            }

            // 레이어 개발 규칙
            val claudeMd = java.io.File(layerDir, "CLAUDE.md")
            if (claudeMd.exists()) store["rules:layer"] = claudeMd.readText()

            // CommonLayerInterface 계약
            val interfaceFile = java.io.File(layerDir, "CommonLayerInterface.kt")
            if (interfaceFile.exists()) store["interface:CommonLayer"] = interfaceFile.readText()
        }
    }
}
