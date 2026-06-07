package com.jsm.nsnd.network.model

data class DrowsyEvent(
    val timestamp: String,
    val drowsy_level: Int,
    val latitude: Double?,
    val longitude: Double?
)