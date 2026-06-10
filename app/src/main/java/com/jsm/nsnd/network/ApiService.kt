package com.jsm.nsnd.network

import com.jsm.nsnd.network.model.DetectionRequest
import com.jsm.nsnd.network.model.DetectionStartResponse
import com.jsm.nsnd.network.model.ReportHistoryResponse
import com.jsm.nsnd.network.model.ReportSummaryResponse
import com.jsm.nsnd.network.model.SessionEndRequest
import com.jsm.nsnd.network.model.SessionStartResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface ApiService {

    @GET("report/summary")
    suspend fun getReportSummary(
        @Header("Authorization") token: String
    ): ReportSummaryResponse

    @GET("report/history")
    suspend fun getReportHistory(
        @Header("Authorization") token: String
    ): ReportHistoryResponse

    @POST("sessions/start")
    suspend fun startSession(
        @Header("Authorization") token: String
    ): SessionStartResponse

    @POST("sessions/end")
    suspend fun endSession(
        @Header("Authorization") token: String,
        @Body body: SessionEndRequest
    )

    @POST("detection/start")
    suspend fun startDetection(
        @Header("Authorization") token: String,
        @Body body: DetectionRequest
    ): DetectionStartResponse

    @POST("detection/stop")
    suspend fun stopDetection(
        @Header("Authorization") token: String,
        @Body body: DetectionRequest
    )
}