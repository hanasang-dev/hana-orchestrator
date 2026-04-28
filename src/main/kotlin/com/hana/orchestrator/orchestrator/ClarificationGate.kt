package com.hana.orchestrator.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 사용자 질문 게이트
 * 정보가 부족할 때 사용자에게 질문하고 답변을 받을 때까지 suspend됨
 * SRP: 질문 요청 생성, 대기, 답변 처리만 담당
 */
class ClarificationGate {

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
     * @return 사용자 답변 문자열
     */
    suspend fun requestClarification(question: String): String {
        val id = UUID.randomUUID().toString().take(8)
        val request = ClarificationRequest(id = id, question = question)
        val deferred = CompletableDeferred<String>()
        pending[id] = PendingClarification(request, deferred)
        _requests.emit(request)
        return try {
            deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    /** 사용자 답변 제출 */
    fun answer(id: String, answer: String): Boolean = pending[id]?.deferred?.complete(answer) ?: false

    /** 대기 중인 질문 목록 */
    fun getPending(): List<ClarificationRequest> = pending.values.map { it.request }
}
