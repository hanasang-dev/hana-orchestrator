package com.hana.orchestrator.llm.embedding

import com.hana.orchestrator.layer.LayerDescription
import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import kotlin.math.sqrt

/**
 * 레이어 임베딩 인덱스
 *
 * 레이어 설명을 벡터로 사전 임베딩해두고,
 * 쿼리와의 코사인 유사도로 가장 관련된 레이어를 빠르게 선택.
 *
 * TBox/ABox 패턴:
 * - TBox(전체 레이어 목록)는 항상 프롬프트에 포함
 * - ABox(함수 상세 스펙)는 top-k 관련 레이어만 포함 → 컨텍스트 대폭 감소
 */
class LayerEmbeddingIndex(
    private val embeddingClient: EmbeddingClient,
    private val topK: Int = 5
) {
    private val logger = createOrchestratorLogger(LayerEmbeddingIndex::class.java, null)

    // 레이어명 → (LayerDescription, 임베딩 벡터)
    private val index = mutableMapOf<String, Pair<LayerDescription, FloatArray>>()

    /**
     * 레이어 목록을 임베딩해 인덱스 구축.
     * 서버 시작 후 레이어 초기화 완료 시점에 1회 호출.
     */
    suspend fun build(layers: List<LayerDescription>) {
        logger.info("🔍 [EmbeddingIndex] ${layers.size}개 레이어 임베딩 시작...")
        var successCount = 0
        for (layer in layers) {
            val text = buildIndexText(layer)
            val vector = embeddingClient.embed(text)
            if (vector.isNotEmpty()) {
                index[layer.name] = Pair(layer, vector)
                successCount++
            } else {
                logger.warn("⚠️ [EmbeddingIndex] '${layer.name}' 임베딩 실패 — 인덱스 제외")
            }
        }
        logger.info("✅ [EmbeddingIndex] 인덱스 완료: $successCount/${layers.size}개")
    }

    /**
     * 새 레이어 핫로드 시 인덱스 갱신
     */
    suspend fun addOrUpdate(layer: LayerDescription) {
        val text = buildIndexText(layer)
        val vector = embeddingClient.embed(text)
        if (vector.isNotEmpty()) {
            index[layer.name] = Pair(layer, vector)
            logger.info("🔄 [EmbeddingIndex] '${layer.name}' 인덱스 갱신")
        }
    }

    fun remove(layerName: String) {
        index.remove(layerName)
    }

    /**
     * 쿼리와 가장 관련된 top-k 레이어 반환.
     * pinnedLayerNames에 포함된 레이어는 점수와 무관하게 항상 포함.
     * 임베딩 실패(인덱스 비어있음) 시 전체 레이어 반환 (fallback).
     */
    suspend fun findRelevant(
        query: String,
        allLayers: List<LayerDescription>,
        pinnedLayerNames: Set<String> = setOf("file-system")
    ): List<LayerDescription> {
        if (index.isEmpty()) {
            logger.warn("⚠️ [EmbeddingIndex] 인덱스 비어있음 — 전체 레이어 반환 (fallback)")
            return allLayers
        }

        val queryVector = embeddingClient.embed(query)
        if (queryVector.isEmpty()) {
            logger.warn("⚠️ [EmbeddingIndex] 쿼리 임베딩 실패 — 전체 레이어 반환 (fallback)")
            return allLayers
        }

        // pinned 레이어 먼저 확보 (점수 무관)
        val pinned = allLayers.filter { it.name in pinnedLayerNames }
        val pinnedNames = pinned.map { it.name }.toSet()

        // 나머지 레이어 중 top-k
        val scored = index.values
            .filter { (layer, _) -> layer.name !in pinnedNames }
            .map { (layer, vector) -> layer to cosineSimilarity(queryVector, vector) }
            .sortedByDescending { it.second }

        val topK = scored.take(topK)
        val result = pinned + topK.map { it.first }
        logger.info("🎯 [EmbeddingIndex] ABox ${result.size}개 (pinned=${pinned.map{it.name}}, top-${topK.size}=${topK.joinToString(", ") { "${it.first.name}(${String.format("%.2f", it.second)})" }})")

        return result
    }

    val isReady: Boolean get() = index.isNotEmpty()

    // 임베딩용 텍스트: 레이어명 + 설명 + 함수명 목록
    private fun buildIndexText(layer: LayerDescription): String {
        val funcNames = if (layer.functionDetails.isNotEmpty()) {
            layer.functionDetails.keys.joinToString(", ")
        } else {
            layer.functions.joinToString(", ")
        }
        return "${layer.name}: ${layer.description}\n함수: $funcNames"
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }
}
