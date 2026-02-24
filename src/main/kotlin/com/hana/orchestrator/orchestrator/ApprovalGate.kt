package com.hana.orchestrator.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 파일 쓰기 승인 게이트
 * writeFile 호출 전 사용자 승인을 기다리는 coroutine suspension 메커니즘
 * SRP: 승인 요청 생성, 대기, 응답 처리만 담당
 */
class ApprovalGate {

    @Serializable
    data class ApprovalRequest(
        val type: String = "APPROVAL_REQUIRED",
        val id: String,
        val path: String,
        val diff: String
    )

    private data class PendingApproval(
        val request: ApprovalRequest,
        val deferred: CompletableDeferred<Boolean>
    )

    private val _requests = MutableSharedFlow<ApprovalRequest>(replay = 0, extraBufferCapacity = 10)
    val requests: SharedFlow<ApprovalRequest> = _requests.asSharedFlow()

    private val pending = ConcurrentHashMap<String, PendingApproval>()

    /**
     * 승인 요청: 사용자가 approve/reject 할 때까지 suspend됨
     * @return true=승인, false=거절
     */
    suspend fun requestApproval(path: String, oldContent: String?, newContent: String): Boolean {
        val id = UUID.randomUUID().toString().take(8)
        val diff = buildDiff(oldContent ?: "", newContent, path)
        val request = ApprovalRequest(id = id, path = path, diff = diff)
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = PendingApproval(request, deferred)
        _requests.emit(request)
        return try {
            deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    /** 승인 */
    fun approve(id: String): Boolean = pending[id]?.deferred?.complete(true) ?: false

    /** 거절 */
    fun reject(id: String): Boolean = pending[id]?.deferred?.complete(false) ?: false

    /** 대기 중인 승인 요청 목록 */
    fun getPending(): List<ApprovalRequest> = pending.values.map { it.request }

    /**
     * 단순 unified diff 생성
     * 첫 번째 변경점부터 마지막 변경점까지 + 컨텍스트 3줄
     */
    private fun buildDiff(oldContent: String, newContent: String, path: String): String {
        if (oldContent == newContent) return "(변경 없음)"

        val oldLines = oldContent.lines()
        val newLines = newContent.lines()

        // 첫 번째 차이점
        var firstDiff = 0
        while (firstDiff < oldLines.size && firstDiff < newLines.size &&
            oldLines[firstDiff] == newLines[firstDiff]) {
            firstDiff++
        }

        // 마지막 차이점
        var lastDiffOld = oldLines.size - 1
        var lastDiffNew = newLines.size - 1
        while (lastDiffOld >= firstDiff && lastDiffNew >= firstDiff &&
            oldLines[lastDiffOld] == newLines[lastDiffNew]) {
            lastDiffOld--
            lastDiffNew--
        }

        val context = 3
        val contextStart = maxOf(0, firstDiff - context)
        val sb = StringBuilder()
        sb.append("--- $path (기존)\n")
        sb.append("+++ $path (변경)\n")
        sb.append("@@ -${contextStart + 1} +${contextStart + 1} @@\n")

        if (contextStart > 0) sb.append("...\n")

        for (i in contextStart until firstDiff) {
            sb.append("  ${oldLines[i]}\n")
        }
        for (i in firstDiff..lastDiffOld) {
            sb.append("- ${oldLines[i]}\n")
        }
        for (i in firstDiff..lastDiffNew) {
            sb.append("+ ${newLines[i]}\n")
        }
        val contextEndOld = minOf(oldLines.size - 1, lastDiffOld + context)
        for (i in lastDiffOld + 1..contextEndOld) {
            sb.append("  ${oldLines[i]}\n")
        }
        if (contextEndOld < oldLines.size - 1) sb.append("...\n")

        return sb.toString()
    }
}
