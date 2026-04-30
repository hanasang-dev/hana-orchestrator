package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.domain.entity.JobSchedule
import com.hana.orchestrator.domain.entity.ScheduledJob
import com.hana.orchestrator.domain.dto.ChatDto
import com.hana.orchestrator.presentation.mapper.ExecutionTreeMapper.toDomain
import java.util.concurrent.ConcurrentHashMap
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
    /** 현재 루프 실행 중인 Job ID — 폴러 중복 기동 방지 */
    private val runningLoopIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
        if (job.schedule is JobSchedule.Loop) {
            if (runningLoopIds.add(job.id)) {
                scope.launch {
                    try { runLoopJob(job) }
                    finally { runningLoopIds.remove(job.id) }
                }
            } // 이미 실행 중이면 무시
        } else {
            scope.launch { runJob(job) }
        }
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
            .filter { it.enabled && (it.nextRunAt ?: 0) <= now && it.id !in runningLoopIds }
            .forEach { job ->
                if (job.schedule is JobSchedule.Loop) {
                    if (runningLoopIds.add(job.id)) {
                        logger.info("📅 [JobScheduler] 실행: ${job.name} (id=${job.id})")
                        scope.launch {
                            try { runLoopJob(job) }
                            finally { runningLoopIds.remove(job.id) }
                        }
                    }
                } else {
                    logger.info("📅 [JobScheduler] 실행: ${job.name} (id=${job.id})")
                    scope.launch { runJob(job) }
                }
            }
    }

    /** Loop 스케줄 전용 — 완료 후 delayMs 대기 뒤 즉시 재실행, 비활성화/삭제 시 중단 */
    private suspend fun runLoopJob(initialJob: ScheduledJob) {
        var job = initialJob
        while (true) {
            runJob(job)
            val reloaded = jobRepository.load(job.id) ?: break
            if (!reloaded.enabled || reloaded.schedule !is JobSchedule.Loop) break
            val delayMs = (reloaded.schedule as JobSchedule.Loop).delayMs
            if (delayMs > 0) delay(delayMs)
            job = jobRepository.load(job.id) ?: break
            if (!job.enabled) break
        }
        logger.info("📅 [JobScheduler] 루프 종료: ${job.name} (id=${job.id})")
    }

    private suspend fun runJob(job: ScheduledJob) {
        if (job.autoApprove) {
            orchestrator.approvalGate.scheduledBypass = true
            orchestrator.clarificationGate.scheduledBypass = true
            logger.info("📅 [JobScheduler] 자동승인 ON: ${job.name}")
        }
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
        } finally {
            if (job.autoApprove) {
                orchestrator.approvalGate.scheduledBypass = false
                orchestrator.clarificationGate.scheduledBypass = false
            }
        }

        val now = System.currentTimeMillis()
        // 실행 중 PATCH된 최신 상태 반영 (autoApprove 등 덮어쓰기 방지)
        val latest = jobRepository.load(job.id) ?: job
        val next = calcNextRun(latest.schedule, now)
        jobRepository.save(
            latest.copy(lastRunAt = now, nextRunAt = next, lastStatus = status)
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
        val projectRoot = System.getProperty("user.dir")
        val layerRoot = "$projectRoot/src/main/kotlin/com/hana/orchestrator/layer"

        val sb = StringBuilder()
        sb.appendLine("[시스템 메트릭 스냅샷]")
        sb.appendLine("- 총 실행: ${metrics.totalExecutions}건 (성공 ${metrics.completedCount} / 실패 ${metrics.failedCount})")
        sb.appendLine("- 성공률: ${"%.1f".format(metrics.completionRate * 100)}%")
        sb.appendLine("- 평균 ReAct 스텝: ${"%.1f".format(metrics.avgStepsToFinish)}")
        sb.appendLine("- 에러 스텝 비율: ${"%.1f".format(metrics.errorStepRate * 100)}%")
        sb.appendLine("- 최대 스텝 초과: ${metrics.maxStepsHitCount}건")
        sb.appendLine("- 평균 실행 시간: ${metrics.avgDurationMs}ms")

        // 전체 레이어 호출 현황 (실패 없어도 모두 표시)
        if (metrics.layerStats.isNotEmpty()) {
            sb.appendLine("- 레이어별 호출 현황:")
            metrics.layerStats.entries.sortedByDescending { it.value.totalCalls }
                .forEach { (key, stat) ->
                    val failNote = if (stat.failedCount > 0)
                        " ⚠️ 실패 ${stat.failedCount}회" +
                        if (stat.recentErrors.isNotEmpty()) " — 최근 에러: ${stat.recentErrors.last().take(80)}" else ""
                    else ""
                    sb.appendLine("  · $key: ${stat.totalCalls}회 호출, 성공률 ${"%.0f".format(stat.successRate * 100)}%$failNote")
                }
        } else {
            sb.appendLine("- 레이어 호출 이력 없음 (신규 시스템)")
        }

        // 소스 경로 컨텍스트 — LLM이 실제 파일 읽을 수 있도록
        sb.appendLine("")
        sb.appendLine("[소스 경로 정보]")
        sb.appendLine("- 레이어 디렉토리: $layerRoot")
        sb.appendLine("- 주요 레이어 파일:")
        val layerFiles = java.io.File(layerRoot).listFiles { f -> f.name.endsWith("Layer.kt") }
            ?.sortedBy { it.name } ?: emptyList()
        layerFiles.forEach { sb.appendLine("  · ${it.absolutePath}") }

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
        is JobSchedule.Loop -> afterMs + schedule.delayMs
        is JobSchedule.Daily -> {
            val zone = ZoneId.systemDefault()
            val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(afterMs), zone)
            var next = now.toLocalDate().atTime(schedule.hour, schedule.minute)
            if (!next.isAfter(now)) next = next.plusDays(1)
            next.atZone(zone).toInstant().toEpochMilli()
        }
    }
}
