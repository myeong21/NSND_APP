package com.jsm.nsnd.data.local

data class SyncRetry(val state: String, val nextAttemptAt: Long, val message: String)

object SyncRetryPolicy {
    fun failure(attempt: Int, code: Int?, now: Long): SyncRetry {
        if (code in setOf(400, 403, 422)) return SyncRetry("failed", 0, "서버 응답 $code · 자료 확인 필요")
        // A new server event can briefly return 404 before the journal is flushed.
        if (code == 404 && attempt >= 12) return SyncRetry("needs_review", 0, "반복 조회해도 서버 기록이 없습니다. 기록 동기화 화면에서 다시 확인하세요.")
        val delay = (30_000L * (1L shl (attempt - 1).coerceIn(0, 7))).coerceAtMost(3_600_000L)
        return SyncRetry("pending", now + delay, if (code == null) "통신 연결 대기" else "서버 응답 $code · 재시도 대기")
    }
}
