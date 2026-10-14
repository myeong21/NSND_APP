package com.jsm.nsnd.network.model

data class HealthResponse(
    val status: String,
    val models_ready: Boolean = false,
    val camera_available: Boolean = false,
    val detection_active: Boolean = false,
    val active_session_id: Int? = null,
    val model_release_id: String? = null,
    val model_normalization: String? = null,
    val model_threshold: Double? = null,
    val detection_code_version: String? = null
)
