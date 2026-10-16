package com.jsm.nsnd.ui.common

import com.jsm.nsnd.data.local.LocalDetectionRecord
import com.jsm.nsnd.network.model.DrowsyEvent
import org.json.JSONObject

/** Presentation of live receipts, recovered history, and server report rows. */
data class MapRecord(
    val id: String, val stage: Int, val latitude: Double?, val longitude: Double?,
    val serverTimestamp: String? = null, val sessionId: Int? = null,
    val local: LocalDetectionRecord? = null
) {
    val isLocal: Boolean get() = local != null
    val hasCoordinates: Boolean get() = latitude != null && longitude != null &&
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    val ear: Double? get() = local?.ear
    val predictionScore: Double? get() = local?.predictionScore
    fun receiptText(): String = local?.receiptText() ?: ServerTime.display(serverTimestamp.orEmpty(), "yyyy.MM.dd HH:mm:ss")
    fun timeLabel(): String = if (local?.source == "recovered") "서버 기록 시각 (복구)" else if (isLocal) "앱 수신" else "서버 기록 시각"
    fun coordinatesText(): String = local?.coordinatesText() ?: if (hasCoordinates)
        "서버 저장 좌표: $latitude, $longitude" else "위치 없음 · 서버에 저장된 좌표 없음"
    fun markerJavascript(): String = "addMarker($latitude, $longitude, $stage, ${JSONObject.quote(timeLabel() + " " + receiptText())}, '', ${JSONObject.quote(id)});"
    companion object {
        fun fromLocal(record: LocalDetectionRecord) = MapRecord(record.id, record.stage,
            record.latitude, record.longitude, sessionId = record.sessionId, local = record)
        /** New events have stable IDs; legacy rows retain response-scoped identities. */
        fun fromServer(event: DrowsyEvent, responseScope: String, index: Int, sessionId: Int? = null) =
            MapRecord(event.event_id?.let { "server:$it" } ?: "server:$responseScope:$index", event.drowsy_level, event.latitude, event.longitude,
                event.timestamp, sessionId)
    }
}
