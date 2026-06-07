package com.jsm.nsnd.network

import com.jsm.nsnd.network.model.ReportHistoryResponse
import com.jsm.nsnd.network.model.ReportSummaryResponse
import retrofit2.http.GET
import retrofit2.http.Header

interface ApiService {

    @GET("report/summary")
    suspend fun getReportSummary(
        @Header("Authorization") token: String
    ): ReportSummaryResponse

    @GET("report/history")
    suspend fun getReportHistory(
        @Header("Authorization") token: String
    ): ReportHistoryResponse
}