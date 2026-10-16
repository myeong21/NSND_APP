package com.jsm.nsnd.network.model

data class ReportByDateResponse(
    val date: String,
    val session_count: Int,
    val safety_score: Int,
    val grade: String,
    val total_drowsy_count: Int,
    val level1_count: Int,
    val level2_count: Int,
    val level3_count: Int,
    val most_dangerous_time: String?,
    val chart_data: List<ChartData>,
    val events: List<DrowsyEvent>,
    val independent_event_count: Int? = null,
    val valid_observation_seconds: Double = 0.0,
    val unavailable_observation_seconds: Double = 0.0,
    val risk_observation_seconds: Double = 0.0,
    val observed_seconds: Double = 0.0,
    val observation_coverage: Double? = null,
    val independent_events_per_hour: Double? = null,
    val assessment_status: String? = null,
    val episode_peak_level: Int = 0
)