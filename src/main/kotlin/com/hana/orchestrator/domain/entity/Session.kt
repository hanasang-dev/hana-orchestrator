package com.hana.orchestrator.domain.entity

import kotlinx.serialization.Serializable

/**
 * 세션 — 연속된 쿼리 실행의 묶음.
 *
 * 실행 이력(ExecutionHistory)의 상위 컨텍스트.
 * 세션은 실행을 소유하지 않고 executionId로 참조만 함.
 *
 * tasks: 유사 쿼리는 동일 SessionTask에 executionId 추가.
 *        다른 쿼리는 새 SessionTask 추가.
 * 시간 흐름: tasks 배열 순서 = 쿼리 실행 순서.
 */
@Serializable
data class Session(
    val id: String,
    val createdAt: Long = System.currentTimeMillis(),
    val tasks: MutableList<SessionTask> = mutableListOf()
)

/**
 * 세션 내 단일 실행 기록.
 * 시간순 append — 병합 없음. tasks 배열 순서 = 대화 흐름.
 */
@Serializable
data class SessionTask(
    val query: String,
    val executionId: String
)
