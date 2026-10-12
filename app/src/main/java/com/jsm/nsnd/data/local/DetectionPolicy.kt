package com.jsm.nsnd.data.local

/** Wall clock is a receipt time, never a server detection timestamp. */
data class DetectionSample(val stage: Int, val ear: Double?, val predictionScore: Double?)

data class LocationSnapshot(
    val latitude: Double?, val longitude: Double?, val measuredAtMs: Long?,
    val accuracyMeters: Float?, val status: String
)

object DetectionPolicy {
    const val MAX_LOCATION_AGE_MS = 30_000L
    const val MAX_ACCURACY_METERS = 100f

    fun shouldSave(previousStage: Int, stage: Int): Boolean = stage in 1..3 && previousStage != stage

    fun usableFix(lat: Double, lng: Double, accuracy: Float?, ageMs: Long): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 &&
            accuracy != null && accuracy.isFinite() && accuracy in 0f..MAX_ACCURACY_METERS &&
            ageMs in 0..MAX_LOCATION_AGE_MS
}
