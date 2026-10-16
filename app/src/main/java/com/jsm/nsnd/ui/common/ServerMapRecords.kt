package com.jsm.nsnd.ui.common

import com.jsm.nsnd.network.ApiService
import com.jsm.nsnd.network.model.DrowsyEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.HttpException

object ServerMapRecords {
    fun rows(events: List<DrowsyEvent>, scope: String, sessionId: Int? = null) = events
        .mapIndexed { index, event -> MapRecord.fromServer(event, scope, index, sessionId) }
        .filter { it.stage in 1..3 }

    /** All server history uses real session endpoints; never sends date=all. */
    suspend fun load(api: ApiService, auth: String, date: String?): List<MapRecord> {
        if (date != null) return rows(api.getReportByDate(auth, date).events, "date:$date")
        val sessionIds = api.getReportHistory(auth).sessions.map { it.session_id }.toMutableSet()
        try {
            sessionIds.add(api.getCurrentSession(auth).id)
        } catch (error: HttpException) {
            if (error.code() != 404) throw error
        }
        val semaphore = Semaphore(3)
        return coroutineScope {
            sessionIds.map { sessionId -> async {
                semaphore.withPermit {
                    rows(api.getReportDetail(auth, sessionId).events, "session:$sessionId", sessionId)
                }
            } }.awaitAll().flatten().sortedByDescending { it.serverTimestamp }
        }
    }
}
