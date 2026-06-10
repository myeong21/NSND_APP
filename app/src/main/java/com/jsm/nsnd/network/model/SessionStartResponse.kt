package com.jsm.nsnd.network.model

data class SessionStartResponse(
    val session_id: Int,
    val started_at: String
)

data class SessionEndRequest(
    val session_id: Int
)