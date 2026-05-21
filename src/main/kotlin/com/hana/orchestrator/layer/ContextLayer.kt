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
 * - 인터페이스:  "interface:{name}"
 * - 스텝 결과:   "step_{executionId}_{n}_result"
 * - 분석 결과:   "analysis:{tag}"
 *
 * ## 저장소 분리
 * - **sessionStore**: `layer:*`, `rules:*`, `interface:*` — 앱 재시작 전까지 유지. clear()로 지워지지 않음.
 * - **execStore**: 그 외 모든 키 — 실행 완료 후 clearExecution()으로 정리됨.
 */
@Layer
class ContextLayer : CommonLayerInterface {

    /**
     * 키-값 저장. 기존 키가 있으면 덮어씀.
     * @param key 저장 키 (네이밍 컨벤션: "layer:Echo", "step_abc_3_result" 등)
     * @param value 저장할 내용 (파일 소스, 분석 결과 등 크기 제한 없음)
     */
    @LayerFunction
    suspend fun put(key: String, value: String): String {
        storeFor(key)[key] = value
        return "OK: 저장 완료 (key=\"$key\", ${value.length}자)"
    }

    /**
     * 키로 저장된 내용 조회. 없으면 에러 반환.
     * @param key 조회할 키
     */
    @LayerFunction
    suspend fun get(key: String): String {
        return storeFor(key)[key] ?: "ERROR: 키 없음: \"$key\". context.list()로 가용 키 확인"
    }

    /**
     * 저장된 모든 키 목록 반환. 내용은 포함하지 않음.
     */
    @LayerFunction
    suspend fun list(): String {
        val all = sessionStore.entries.toList() + execStore.entries.toList()
        if (all.isEmpty()) return "저장된 항목 없음"
        return all.joinToString("\n") { (k, v) -> "- $k (${v.length}자)" }
    }

    /**
     * 특정 키 삭제.
     * @param key 삭제할 키
     */
    @LayerFunction
    suspend fun delete(key: String): String {
        return if (storeFor(key).remove(key) != null) "OK: 삭제 완료 (key=\"$key\")"
        else "ERROR: 키 없음: \"$key\""
    }

    /**
     * 실행 저장소(execStore) 전체 초기화. 세션 데이터(layer:*, rules:*, interface:*)는 보존됨.
     */
    @LayerFunction
    suspend fun clear(): String {
        val count = execStore.size
        execStore.clear()
        return "OK: 실행 저장소 초기화 ($count 항목 삭제). 세션 데이터 보존됨"
    }

    /**
     * 특정 실행의 step 키만 정리. 실행 완료·취소·실패 후 자동 호출됨.
     * @param executionId 정리할 실행 ID
     */
    @LayerFunction
    suspend fun clearExecution(executionId: String): String {
        val count = Companion.clearExecution(executionId)
        return "OK: executionId=$executionId step 키 ${count}개 삭제"
    }

    override suspend fun approvalPreview(function: String, args: Map<String, Any>): ApprovalPreview =
        ApprovalPreview(path = "context.$function", oldContent = null, newContent = "", kind = ApprovalKind.READ_ONLY)

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
            "clearExecution" -> {
                val executionId = args["executionId"] as? String ?: return "ERROR: executionId 필수"
                clearExecution(executionId)
            }
            else -> throw IllegalArgumentException("Unknown function: $function. Available: put, get, list, delete, clear, clearExecution")
        }
    }

    companion object {
        /** 세션 저장소 — layer:*, rules:*, interface:* 키. 앱 재시작 전까지 유지 */
        private val sessionStore = ConcurrentHashMap<String, String>()
        /** 실행 저장소 — 나머지 키. 실행 완료 후 clearExecution()으로 정리 */
        private val execStore = ConcurrentHashMap<String, String>()

        private fun isSessionKey(key: String) =
            key.startsWith("layer:") || key.startsWith("rules:") || key.startsWith("interface:")

        private fun storeFor(key: String) = if (isSessionKey(key)) sessionStore else execStore

        /** 앱 시작 시 레이어 소스·규칙 파일 자동 populate */
        fun populate(projectRoot: java.io.File) {
            val layerDir = java.io.File(projectRoot, "src/main/kotlin/com/hana/orchestrator/layer")

            layerDir.listFiles { f ->
                f.name.endsWith("Layer.kt") && !f.name.endsWith(".bak")
            }?.forEach { file ->
                val layerName = file.nameWithoutExtension.removeSuffix("Layer")
                sessionStore["layer:$layerName"] = file.readText()
            }

            val claudeMd = java.io.File(layerDir, "CLAUDE.md")
            if (claudeMd.exists()) sessionStore["rules:layer"] = claudeMd.readText()

            val interfaceFile = java.io.File(layerDir, "CommonLayerInterface.kt")
            if (interfaceFile.exists()) sessionStore["interface:CommonLayer"] = interfaceFile.readText()
        }

        /** 실행 완료 후 해당 executionId의 step_* 키 정리. 반환값: 삭제된 키 수 */
        fun clearExecution(executionId: String): Int {
            val prefix = "step_${executionId}_"
            val keys = execStore.keys().toList().filter { it.startsWith(prefix) }
            keys.forEach { execStore.remove(it) }
            return keys.size
        }
    }
}
