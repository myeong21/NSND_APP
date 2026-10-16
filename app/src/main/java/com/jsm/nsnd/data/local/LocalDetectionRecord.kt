package com.jsm.nsnd.data.local

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalDetectionRecord(
    val id: String, val accountKey: String, val userId: Long, val sessionId: Int,
    val receivedAtMs: Long, val stage: Int, val ear: Double?, val predictionScore: Double?,
    val latitude: Double?, val longitude: Double?, val locationMeasuredAtMs: Long?,
    val accuracyMeters: Float?, val locationStatus: String,
    val serverEventId: String? = null, val serverDetectedAtMs: Long? = null,
    val syncState: String = "local_only", val syncError: String? = null,
    val source: String = "live", val recoveredAtMs: Long? = null
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
    fun receiptText(): String = SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS", Locale.KOREAN).format(Date(receivedAtMs))
    fun coordinatesText(): String = if (hasCoordinates) {
        String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
    } else "위치 없음 (${locationStatusText()})"
    private fun locationStatusText() = when (locationStatus) {
        "permission_denied" -> "권한 거부"
        "services_disabled" -> "위치 서비스 꺼짐"
        "stale" -> "오래된 위치"
        "inaccurate" -> "정확도 부족"
        "no_fix" -> "위치 미수신"
        "not_recorded" -> "서버에 위치 기록 없음"
        else -> "위치 수신 실패"
    }
    fun detailText(): String = buildString {
        append("${if (source == "recovered") "서버 기록 시각" else "앱 결과 수신"}: ${receiptText()}\n졸음 ${stage}단계\n")
        if (source == "recovered") append("서버에서 복구한 과거 기록 · 현재 위치를 연결하거나 경보/SMS를 실행하지 않았습니다.\n")
        else append("위치는 앱 수신 당시 측정값이며 서버 감지 순간과 차이가 있을 수 있습니다.\n")
        append("EAR: ${ear ?: "없음"}\n예측 점수: ${predictionScore ?: "없음"}\n")
        append("${coordinatesText()}\n")
        if (hasCoordinates && (latitude!! !in 33.0..39.0 || longitude!! !in 124.0..132.0)) {
            append("이 좌표의 카카오맵 배경 표시는 제한될 수 있습니다. 원본 좌표를 보존합니다.\n")
        }
        locationMeasuredAtMs?.let {
            append("위치 측정: ${SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREAN).format(Date(it))}\n")
        }
        accuracyMeters?.let { append("위치 정확도: ±${it.toInt()}m\n") }
        append("운전 세션 #$sessionId\n기록 ID: $id\n")
        serverDetectedAtMs?.let {
            append("서버 감지: ${SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS", Locale.KOREAN).format(Date(it))}\n")
        } ?: append("서버 감지 시각 없음\n")
        append("서버 위치 연결: ${when (syncState) {
            "synced" -> "완료"
            "pending" -> "전송 대기 · 연결 복구 시 재시도"
            "failed" -> "실패"
            "needs_review" -> "확인 필요 · 장기간 서버 기록 없음"
            "recovered" -> "서버 기록 복구 완료"
            else -> "이전 서버 기록 · 연결 ID 없음"
        }}")
        syncError?.let { append("\n$it") }
    }
    fun markerJavascript(): String =
        "addMarker($latitude, $longitude, $stage, ${JSONObject.quote(receiptText())}, '', ${JSONObject.quote(id)});"
}
