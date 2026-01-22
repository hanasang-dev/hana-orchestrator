package com.hana.orchestrator.presentation.model.layer

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRemoteLayerResponse(
    val success: Boolean,
    val message: String,
    val layerName: String,
    val baseUrl: String
)
