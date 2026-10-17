package com.jsm.nsnd.network.model

data class DeviceTelemetryResponse(
    val arduino_enabled: Boolean = false,
    val arduino_connected: Boolean = false,
    val arduino_responding: Boolean = false,
    val co2_ppm: Int? = null,
    val co2_age_seconds: Double? = null,
) {
    /** Include request transit time so a delayed response cannot revive an old reading. */
    fun freshReading(requestElapsedMs: Long): Co2Reading? {
        val ppm = co2_ppm ?: return null
        val age = co2_age_seconds ?: return null
        if (!arduino_enabled || !arduino_connected || ppm !in 300..10000 ||
            !age.isFinite() || age < 0 || age > 10 || requestElapsedMs < 0) return null
        val remaining = ((10 - age) * 1000).toLong() - requestElapsedMs
        return if (remaining > 0) Co2Reading(ppm, remaining) else null
    }
}

data class Co2Reading(val ppm: Int, val remainingFreshMillis: Long)
