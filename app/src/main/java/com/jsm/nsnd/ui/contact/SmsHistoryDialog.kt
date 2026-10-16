package com.jsm.nsnd.ui.contact

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.jsm.nsnd.data.local.SmsHistoryStore
import com.jsm.nsnd.data.local.SmsStatusPolicy
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.service.EmergencySmsSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object SmsHistoryDialog {
    fun show(context: Context) {
        val account = SessionManager(context).getDetectionAccountKey() ?: return
        val rows = SmsHistoryStore.history(context,account)
        val date = SimpleDateFormat("MM.dd HH:mm:ss",Locale.KOREAN)
        val dialog = AlertDialog.Builder(context).setTitle("SMS 발송 이력 · 최근 50건")
            .setNegativeButton("닫기",null).setNeutralButton("새로고침") { _, _ -> show(context) }
        if (rows.isEmpty()) dialog.setMessage("발송 이력이 없습니다.")
        else dialog.setItems(rows.map {
            "${date.format(Date(it.requestedAt))} · ${it.name}\n${SmsStatusPolicy.label(it.state)} · ${it.purpose}"
        }.toTypedArray()) { _, index ->
            val row = rows[index]
            val detail = AlertDialog.Builder(context).setTitle(SmsStatusPolicy.label(row.state))
                .setMessage("${row.name} · ${row.phone}\n${date.format(Date(row.requestedAt))} · ${row.purpose}\n\n${row.message}\n\n" +
                    (row.error ?: "전달 확인은 통신망이 결과를 제공한 경우 표시됩니다."))
                .setNegativeButton("닫기",null)
            if (row.state in setOf("failed","delivery_failed")) detail.setPositiveButton("재전송") { _, _ ->
                AlertDialog.Builder(context).setTitle("문자를 다시 보낼까요?")
                    .setMessage("${row.phone}로 다시 발송합니다. 긴 문자의 일부는 이미 전송됐을 수 있습니다.")
                    .setNegativeButton("취소",null).setPositiveButton("발송") { _, _ ->
                        if (SessionManager(context).getDetectionAccountKey() == row.account) {
                            val count = EmergencySmsSender.send(context,listOf(ContactItem(0,row.name,row.phone,row.message)),
                                "retry:${UUID.randomUUID()}","실패 문자 재전송")
                            Toast.makeText(context,"${count}개 발송 요청 · 이력에서 결과를 확인하세요",Toast.LENGTH_LONG).show()
                        }
                    }.show()
            }
            detail.show()
        }
        dialog.show()
    }
}
