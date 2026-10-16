package com.jsm.nsnd.service

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.jsm.nsnd.data.local.SmsHistoryStore
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.ui.contact.ContactItem
import java.util.UUID

object EmergencySmsSender {
    fun send(context: Context, contacts: List<ContactItem>, eventKey: String, purpose: String,
        expectedAccount: String? = SessionManager(context).getDetectionAccountKey()): Int {
        val account = SessionManager(context).getDetectionAccountKey() ?: return 0
        if (account != expectedAccount) return 0
        val manager = context.getSystemService(SmsManager::class.java)
        var requested = 0
        for (contact in contacts) {
            if (SessionManager(context).getDetectionAccountKey() != account) break
            val parts = runCatching { manager.divideMessage(contact.message) }.getOrDefault(arrayListOf(contact.message))
            val id = SmsHistoryStore.create(context,account,eventKey,contact.name,contact.phone,contact.message,parts.size,purpose) ?: continue
            if (ContextCompat.checkSelfPermission(context,Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                SmsHistoryStore.fail(context,id,"SMS 권한이 없습니다. 권한을 허용한 뒤 다시 발송하세요.")
                continue
            }
            try {
                val sent = ArrayList(parts.indices.map { callback(context,id,it,false) })
                val delivered = ArrayList(parts.indices.map { callback(context,id,it,true) })
                manager.sendMultipartTextMessage(contact.phone,null,parts,sent,delivered)
                requested++
            } catch (error: Exception) {
                SmsHistoryStore.fail(context,id,error.message ?: "SMS 요청을 처리하지 못했습니다")
            }
        }
        return requested
    }

    fun riskKey(sessionId: Int): String = DetectionForegroundService.latestDetection
        ?.takeIf { it.sessionId == sessionId }?.sample?.serverEventId?.let { "server:$it" }
        ?: "legacy:$sessionId:${UUID.randomUUID()}"

    private fun callback(context: Context, id: String, part: Int, delivery: Boolean): PendingIntent =
        PendingIntent.getBroadcast(context,0,Intent(context,SmsResultReceiver::class.java).apply {
            action = if (delivery) ACTION_DELIVERED else ACTION_SENT
            data = Uri.parse("nsnd-sms://result/$id/$part/${if (delivery) "delivery" else "sent"}")
            putExtra("message_id",id); putExtra("part",part)
        },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    const val ACTION_SENT = "com.jsm.nsnd.SMS_SENT"
    const val ACTION_DELIVERED = "com.jsm.nsnd.SMS_DELIVERED"
}

class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(EmergencySmsSender.ACTION_SENT,EmergencySmsSender.ACTION_DELIVERED)) return
        val id = intent.getStringExtra("message_id") ?: return
        val part = intent.getIntExtra("part",-1)
        if (part < 0) return
        SmsHistoryStore.recordResult(context,id,part,intent.action == EmergencySmsSender.ACTION_DELIVERED,resultCode)
    }
}
