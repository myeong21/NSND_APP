package com.jsm.nsnd.ui.common

import android.content.Context
import androidx.appcompat.app.AlertDialog

object MapRecordDialogs {
    fun detail(context: Context, record: MapRecord) {
        record.local?.let { LocalRecordDialogs.detail(context, it); return }
        AlertDialog.Builder(context).setTitle("서버 감지 기록")
            .setMessage("서버 기록 시각: ${record.receiptText()}\n졸음 ${record.stage}단계\n${record.coordinatesText()}\n" +
                "EAR·예측 점수·위치 측정 정보: 서버 리포트에서 미제공" +
                (record.sessionId?.let { "\n운전 세션 #$it" } ?: ""))
            .setPositiveButton("닫기", null).show()
    }
    fun list(context: Context, records: List<MapRecord>, serverRecords: Boolean = records.firstOrNull()?.isLocal == false,
             onSelect: (MapRecord) -> Unit = { detail(context, it) }) {
        val title = if (!serverRecords) "로컬 기록 ${records.size}건 · 앱 수신 시각"
            else "서버 기록 ${records.size}건 · 서버 기록 시각"
        val dialog = AlertDialog.Builder(context).setTitle(title).setNegativeButton("닫기", null)
        if (records.isEmpty()) dialog.setMessage("조회된 기록이 없습니다.")
        else dialog.setItems(records.map {
            "${it.receiptText()} · ${it.stage}단계\n${it.coordinatesText()} · ${if (it.isLocal) it.id.take(8) else it.id.substringAfterLast(':')}"
        }.toTypedArray()) { _, index -> onSelect(records[index]) }
        dialog.show()
    }
    fun selectAt(context: Context, record: MapRecord, records: List<MapRecord>, onSelect: (MapRecord) -> Unit = { detail(context, it) }) {
        val overlapping = records.filter { it.hasCoordinates && record.hasCoordinates &&
            kotlin.math.abs(it.latitude!! - record.latitude!!) < .0001 &&
            kotlin.math.abs(it.longitude!! - record.longitude!!) < .0001 }
        if (overlapping.size > 1) list(context, overlapping, onSelect = onSelect) else onSelect(record)
    }
}
