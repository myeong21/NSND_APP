package com.jsm.nsnd.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

data class SmsHistoryRecord(val id: String, val account: String, val name: String, val phone: String,
    val message: String, val requestedAt: Long, val purpose: String, val state: String, val error: String?)

object SmsHistoryStore {
    fun createTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sms_messages (
            id TEXT PRIMARY KEY, account_key TEXT NOT NULL, event_key TEXT NOT NULL,
            name TEXT NOT NULL, phone TEXT NOT NULL, message TEXT NOT NULL,
            requested_at_ms INTEGER NOT NULL, purpose TEXT NOT NULL, failure_reason TEXT,
            UNIQUE(account_key,event_key,phone))""")
        db.execSQL("""CREATE TABLE sms_parts (
            message_id TEXT NOT NULL, part INTEGER NOT NULL, sent_result INTEGER, delivery_result INTEGER,
            PRIMARY KEY(message_id,part))""")
    }

    @Synchronized fun create(context: Context, account: String, eventKey: String, name: String,
        phone: String, message: String, parts: Int, purpose: String): String? {
        val store = LocalDetectionStore.get(context)
        return synchronized(store) {
        if (store.isAccountRetired(account)) return@synchronized null
        val db = store.writableDatabase
        val id = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            val inserted = db.insertWithOnConflict("sms_messages", null, ContentValues().apply {
                put("id",id); put("account_key",account); put("event_key",eventKey); put("name",name)
                put("phone",phone.filter(Char::isDigit)); put("message",message); put("requested_at_ms",System.currentTimeMillis()); put("purpose",purpose)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted == -1L) return null
            repeat(parts) { part -> db.insertOrThrow("sms_parts",null,ContentValues().apply {
                put("message_id",id); put("part",part)
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        id
        }
    }

    @Synchronized fun fail(context: Context, id: String, reason: String) {
        LocalDetectionStore.get(context).writableDatabase.update("sms_messages",ContentValues().apply {
            put("failure_reason",reason)
        },"id=?",arrayOf(id))
    }

    @Synchronized fun recordResult(context: Context, id: String, part: Int, delivery: Boolean, result: Int) {
        val column = if (delivery) "delivery_result" else "sent_result"
        LocalDetectionStore.get(context).writableDatabase.update("sms_parts",ContentValues().apply {
            put(column,result)
        },"message_id=? AND part=? AND $column IS NULL",arrayOf(id,part.toString()))
    }

    @Synchronized fun history(context: Context, account: String): List<SmsHistoryRecord> {
        val db = LocalDetectionStore.get(context).readableDatabase
        return db.query("sms_messages",null,"account_key=?",arrayOf(account),null,null,"requested_at_ms DESC","50").use { c ->
            fun text(key: String): String = c.getString(c.getColumnIndexOrThrow(key))
            buildList {
                while (c.moveToNext()) {
                    val id = text("id")
                    val sent = mutableListOf<Int?>(); val delivered = mutableListOf<Int?>()
                    db.rawQuery("SELECT sent_result,delivery_result FROM sms_parts WHERE message_id=? ORDER BY part",arrayOf(id)).use { p ->
                        while (p.moveToNext()) {
                            sent.add(if (p.isNull(0)) null else p.getInt(0))
                            delivered.add(if (p.isNull(1)) null else p.getInt(1))
                        }
                    }
                    val requested = c.getLong(c.getColumnIndexOrThrow("requested_at_ms"))
                    val error = c.getString(c.getColumnIndexOrThrow("failure_reason")) ?:
                        (sent + delivered).firstOrNull { it != null && it != SmsStatusPolicy.OK }?.let { "통신 결과 코드 $it" }
                    val state = SmsStatusPolicy.state(sent,delivered,requested,System.currentTimeMillis(),
                        c.getString(c.getColumnIndexOrThrow("failure_reason")))
                    add(SmsHistoryRecord(id,account,text("name"),text("phone"),text("message"),requested,text("purpose"),state,error))
                }
            }
        }
    }
}
