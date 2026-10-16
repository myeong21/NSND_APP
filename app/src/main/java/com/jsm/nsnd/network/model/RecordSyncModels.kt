package com.jsm.nsnd.network.model

import com.jsm.nsnd.data.api.ContactDto

data class RecoveredEvent(
    val cursor: Long, val event_id: String, val session_id: Int, val detected_at: String,
    val stage: Int, val ear: Double?, val prediction_score: Double?,
    val latitude: Double?, val longitude: Double?, val app_received_at: String?,
    val location_measured_at: String?, val accuracy_meters: Float?, val location_status: String?,
)
data class RecoveryPage(val next_cursor: Long, val has_more: Boolean, val events: List<RecoveredEvent>)
data class DistanceSaved(val distance_meters: Double)
data class SmsSettings(val enabled: Boolean, val contact_count: Int, val contacts: List<ContactDto>)
data class SmsSettingsUpdate(val enabled: Boolean)
