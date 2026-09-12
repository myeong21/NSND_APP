package com.jsm.nsnd.network.model

data class HealthResponse(
    val status: String,
    val models_ready: Boolean = false,
    val camera_available: Boolean = false,
    val detection_active: Boolean = false,
    val active_session_id: Int? = null
)
