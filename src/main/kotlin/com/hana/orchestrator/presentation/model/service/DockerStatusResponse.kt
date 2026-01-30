package com.hana.orchestrator.presentation.model.service

import kotlinx.serialization.Serializable

@Serializable
data class DockerStatusResponse(
    val dockerAvailable: Boolean,
    val services: Map<String, Boolean>,
    val allRunning: Boolean
)

@Serializable
data class OllamaStatusResponse(
    val ollamaAvailable: Boolean,
    val models: List<String>,
    val baseUrl: String
)
