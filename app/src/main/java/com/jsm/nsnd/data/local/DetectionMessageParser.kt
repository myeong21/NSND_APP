package com.jsm.nsnd.data.local

import org.json.JSONObject

object DetectionMessageParser {
    fun parse(text: String): DetectionSample? = runCatching {
        val json = JSONObject(text)
        if (json.optBoolean("ping") || json.has("error")) return null
        val rawStage = json.opt("drowsy_level") as? Number ?: return null
        val stage = rawStage.toInt()
        if (rawStage.toDouble() != stage.toDouble() || stage !in 0..3) return null
        fun number(key: String): Double? = (json.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
        val state = json.optString("state").takeIf { it.isNotBlank() }
        val valid = json.optBoolean("observation_valid", state !in setOf("NO_FACE", "INVALID_EAR", "CALIBRATING"))
        fun optionalBoolean(key: String): Boolean? = (json.opt(key) as? Boolean)
        DetectionSample(stage, number("ear_value"), number("pred_score"), state,
            json.optString("message").takeIf { it.isNotBlank() }, valid,
            json.optBoolean("alert_active", valid && stage > 0),
            number("calibration_progress")?.coerceIn(0.0, 1.0),
            json.optBoolean("recovery_pending"), number("recovery_seconds"),
            json.optBoolean("risk_latched"), json.optBoolean("new_drowsiness_event"),
            json.optBoolean("emergency_condition"), optionalBoolean("alarm_acknowledged"),
            optionalBoolean("alarm_ack_required"), (json.opt("recent_event_count") as? Number)?.toInt(),
            json.optString("event_id").takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) },
            runCatching { java.time.Instant.parse(json.optString("detected_at")).toEpochMilli() }.getOrNull(),
            json.optString("episode_id").takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) },
            json.optString("risk_id").takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) },
            json.optString("storage_error").takeIf { it.isNotBlank() && it != "null" })
    }.getOrNull()
}
