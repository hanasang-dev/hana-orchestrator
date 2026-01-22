package com.hana.orchestrator.presentation.model.chat

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val message: String
)
