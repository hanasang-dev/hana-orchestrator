package com.hana.orchestrator.presentation.model.metrics

import kotlinx.serialization.Serializable

@Serializable
data class OrchestratorMetrics(
    val totalExecutions: Int,
    val completedCount: Int,
    val failedCount: Int,
    val completionRate: Double,
    val avgStepsToFinish: Double,
    val maxStepsHitCount: Int,
    val autoNextStepRate: Double,
    val errorStepRate: Double,
    val avgDurationMs: Long,
    val recentExecutions: List<ExecutionSummary>,
    val layerStats: Map<String, LayerStats> = emptyMap()  // "layerName.function" → stats
)

@Serializable
data class LayerStats(
    val totalCalls: Int,
    val successCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val successRate: Double,
    val recentErrors: List<String> = emptyList()  // 최근 에러 메시지 (최대 3개)
)

@Serializable
data class ExecutionSummary(
    val id: String,
    val query: String,
    val status: String,
    val steps: Int,
    val durationMs: Long?,
    val autoSteps: Int,
    val errorSteps: Int
)
