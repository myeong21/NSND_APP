package com.jsm.nsnd.ui.common

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.jsm.nsnd.data.local.LocalDetectionRecord

object LocalRecordDialogs {
    fun detail(context: Context, record: LocalDetectionRecord) {
        AlertDialog.Builder(context).setTitle("로컬 졸음 기록")
            .setMessage(record.detailText()).setPositiveButton("닫기", null).show()
    }

    /** Entries retain UUID identity even when timestamps and coordinates are equal. */
    fun list(context: Context, records: List<LocalDetectionRecord>,
             onSelect: (LocalDetectionRecord) -> Unit = { detail(context, it) }) {
        if (records.isEmpty()) {
            AlertDialog.Builder(context).setMessage("저장된 로컬 감지 기록이 없습니다.")
                .setPositiveButton("닫기", null).show()
            return
        }
        val labels = records.map {
            "${if (it.source == "recovered") "서버 기록 (복구)" else "앱 수신"} ${it.receiptText()} · ${it.stage}단계\n${it.coordinatesText()} · ${it.id.take(8)}"
        }.toTypedArray()
        AlertDialog.Builder(context).setTitle(if (records.any { it.source == "recovered" }) "로컬 기록 ${records.size}건 · 복구 이력 포함" else "로컬 기록 ${records.size}건 · 앱 수신 시각")
            .setItems(labels) { _, index -> onSelect(records[index]) }
            .setNegativeButton("닫기", null).show()
    }

    fun selectAt(context: Context, record: LocalDetectionRecord, records: List<LocalDetectionRecord>,
                 onSelect: (LocalDetectionRecord) -> Unit = { detail(context, it) }) {
        val overlapping = records.filter {
            it.hasCoordinates && record.hasCoordinates &&
                kotlin.math.abs(it.latitude!! - record.latitude!!) < 0.0001 &&
                kotlin.math.abs(it.longitude!! - record.longitude!!) < 0.0001
        }
        if (overlapping.size > 1) list(context, overlapping, onSelect) else onSelect(record)
    }
}
