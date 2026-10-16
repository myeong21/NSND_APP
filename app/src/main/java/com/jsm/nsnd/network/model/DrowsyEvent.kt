package com.jsm.nsnd.network.model

data class DrowsyEvent(
    val timestamp: String,
    val drowsy_level: Int,
    val latitude: Double?,
    val longitude: Double?,
    val event_id: String? = null,
    val app_received_at: String? = null,
    val location_measured_at: String? = null,
    val location_accuracy_meters: Float? = null,
    val location_status: String? = null
)
