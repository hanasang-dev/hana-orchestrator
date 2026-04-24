package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.domain.entity.ExecutionStatus
import com.hana.orchestrator.presentation.model.metrics.ExecutionSummary
import com.hana.orchestrator.presentation.model.metrics.OrchestratorMetrics

class MetricsService {

    fun compute(histories: List<ExecutionHistory>): OrchestratorMetrics {
        val finished = histories.filter {
            it.status == ExecutionStatus.COMPLETED || it.status == ExecutionStatus.FAILED
        }
        val completed = finished.filter { it.status == ExecutionStatus.COMPLETED }
        val failed = finished.filter { it.status == ExecutionStatus.FAILED }

        val allSteps = finished.flatMap { it.result.stepHistory }
        val autoStepCount = allSteps.count { it.reasoning.startsWith("자동 실행:") }
        val errorStepCount = allSteps.count { it.result.startsWith("ERROR") }
        val maxStepsHitCount = finished.count { it.result.error?.contains("최대 ReAct 스텝") == true }

        val completionRate = if (finished.isNotEmpty()) completed.size.toDouble() / finished.size else 0.0
        val avgStepsToFinish = if (completed.isNotEmpty()) completed.map { it.result.stepHistory.size }.average() else 0.0
        val avgDurationMs = finished.mapNotNull { it.duration }.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: 0L
        val autoNextStepRate = if (allSteps.isNotEmpty()) autoStepCount.toDouble() / allSteps.size else 0.0
        val errorStepRate = if (allSteps.isNotEmpty()) errorStepCount.toDouble() / allSteps.size else 0.0

        val recentExecutions = histories.take(10).map { h ->
            val steps = h.result.stepHistory
            ExecutionSummary(
                id = h.id,
                query = h.query.take(80),
                status = h.status.name,
                steps = steps.size,
                durationMs = h.duration,
                autoSteps = steps.count { it.reasoning.startsWith("자동 실행:") },
                errorSteps = steps.count { it.result.startsWith("ERROR") }
            )
        }

        return OrchestratorMetrics(
            totalExecutions = finished.size,
            completedCount = completed.size,
            failedCount = failed.size,
            completionRate = completionRate,
            avgStepsToFinish = avgStepsToFinish,
            maxStepsHitCount = maxStepsHitCount,
            autoNextStepRate = autoNextStepRate,
            errorStepRate = errorStepRate,
            avgDurationMs = avgDurationMs,
            recentExecutions = recentExecutions
        )
    }
}
