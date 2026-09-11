package com.jsm.nsnd.network.model

data class SessionStartResponse(
    val session_id: Int,
    val started_at: String
)

data class SessionEndRequest(
    val session_id: Int
)

data class CurrentSessionResponse(
    val id: Int,
    val user_id: Int,
    val started_at: String,
    val ended_at: String?
)

data class DetectionStatusResponse(
    val session_id: Int,
    val is_running: Boolean
)
