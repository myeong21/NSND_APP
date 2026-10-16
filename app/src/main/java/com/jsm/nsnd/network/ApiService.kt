package com.jsm.nsnd.network

import com.jsm.nsnd.network.model.DetectionRequest
import com.jsm.nsnd.network.model.DetectionStartResponse
import com.jsm.nsnd.network.model.HealthResponse
import com.jsm.nsnd.network.model.ReportByDateResponse
import com.jsm.nsnd.network.model.ReportHistoryResponse
import com.jsm.nsnd.network.model.ReportSummaryResponse
import com.jsm.nsnd.network.model.SessionEndRequest
import com.jsm.nsnd.network.model.SessionStartResponse
import com.jsm.nsnd.network.model.CurrentSessionResponse
import com.jsm.nsnd.network.model.DetectionStatusResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

interface ApiService {

    @GET("detection/events")
    suspend fun recoverEvents(@Header("Authorization") token: String, @Query("after") after: Long,
        @Query("limit") limit: Int = 100): com.jsm.nsnd.network.model.RecoveryPage

    @GET("contacts/sms-settings")
    suspend fun getSmsSettings(@Header("Authorization") token: String): com.jsm.nsnd.network.model.SmsSettings

    @PUT("contacts/sms-settings")
    suspend fun updateSmsSettings(@Header("Authorization") token: String,
        @Body body: com.jsm.nsnd.network.model.SmsSettingsUpdate): com.jsm.nsnd.network.model.SmsSettings

    @PUT("detection/events/{eventId}/location")
    suspend fun updateEventLocation(
        @Header("Authorization") token: String, @Path("eventId") eventId: String,
        @Body body: com.jsm.nsnd.service.EventLocationRequest,
    )

    @PUT("sessions/{sessionId}/distance")
    suspend fun updateSessionDistance(
        @Header("Authorization") token: String, @Path("sessionId") sessionId: Int,
        @Body body: com.jsm.nsnd.service.SessionDistanceRequest,
    ): com.jsm.nsnd.network.model.DistanceSaved

    @GET("health")
    suspend fun healthCheck(): HealthResponse

    @GET("report/summary")
    suspend fun getReportSummary(
        @Header("Authorization") token: String,
        @Query("utc_offset_minutes") utcOffsetMinutes: Int = java.time.ZonedDateTime.now().offset.totalSeconds / 60
    ): ReportSummaryResponse

    @GET("report/by-date")
    suspend fun getReportByDate(
        @Header("Authorization") token: String,
        @Query("date") date: String,
        @Query("utc_offset_minutes") utcOffsetMinutes: Int = com.jsm.nsnd.ui.common.ServerTime.utcOffsetMinutes(date)
    ): ReportByDateResponse

    @GET("report/history")
    suspend fun getReportHistory(
        @Header("Authorization") token: String
    ): ReportHistoryResponse

    @POST("sessions/start")
    suspend fun startSession(
        @Header("Authorization") token: String
    ): SessionStartResponse

    @GET("sessions/current")
    suspend fun getCurrentSession(
        @Header("Authorization") token: String
    ): CurrentSessionResponse

    @GET("detection/status/{sessionId}")
    suspend fun getDetectionStatus(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: Int
    ): DetectionStatusResponse

    @GET("report/detail/{sessionId}")
    suspend fun getReportDetail(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: Int,
        @Query("utc_offset_minutes") utcOffsetMinutes: Int = java.time.ZonedDateTime.now().offset.totalSeconds / 60
    ): ReportSummaryResponse

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

    @POST("detection/alarm/acknowledge")
    suspend fun acknowledgeAlarm(
        @Header("Authorization") token: String,
        @Body body: DetectionRequest
    ): AlarmAcknowledgementResponse
}

data class AlarmAcknowledgementResponse(val session_id: Int, val status: String, val message: String)
