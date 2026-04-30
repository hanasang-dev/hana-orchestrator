package com.hana.orchestrator.domain.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 스케줄 작업 정의
 *
 * query만 있으면 → ReAct 루프 실행
 * treeId도 있으면 → 저장된 트리 직접 실행 (LLM 스킵)
 */
@Serializable
data class ScheduledJob(
    val id: String,
    val name: String,
    val query: String,
    val treeId: String? = null,
    val schedule: JobSchedule,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
    val lastStatus: String? = null,  // "SUCCESS" | "FAILED" | "CANCELLED"
    val includeMetrics: Boolean = false,  // true면 실행 직전 메트릭 스냅샷을 쿼리에 주입
    val autoApprove: Boolean = false      // true면 모든 승인 게이트 자동 통과 (무인 실행용)
)

@Serializable
sealed class JobSchedule {
    /** 특정 epoch ms에 1회 실행 */
    @Serializable
    @SerialName("once")
    data class Once(val at: Long) : JobSchedule()

    /** intervalMs 마다 반복 */
    @Serializable
    @SerialName("interval")
    data class Interval(val intervalMs: Long) : JobSchedule()

    /** 매일 특정 시각 실행 (24h) */
    @Serializable
    @SerialName("daily")
    data class Daily(val hour: Int, val minute: Int = 0) : JobSchedule()

    /** 완료 직후 재실행 (루프) — delayMs: 다음 실행까지 대기 시간 */
    @Serializable
    @SerialName("loop")
    data class Loop(val delayMs: Long = 0) : JobSchedule()
}
