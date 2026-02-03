package com.hana.orchestrator.presentation.model.chat

import kotlinx.serialization.Serializable

@Serializable
data class ChatResponse(
    val results: List<String>
)
