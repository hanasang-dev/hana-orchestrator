package com.hana.orchestrator.data.model.response

import kotlinx.serialization.Serializable

@Serializable
data class LayerSelectionResponse(
    val selectedLayers: List<String>,
    val reasoning: String,
    val executionPlan: String
)
