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
    val events: List<DrowsyEvent>
)