package com.jsm.nsnd.ui.common

/** Durations use one device's monotonic clock; server timestamps are for display only. */
class DrivingSessionTimer(private val elapsedRealtimeMs: () -> Long) {
    private var anchorMs: Long? = null
    private var initialSeconds = 0L

    fun start(elapsedSeconds: Long = 0L) {
        initialSeconds = elapsedSeconds.coerceAtLeast(0L)
        anchorMs = elapsedRealtimeMs()
    }

    fun elapsedSeconds(): Long {
        val anchor = anchorMs ?: return 0L
        return initialSeconds + (elapsedRealtimeMs() - anchor).coerceAtLeast(0L) / 1000L
    }
}
