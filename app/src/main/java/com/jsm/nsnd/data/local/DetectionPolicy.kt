package com.jsm.nsnd.data.local

/** Wall clock is a receipt time, never a server detection timestamp. */
data class DetectionSample(
    val stage: Int, val ear: Double?, val predictionScore: Double?,
    val state: String? = null, val message: String? = null,
    val observationValid: Boolean = true, val alertActive: Boolean = stage > 0,
    val calibrationProgress: Double? = null, val recoveryPending: Boolean = false,
    val recoverySeconds: Double? = null, val riskLatched: Boolean = false,
    val newDrowsinessEvent: Boolean = false, val emergencyCondition: Boolean = false,
    val alarmAcknowledged: Boolean? = null, val alarmAckRequired: Boolean? = null,
    val recentEventCount: Int? = null
) {
    fun statusText(): String = when (state) {
        "CALIBRATING" -> "눈을 뜨고 정면을 보세요 · 개인 보정 ${((calibrationProgress ?: 0.0) * 100).toInt()}%"
        "WARMING_UP" -> "모델 입력 준비 중 · 잠시 기다려주세요"
        "DROWSY_PENDING" -> "눈 상태 확인 중"
        "NO_FACE" -> "얼굴을 찾지 못했습니다 · 카메라를 확인하세요"
        "INVALID_EAR" -> "눈 상태를 측정할 수 없습니다 · 정면을 보세요"
        else -> when {
            recoveryPending -> "${stage}단계 · 정상 회복 확인 중 (${(recoverySeconds ?: 0.0).toInt()}/10초)"
            alarmAckRequired == true -> "${stage}단계 · 경보 중지 후 정상 회복 확인이 필요합니다"
            riskLatched -> "${stage}단계 · 위험 발생 기록 유지"
            stage == 0 -> "정상 상태"
            else -> "${stage}단계 졸음 감지"
        }
    }
}

data class LocationSnapshot(
    val latitude: Double?, val longitude: Double?, val measuredAtMs: Long?,
    val accuracyMeters: Float?, val status: String
)

object DetectionPolicy {
    const val MAX_LOCATION_AGE_MS = 30_000L
    const val MAX_ACCURACY_METERS = 100f

    /** A generous 100m/s bound rejects GPS jumps while retaining ordinary road travel. */
    fun usableDistance(distanceMeters: Float, elapsedMs: Long, previousAccuracy: Float?, accuracy: Float?): Boolean =
        distanceMeters.isFinite() && distanceMeters > 0.5f && elapsedMs in 1..MAX_LOCATION_AGE_MS &&
            previousAccuracy != null && previousAccuracy.isFinite() && previousAccuracy in 0f..40f &&
            accuracy != null && accuracy.isFinite() && accuracy in 0f..40f &&
            distanceMeters <= elapsedMs / 1000f * 100f

    fun shouldSave(previousStage: Int, stage: Int): Boolean = stage in 1..3 && previousStage != stage

    fun usableFix(lat: Double, lng: Double, accuracy: Float?, ageMs: Long): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 &&
            accuracy != null && accuracy.isFinite() && accuracy in 0f..MAX_ACCURACY_METERS &&
            ageMs in 0..MAX_LOCATION_AGE_MS
}
