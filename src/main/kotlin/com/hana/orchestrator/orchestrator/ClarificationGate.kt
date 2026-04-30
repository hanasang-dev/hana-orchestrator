package com.hana.orchestrator.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 사용자 질문 게이트
 * 정보가 부족할 때 사용자에게 질문하고 답변을 받을 때까지 suspend됨
 * SRP: 질문 요청 생성, 대기, 답변 처리만 담당
 */
class ClarificationGate {
    private val logger = LoggerFactory.getLogger(ClarificationGate::class.java)

    /** 스케줄러 무인 실행 중 — "ask" 액션을 빈 문자열로 자동 통과 (루프 진행 유지) */
    @Volatile var scheduledBypass: Boolean = false

    @Serializable
    data class ClarificationRequest(
        val type: String = "CLARIFICATION_REQUIRED",
        val id: String,
        val question: String
    )

    private data class PendingClarification(
        val request: ClarificationRequest,
        val deferred: CompletableDeferred<String>
    )

    private val _requests = MutableSharedFlow<ClarificationRequest>(replay = 0, extraBufferCapacity = 10)
    val requests: SharedFlow<ClarificationRequest> = _requests.asSharedFlow()

    private val pending = ConcurrentHashMap<String, PendingClarification>()

    /**
     * 질문 요청: 사용자가 answer()를 호출할 때까지 suspend됨
     * scheduledBypass=true면 즉시 자율 실행 안내 메시지 반환 (무인 실행 — LLM이 스스로 결정)
     * timeoutMs 초과 시 빈 문자열 반환 (기본 5분)
     * @return 사용자 답변 문자열
     */
    suspend fun requestClarification(question: String, timeoutMs: Long = 5 * 60 * 1000L): String {
        if (scheduledBypass) {
            logger.info("📅 [ClarificationGate] bypass — 자율 실행 모드: $question")
            return "[자율 실행] 사용자 확인 없이 자율 실행 중입니다. 스스로 가장 합리적인 선택을 결정하고 즉시 진행하세요. 추가 질문 없이 직접 실행하세요."
        }
        val id = UUID.randomUUID().toString().take(8)
        val request = ClarificationRequest(id = id, question = question)
        val deferred = CompletableDeferred<String>()
        pending[id] = PendingClarification(request, deferred)
        _requests.emit(request)
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
                logger.warn("⏰ [ClarificationGate] 타임아웃 — 빈 답변으로 진행: $question")
                ""
            }
        } finally {
            pending.remove(id)
        }
    }

    /** 사용자 답변 제출 */
    fun answer(id: String, answer: String): Boolean = pending[id]?.deferred?.complete(answer) ?: false

    /** 대기 중인 질문 목록 */
    fun getPending(): List<ClarificationRequest> = pending.values.map { it.request }
}
