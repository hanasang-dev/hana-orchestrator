package com.hana.orchestrator.presentation.mapper

import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.orchestrator.ReActTreeConverter
import com.hana.orchestrator.presentation.model.chat.ChatResponse

/**
 * Domain Entity → Presentation Response 변환
 */
object ExecutionResultMapper {
    fun toChatResponse(result: ExecutionResult): ChatResponse {
        val tree = result.stepHistory.takeIf { it.isNotEmpty() }
            ?.let { ReActTreeConverter.convert(it) }
        return ChatResponse(results = listOf(result.result), tree = tree)
    }
}
