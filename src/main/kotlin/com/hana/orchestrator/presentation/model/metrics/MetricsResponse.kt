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
    val recentExecutions: List<ExecutionSummary>
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
