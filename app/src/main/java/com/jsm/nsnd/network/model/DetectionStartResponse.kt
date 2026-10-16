package com.jsm.nsnd.network.model

data class DetectionStartResponse(
    val session_id: Int,
    val status: String,
    val message: String,
    val calibration_required_seconds: Double? = null,
    val model_release_id: String? = null,
    val detection_code_version: String? = null
)

data class DetectionRequest(
    val session_id: Int,
    val risk_id: String? = null
)
