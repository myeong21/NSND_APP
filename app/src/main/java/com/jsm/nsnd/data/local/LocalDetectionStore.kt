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
    SQLiteOpenHelper(context.applicationContext ?: context, name, null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    private val retiredAccounts = mutableSetOf<String>()

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("A non-destructive migration is required for version $newVersion")
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
            if (DetectionPolicy.shouldSave(previous, sample.stage)) {
                inserted = UUID.randomUUID().toString()
                val values = ContentValues().apply {
                    put("id", inserted); put("account_key", account); put("user_id", userId)
                    put("session_id", sessionId); put("received_at_ms", receivedAtMs); put("stage", sample.stage)
                    put("ear", sample.ear); put("prediction_score", sample.predictionScore)
                    put("latitude", location.latitude); put("longitude", location.longitude)
                    put("location_measured_at_ms", location.measuredAtMs); put("accuracy_meters", location.accuracyMeters)
                    put("location_status", location.status)
                }
                db.insertOrThrow("local_events", null, values)
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
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        revision.value = revision.value + 1
    }

    suspend fun query(account: String, fromMs: Long? = null, untilMs: Long? = null,
                      sessionId: Int? = null): List<LocalDetectionRecord> = withContext(Dispatchers.IO) {
        val clauses = mutableListOf("account_key=?")
        val args = mutableListOf(account)
        fromMs?.let { clauses += "received_at_ms>=?"; args += it.toString() }
        untilMs?.let { clauses += "received_at_ms<?"; args += it.toString() }
        sessionId?.let { clauses += "session_id=?"; args += it.toString() }
        readableDatabase.query("local_events", null, clauses.joinToString(" AND "), args.toTypedArray(),
            null, null, "received_at_ms DESC, id DESC").use { c ->
            fun text(n: String) = c.getString(c.getColumnIndexOrThrow(n))
            fun long(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
            fun double(n: String): Double? = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getDouble(it) }
            fun nullableLong(n: String): Long? = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getLong(it) }
            buildList {
                while (c.moveToNext()) add(LocalDetectionRecord(text("id"), text("account_key"), long("user_id"),
                    long("session_id").toInt(), long("received_at_ms"), long("stage").toInt(), double("ear"),
                    double("prediction_score"), double("latitude"), double("longitude"), nullableLong("location_measured_at_ms"),
                    double("accuracy_meters")?.toFloat(), text("location_status")))
            }
        }
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
