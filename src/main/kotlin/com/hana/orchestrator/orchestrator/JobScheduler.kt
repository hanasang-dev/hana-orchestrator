package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.domain.entity.JobSchedule
import com.hana.orchestrator.domain.entity.ScheduledJob
import com.hana.orchestrator.domain.dto.ChatDto
import com.hana.orchestrator.presentation.mapper.ExecutionTreeMapper.toDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * 스케줄 작업 실행기
 *
 * 60초마다 JobRepository를 폴링 → nextRunAt 도달한 활성 작업 실행
 * 실행은 Orchestrator에 위임 (ReAct 또는 저장 트리 직접 실행)
 * 실행 결과는 기존 ExecutionHistory에 자동으로 쌓임
 */
class JobScheduler(
    private val jobRepository: JobRepository,
    private val treeRepository: TreeRepository,
    private val orchestrator: Orchestrator,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(JobScheduler::class.java)

    fun start() {
        scope.launch {
            logger.info("📅 [JobScheduler] 시작 — 60초 간격 폴링")
            while (isActive) {
                runDueJobs()
                delay(60_000)
            }
        }
    }

    /** 지금 당장 특정 Job 실행 (수동 트리거) */
    fun triggerNow(id: String): Boolean {
        val job = jobRepository.load(id) ?: return false
        scope.launch { runJob(job) }
        return true
    }

    /** nextRunAt 계산해서 저장 (등록/수정 시 호출) */
    fun scheduleNext(job: ScheduledJob): ScheduledJob {
        val nextRun = calcNextRun(job.schedule, System.currentTimeMillis())
        return job.copy(nextRunAt = nextRun)
    }

    // ── private ──────────────────────────────────────────────────────────────

    private suspend fun runDueJobs() {
        val now = System.currentTimeMillis()
        jobRepository.list()
            .filter { it.enabled && (it.nextRunAt ?: 0) <= now }
            .forEach { job ->
                logger.info("📅 [JobScheduler] 실행: ${job.name} (id=${job.id})")
                runJob(job)
            }
    }

    private suspend fun runJob(job: ScheduledJob) {
        val status = try {
            if (job.treeId != null) {
                runTreeJob(job)
            } else {
                runReActJob(job)
            }
            "SUCCESS"
        } catch (e: Exception) {
            logger.error("📅 [JobScheduler] 실패: ${job.name} — ${e.message}", e)
            "FAILED"
        }

        val now = System.currentTimeMillis()
        val next = calcNextRun(job.schedule, now)
        jobRepository.save(
            job.copy(lastRunAt = now, nextRunAt = next, lastStatus = status)
        )
    }

    private suspend fun runReActJob(job: ScheduledJob) {
        val finalQuery = if (job.includeMetrics) {
            val metrics = orchestrator.computeMetrics()
            buildMetricsContext(metrics) + "\n\n" + job.query
        } else {
            job.query
        }
        orchestrator.executeOrchestration(ChatDto(message = finalQuery))
    }

    private fun buildMetricsContext(metrics: com.hana.orchestrator.presentation.model.metrics.OrchestratorMetrics): String {
        val sb = StringBuilder()
        sb.appendLine("[시스템 메트릭 스냅샷]")
        sb.appendLine("- 총 실행: ${metrics.totalExecutions}건 (성공 ${metrics.completedCount} / 실패 ${metrics.failedCount})")
        sb.appendLine("- 성공률: ${"%.1f".format(metrics.completionRate * 100)}%")
        sb.appendLine("- 평균 ReAct 스텝: ${"%.1f".format(metrics.avgStepsToFinish)}")
        sb.appendLine("- 에러 스텝 비율: ${"%.1f".format(metrics.errorStepRate * 100)}%")
        sb.appendLine("- 최대 스텝 초과: ${metrics.maxStepsHitCount}건")
        sb.appendLine("- 평균 실행 시간: ${metrics.avgDurationMs}ms")
        if (metrics.layerStats.isNotEmpty()) {
            sb.appendLine("- 레이어별 실패율 (실패 있는 것만):")
            metrics.layerStats
                .filter { it.value.failedCount > 0 }
                .entries.sortedByDescending { it.value.failedCount }
                .forEach { (key, stat) ->
                    sb.appendLine("  · $key: ${stat.failedCount}/${stat.totalCalls} 실패" +
                        if (stat.recentErrors.isNotEmpty()) " — 최근 에러: ${stat.recentErrors.last().take(80)}" else "")
                }
        }
        return sb.toString().trimEnd()
    }

    private suspend fun runTreeJob(job: ScheduledJob) {
        val savedTree = treeRepository.load(job.treeId!!)
            ?: throw IllegalArgumentException("저장된 트리 없음: ${job.treeId}")
        val domainTree = savedTree.tree.toDomain()
        orchestrator.executeCustomTree(job.query, domainTree)
    }

    private fun calcNextRun(schedule: JobSchedule, afterMs: Long): Long? = when (schedule) {
        is JobSchedule.Once -> null  // 1회성 — 다음 실행 없음
        is JobSchedule.Interval -> afterMs + schedule.intervalMs
        is JobSchedule.Daily -> {
            val zone = ZoneId.systemDefault()
            val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(afterMs), zone)
            var next = now.toLocalDate().atTime(schedule.hour, schedule.minute)
            if (!next.isAfter(now)) next = next.plusDays(1)
            next.atZone(zone).toInstant().toEpochMilli()
        }
    }
}
