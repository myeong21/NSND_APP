package com.jsm.nsnd.network.model

data class ChartData(
    val hour: Int,
    val count: Int,     // 전체 감지 횟수
    val level1: Int,    // 1단계 감지 횟수
    val level2: Int,    // 2단계 감지 횟수
    val level3: Int,    // 3단계 감지 횟수
    val max_level: Int  // 해당 시간대 최고 졸음 단계
)

data class ReportSummaryResponse(
    val session_id: Int,
    val started_at: String,
    val ended_at: String?,
    val duration_minutes: Int?,
    val safety_score: Int,
    val grade: String,
    val total_drowsy_count: Int,
    val level1_count: Int,
    val level2_count: Int,
    val level3_count: Int,
    val most_dangerous_time: String?,
    val chart_data: List<ChartData>,
    val events: List<DrowsyEvent>,
    val distance_meters: Double = 0.0,
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

data class ReportHistoryItem(
    val session_id: Int,
    val started_at: String,
    val ended_at: String?,
    val duration_minutes: Int?,
    val safety_score: Int,
    val grade: String,
    val total_drowsy_count: Int,
    val distance_meters: Double = 0.0,
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

data class ReportHistoryResponse(
    val total_sessions: Int,
    val sessions: List<ReportHistoryItem>
)
