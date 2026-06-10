package com.jsm.nsnd.network.model

data class DetectionStartResponse(
    val session_id: Int,
    val status: String,
    val message: String
)

data class DetectionRequest(
    val session_id: Int
)