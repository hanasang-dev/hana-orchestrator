package com.hana.orchestrator.presentation.model.service

import kotlinx.serialization.Serializable

@Serializable
data class ServiceStatusResponse(
    val id: String,
    val name: String,
    val port: Int,
    val uptime: Long,
    val status: String
)
