package com.jsm.nsnd.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/** Separate app-private database; never opens the repository's drowsyguard.db. */
class LocalDetectionStore private constructor(context: Context, name: String) :
    SQLiteOpenHelper(context.applicationContext ?: context, name, null, 4) {
    init { setWriteAheadLoggingEnabled(true) }
    private val retiredAccounts = mutableSetOf<String>()
    @Synchronized fun isAccountRetired(account: String): Boolean = account in retiredAccounts

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE local_events (
            id TEXT PRIMARY KEY, account_key TEXT NOT NULL, user_id INTEGER NOT NULL,
            session_id INTEGER NOT NULL, received_at_ms INTEGER NOT NULL,
            stage INTEGER NOT NULL CHECK(stage BETWEEN 1 AND 3), ear REAL, prediction_score REAL,
            latitude REAL, longitude REAL, location_measured_at_ms INTEGER, accuracy_meters REAL,
            location_status TEXT NOT NULL,
            CHECK((latitude IS NULL) = (longitude IS NULL)))""")
        db.execSQL("CREATE INDEX local_events_account_time ON local_events(account_key, received_at_ms)")
        db.execSQL("""CREATE TABLE transition_state (
            account_key TEXT NOT NULL, session_id INTEGER NOT NULL, stage INTEGER NOT NULL,
            PRIMARY KEY(account_key, session_id))""")
        addSyncTables(db)
        SmsHistoryStore.createTables(db)
        addRecoveryTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addSyncTables(db)
        if (oldVersion < 3) SmsHistoryStore.createTables(db)
        if (oldVersion < 4) addRecoveryTables(db)
    }

    private fun addRecoveryTables(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE local_events ADD COLUMN record_source TEXT NOT NULL DEFAULT 'live'")
        db.execSQL("ALTER TABLE local_events ADD COLUMN recovered_at_ms INTEGER")
        for (table in listOf("local_events", "session_distances")) {
            db.execSQL("ALTER TABLE $table ADD COLUMN sync_attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $table ADD COLUMN next_attempt_at INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL("ALTER TABLE session_distances ADD COLUMN sync_state TEXT NOT NULL DEFAULT 'pending'")
        db.execSQL("ALTER TABLE session_distances ADD COLUMN sync_error TEXT")
        db.execSQL("CREATE INDEX local_events_pending ON local_events(account_key,sync_state,next_attempt_at)")
        db.execSQL("""CREATE TABLE recovery_state(account_key TEXT PRIMARY KEY, cursor INTEGER NOT NULL DEFAULT 0,
            last_recovered_at INTEGER, last_error TEXT)""")
    }

    private fun addSyncTables(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE local_events ADD COLUMN server_event_id TEXT")
        db.execSQL("ALTER TABLE local_events ADD COLUMN server_detected_at_ms INTEGER")
        db.execSQL("ALTER TABLE local_events ADD COLUMN sync_state TEXT NOT NULL DEFAULT 'local_only'")
        db.execSQL("ALTER TABLE local_events ADD COLUMN sync_error TEXT")
        db.execSQL("CREATE UNIQUE INDEX local_events_server_id ON local_events(account_key, server_event_id)")
        db.execSQL("""CREATE TABLE session_distances (
            account_key TEXT NOT NULL, session_id INTEGER NOT NULL,
            meters REAL NOT NULL DEFAULT 0, uploaded_meters REAL NOT NULL DEFAULT 0,
            PRIMARY KEY(account_key, session_id))""")
    }

    /** Checkpoint and event insertion are atomic, including the normal-stage reset. */
    @Synchronized
    fun recordTransition(account: String, userId: Long, sessionId: Int, sample: DetectionSample,
                         receivedAtMs: Long, location: LocationSnapshot): String? {
        require(userId > 0 && sessionId > 0 && account.isNotBlank() && sample.stage in 0..3)
        if (account in retiredAccounts) return null
        val db = writableDatabase
        var inserted: String? = null
        db.beginTransaction()
        try {
            val previous = db.rawQuery(
                "SELECT stage FROM transition_state WHERE account_key=? AND session_id=?",
                arrayOf(account, sessionId.toString())
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val knownServerEvent = sample.serverEventId?.let { eventId ->
                db.rawQuery("SELECT record_source FROM local_events WHERE account_key=? AND server_event_id=?",
                    arrayOf(account, eventId)).use { if (it.moveToFirst()) it.getString(0) else null }
            }
            val shouldSave = if (sample.serverEventId != null) sample.stage in 1..3 && (knownServerEvent == null || knownServerEvent == "recovered")
                else DetectionPolicy.shouldSave(previous, sample.stage)
            if (shouldSave) {
                inserted = UUID.randomUUID().toString()
                val values = ContentValues().apply {
                    put("id", inserted); put("account_key", account); put("user_id", userId)
                    put("session_id", sessionId); put("received_at_ms", receivedAtMs); put("stage", sample.stage)
                    put("ear", sample.ear); put("prediction_score", sample.predictionScore)
                    put("latitude", location.latitude); put("longitude", location.longitude)
                    put("location_measured_at_ms", location.measuredAtMs); put("accuracy_meters", location.accuracyMeters)
                    put("location_status", location.status)
                    put("server_event_id", sample.serverEventId)
                    put("server_detected_at_ms", sample.serverDetectedAtMs)
                    put("sync_state", if (sample.serverEventId == null) "local_only" else "pending")
                }
                if (knownServerEvent == "recovered") {
                    // A cursor fetch can beat the first live frame. Preserve one row, but
                    // attach the real receipt snapshot only when that live frame arrives.
                    values.remove("id")
                    values.put("record_source", "live")
                    values.put("sync_attempts", 0); values.put("next_attempt_at", 0)
                    values.putNull("sync_error")
                    db.update("local_events", values, "account_key=? AND server_event_id=?",
                        arrayOf(account, sample.serverEventId!!))
                    inserted = db.rawQuery("SELECT id FROM local_events WHERE account_key=? AND server_event_id=?",
                        arrayOf(account, sample.serverEventId!!)).use { it.moveToFirst(); it.getString(0) }
                } else db.insertOrThrow("local_events", null, values)
            }
            if (previous != sample.stage) {
                db.insertWithOnConflict("transition_state", null, ContentValues().apply {
                    put("account_key", account); put("session_id", sessionId); put("stage", sample.stage)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (inserted != null) revision.value = revision.value + 1
        return inserted
    }

    suspend fun deleteAccount(account: String) = withContext(Dispatchers.IO) {
        deleteAccountNow(account)
    }

    @Synchronized private fun deleteAccountNow(account: String) {
        retiredAccounts.add(account) // Prevent queued service writes after withdrawal.
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("local_events", "account_key=?", arrayOf(account))
            db.delete("transition_state", "account_key=?", arrayOf(account))
            db.delete("session_distances", "account_key=?", arrayOf(account))
            db.delete("recovery_state", "account_key=?", arrayOf(account))
            db.delete("sms_parts", "message_id IN (SELECT id FROM sms_messages WHERE account_key=?)", arrayOf(account))
            db.delete("sms_messages", "account_key=?", arrayOf(account))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        revision.value = revision.value + 1
    }

    suspend fun query(account: String, fromMs: Long? = null, untilMs: Long? = null,
                      sessionId: Int? = null, pendingOnly: Boolean = false): List<LocalDetectionRecord> = withContext(Dispatchers.IO) {
        val clauses = mutableListOf("account_key=?")
        val args = mutableListOf(account)
        fromMs?.let { clauses += "received_at_ms>=?"; args += it.toString() }
        untilMs?.let { clauses += "received_at_ms<?"; args += it.toString() }
        sessionId?.let { clauses += "session_id=?"; args += it.toString() }
        if (pendingOnly) {
            clauses += "sync_state='pending' AND server_event_id IS NOT NULL AND next_attempt_at<=?"
            args += System.currentTimeMillis().toString()
        }
        readableDatabase.query("local_events", null, clauses.joinToString(" AND "), args.toTypedArray(),
            null, null, if (pendingOnly) "next_attempt_at ASC, received_at_ms ASC" else "received_at_ms DESC, id DESC",
            if (pendingOnly) "100" else null).use { c ->
            fun text(n: String) = c.getString(c.getColumnIndexOrThrow(n))
            fun long(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
            fun double(n: String): Double? = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getDouble(it) }
            fun nullableLong(n: String): Long? = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getLong(it) }
            buildList {
                while (c.moveToNext()) add(LocalDetectionRecord(text("id"), text("account_key"), long("user_id"),
                    long("session_id").toInt(), long("received_at_ms"), long("stage").toInt(), double("ear"),
                    double("prediction_score"), double("latitude"), double("longitude"), nullableLong("location_measured_at_ms"),
                    double("accuracy_meters")?.toFloat(), text("location_status"),
                    c.getString(c.getColumnIndexOrThrow("server_event_id")), nullableLong("server_detected_at_ms"),
                    text("sync_state"), c.getString(c.getColumnIndexOrThrow("sync_error")),
                    text("record_source"), nullableLong("recovered_at_ms")))
            }
        }
    }

    suspend fun pendingLocations(account: String): List<LocalDetectionRecord> =
        query(account, pendingOnly = true)

    @Synchronized fun hasPendingSync(account: String): Boolean = readableDatabase.rawQuery(
        """SELECT 1 WHERE EXISTS(SELECT 1 FROM local_events WHERE account_key=? AND sync_state='pending')
            OR EXISTS(SELECT 1 FROM session_distances WHERE account_key=? AND meters>uploaded_meters AND sync_state='pending')""",
        arrayOf(account,account)).use { it.moveToFirst() }

    @Synchronized fun markLocationSync(account: String, id: String, state: String, error: String? = null) {
        writableDatabase.update("local_events", ContentValues().apply {
            put("sync_state", state); put("sync_error", error)
            if (state == "synced") { put("sync_attempts", 0); put("next_attempt_at", 0) }
        }, "account_key=? AND id=?", arrayOf(account, id))
        revision.value += 1
    }

    data class SessionDistance(val sessionId: Int, val meters: Double)

    @Synchronized fun distanceMeters(account: String, sessionId: Int): Double =
        readableDatabase.rawQuery("SELECT meters FROM session_distances WHERE account_key=? AND session_id=?",
            arrayOf(account, sessionId.toString())).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }

    @Synchronized fun saveDistance(account: String, sessionId: Int, meters: Double) {
        if (account in retiredAccounts || sessionId <= 0 || !meters.isFinite() || meters < 0) return
        writableDatabase.execSQL("INSERT OR IGNORE INTO session_distances(account_key,session_id) VALUES (?,?)",
            arrayOf(account, sessionId))
        writableDatabase.execSQL("UPDATE session_distances SET meters=MAX(meters,?) WHERE account_key=? AND session_id=?",
            arrayOf(meters, account, sessionId))
    }

    @Synchronized fun pendingDistances(account: String): List<SessionDistance> =
        readableDatabase.rawQuery("""SELECT session_id,meters FROM session_distances WHERE account_key=? AND meters>uploaded_meters
            AND sync_state='pending' AND next_attempt_at<=? ORDER BY next_attempt_at LIMIT 100""",
            arrayOf(account,System.currentTimeMillis().toString())).use { c -> buildList { while (c.moveToNext()) add(SessionDistance(c.getInt(0),c.getDouble(1))) } }

    @Synchronized fun markDistanceSynced(account: String, sessionId: Int, meters: Double) {
        writableDatabase.execSQL("""UPDATE session_distances SET uploaded_meters=MAX(uploaded_meters,?),meters=MAX(meters,?),
            sync_state='pending',sync_attempts=0,next_attempt_at=0,sync_error=NULL WHERE account_key=? AND session_id=?""",
            arrayOf(meters,meters,account,sessionId))
    }

    @Synchronized fun syncFailure(account: String, id: String, distance: Boolean, code: Int?) {
        val table = if (distance) "session_distances" else "local_events"
        val key = if (distance) "session_id" else "id"
        val args = arrayOf(account,id)
        val attempt = readableDatabase.rawQuery("SELECT sync_attempts FROM $table WHERE account_key=? AND $key=?", args)
            .use { if (it.moveToFirst()) it.getInt(0) + 1 else return }
        val failure = SyncRetryPolicy.failure(attempt,code,System.currentTimeMillis())
        writableDatabase.update(table,ContentValues().apply {
            put("sync_attempts",attempt); put("next_attempt_at",failure.nextAttemptAt)
            put("sync_state",failure.state); put("sync_error",failure.message)
        },"account_key=? AND $key=?",args)
        revision.value += 1
    }

    @Synchronized fun recoveryCursor(account: String): Long = readableDatabase.rawQuery(
        "SELECT cursor FROM recovery_state WHERE account_key=?",arrayOf(account)).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    /** Recovered events are history only. No transition checkpoint, GPS snapshot or alarm is touched. */
    @Synchronized fun importRecoveryPage(account: String, userId: Long, expectedCursor: Long,
        page: com.jsm.nsnd.network.model.RecoveryPage): Boolean {
        if (account in retiredAccounts || recoveryCursor(account) != expectedCursor) return false
        require(page.next_cursor == (page.events.lastOrNull()?.cursor ?: expectedCursor))
        require(!page.has_more || page.events.isNotEmpty())
        require(page.events.zipWithNext().all { (a,b) -> a.cursor < b.cursor })
        val db = writableDatabase
        db.beginTransaction()
        try {
            fun millis(value: String?): Long? = value?.let { java.time.Instant.parse(it).toEpochMilli() }
            for (event in page.events) {
                require(event.cursor > expectedCursor && event.cursor <= page.next_cursor && event.stage in 1..3)
                val detected = millis(event.detected_at)!!
                db.insertWithOnConflict("local_events",null,ContentValues().apply {
                    put("id",UUID.randomUUID().toString()); put("account_key",account); put("user_id",userId)
                    put("session_id",event.session_id); put("received_at_ms",detected); put("stage",event.stage)
                    put("ear",event.ear); put("prediction_score",event.prediction_score)
                    put("latitude",event.latitude); put("longitude",event.longitude)
                    put("location_measured_at_ms",millis(event.location_measured_at)); put("accuracy_meters",event.accuracy_meters)
                    put("location_status",event.location_status ?: "not_recorded"); put("server_event_id",event.event_id)
                    put("server_detected_at_ms",detected); put("sync_state","recovered")
                    put("record_source","recovered"); put("recovered_at_ms",System.currentTimeMillis())
                },SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.execSQL("INSERT OR IGNORE INTO recovery_state(account_key) VALUES (?)",arrayOf(account))
            db.update("recovery_state",ContentValues().apply {
                put("cursor",page.next_cursor); put("last_recovered_at",System.currentTimeMillis()); putNull("last_error")
            },"account_key=?",arrayOf(account))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        revision.value += 1
        return true
    }

    @Synchronized fun recoveryFailure(account: String, message: String) {
        if (account in retiredAccounts) return
        writableDatabase.execSQL("INSERT OR IGNORE INTO recovery_state(account_key) VALUES (?)",arrayOf(account))
        writableDatabase.update("recovery_state",ContentValues().apply { put("last_error",message) },"account_key=?",arrayOf(account))
    }

    @Synchronized fun retryFailedSync(account: String) {
        for (table in listOf("local_events","session_distances")) writableDatabase.execSQL(
            "UPDATE $table SET sync_state='pending',sync_attempts=0,next_attempt_at=0,sync_error=NULL WHERE account_key=? AND sync_state IN ('failed','needs_review')",
            arrayOf(account))
        revision.value += 1
    }

    @Synchronized fun syncSummary(account: String): String {
        val lines = mutableListOf<String>()
        for ((table,label) in listOf("local_events" to "위치", "session_distances" to "거리")) {
            val condition = if (table == "session_distances") " AND meters>uploaded_meters" else ""
            readableDatabase.rawQuery("SELECT sync_state,COUNT(*) FROM $table WHERE account_key=?$condition GROUP BY sync_state",arrayOf(account)).use { c ->
                while (c.moveToNext()) if (c.getString(0) in setOf("pending","failed","needs_review")) {
                    val status = when(c.getString(0)) { "pending" -> "대기"; "failed" -> "실패"; else -> "확인 필요" }
                    lines += "$label $status ${c.getInt(1)}건"
                }
            }
        }
        readableDatabase.rawQuery("SELECT last_recovered_at,last_error FROM recovery_state WHERE account_key=?",arrayOf(account)).use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) lines += "마지막 서버 조회: " + java.text.SimpleDateFormat("MM.dd HH:mm:ss",java.util.Locale.KOREAN).format(java.util.Date(c.getLong(0)))
                if (!c.isNull(1)) lines += c.getString(1)
            }
        }
        return lines.joinToString("\n").ifBlank { "서버 기록을 조회할 준비가 되었습니다." }
    }

    companion object {
        const val DATABASE_NAME = "nsnd_local_detection.db"
        private val revision = MutableStateFlow(0L)
        val changes = revision.asStateFlow()
        @Volatile private var instance: LocalDetectionStore? = null
        fun get(context: Context): LocalDetectionStore = instance ?: synchronized(this) {
            instance ?: LocalDetectionStore(context, DATABASE_NAME).also { instance = it }
        }
        /** Instrumentation tests use an isolated database name. */
        fun isolated(context: Context, name: String) = LocalDetectionStore(context, name)
    }
}
