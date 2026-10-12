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
        DetectionSample(stage, number("ear_value"), number("pred_score"))
    }.getOrNull()
}
