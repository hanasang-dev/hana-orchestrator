package com.hana.orchestrator.presentation.mapper

import com.hana.orchestrator.presentation.model.chat.ChatRequest
import com.hana.orchestrator.domain.dto.ChatDto

/**
 * Presentation Request → Domain Dto 변환
 */
object ChatRequestMapper {
    fun toDto(request: ChatRequest): ChatDto {
        return ChatDto(message = request.message, context = request.context)
    }
}
