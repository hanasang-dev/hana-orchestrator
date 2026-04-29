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
    val lastStatus: String? = null   // "SUCCESS" | "FAILED" | "CANCELLED"
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
}
