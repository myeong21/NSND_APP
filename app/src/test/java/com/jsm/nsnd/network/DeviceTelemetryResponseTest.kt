package com.jsm.nsnd.network

import com.google.gson.Gson
import com.jsm.nsnd.network.model.DeviceTelemetryResponse
import org.junit.Assert.*
import org.junit.Test

class DeviceTelemetryResponseTest {
    private val sample = DeviceTelemetryResponse(
        arduino_enabled = true, arduino_connected = true,
        co2_ppm = 850, co2_age_seconds = 2.0,
    )

    @Test fun sensorDataDoesNotRequireAnAlarmAck() {
        val response = Gson().fromJson("""{"arduino_enabled":true,"arduino_connected":true,
            "arduino_responding":false,"co2_ppm":850,"co2_age_seconds":2.0}""", DeviceTelemetryResponse::class.java)
        val reading = response.freshReading(500)!!
        assertEquals(850, reading.ppm)
        assertEquals(7500L, reading.remainingFreshMillis)
    }

    @Test fun delayedResponsesCannotReviveExpiredReadings() {
        assertNull(sample.copy(co2_age_seconds = 9.5).freshReading(500))
        assertNull(sample.copy(co2_age_seconds = 10.1).freshReading(0))
        assertNull(sample.freshReading(8000))
        assertEquals(1L, sample.freshReading(7999)!!.remainingFreshMillis)
    }

    @Test fun missingInvalidOrDisconnectedDataIsNeverPresentedAsAReading() {
        assertNull(Gson().fromJson("{}", DeviceTelemetryResponse::class.java).freshReading(0))
        assertNull(sample.copy(arduino_connected = false).freshReading(0))
        assertNull(sample.copy(arduino_enabled = false).freshReading(0))
        assertNull(sample.copy(co2_ppm = null).freshReading(0))
        for (ppm in listOf(0, 299, 10001)) assertNull(sample.copy(co2_ppm = ppm).freshReading(0))
        for (age in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(sample.copy(co2_age_seconds = age).freshReading(0))
        }
    }
}
