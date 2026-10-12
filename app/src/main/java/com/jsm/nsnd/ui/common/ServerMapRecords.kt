package com.jsm.nsnd.ui.common

import com.jsm.nsnd.network.ApiService
import com.jsm.nsnd.network.model.DrowsyEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object ServerMapRecords {
    fun rows(events: List<DrowsyEvent>, scope: String, sessionId: Int? = null) = events
        .mapIndexed { index, event -> MapRecord.fromServer(event, scope, index, sessionId) }
        .filter { it.stage in 1..3 }

    /** All server history uses real session endpoints; never sends date=all. */
    suspend fun load(api: ApiService, auth: String, date: String?): List<MapRecord> {
        if (date != null) return rows(api.getReportByDate(auth, date).events, "date:$date")
        val sessions = api.getReportHistory(auth).sessions.distinctBy { it.session_id }
        val semaphore = Semaphore(3)
        return coroutineScope {
            sessions.map { session -> async {
                semaphore.withPermit {
                    rows(api.getReportDetail(auth, session.session_id).events, "session:${session.session_id}", session.session_id)
                }
            } }.awaitAll().flatten().sortedByDescending { it.serverTimestamp }
        }
    }
}
