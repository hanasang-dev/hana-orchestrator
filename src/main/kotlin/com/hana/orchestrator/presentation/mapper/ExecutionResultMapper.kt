package com.hana.orchestrator.presentation.mapper

import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.presentation.model.chat.ChatResponse

/**
 * Domain Entity → Presentation Response 변환
 */
object ExecutionResultMapper {
    fun toChatResponse(result: ExecutionResult): ChatResponse {
        // 현재는 result만 사용
        // 나중에 executionTree, context 정보 추가 가능
        return ChatResponse(listOf(result.result))
    }
}
