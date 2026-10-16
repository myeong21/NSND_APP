package com.jsm.nsnd.data.local

object SmsStatusPolicy {
    const val OK = -1 // Activity.RESULT_OK; keep this policy independently testable.
    fun state(sent: List<Int?>, delivered: List<Int?>, requestedAt: Long, now: Long, error: String?): String = when {
        error != null || sent.any { it != null && it != OK } -> "failed"
        delivered.any { it != null && it != OK } -> "delivery_failed"
        delivered.isNotEmpty() && delivered.all { it == OK } -> "delivered"
        sent.isNotEmpty() && sent.all { it == OK } -> "sent"
        now - requestedAt >= 120_000 -> "unknown"
        else -> "requested"
    }
    fun label(state: String): String = when (state) {
        "failed" -> "전송 실패"
        "delivery_failed" -> "전달 실패"
        "delivered" -> "전달 확인"
        "sent" -> "전송 성공 · 전달 미확인"
        "unknown" -> "전송 결과 미확인"
        else -> "발송 요청 · 결과 대기"
    }
}
