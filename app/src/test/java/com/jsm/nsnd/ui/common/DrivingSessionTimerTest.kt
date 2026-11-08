package com.jsm.nsnd.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class DrivingSessionTimerTest {
    @Test fun newDriveStartsAtZeroDespiteServerClockBeingAhead() {
        val phoneNowMs = 1_792_234_453_000L
        val serverStartMs = phoneNowMs + 28_000L
        assertEquals(-28L, (phoneNowMs - serverStartMs) / 1000L)

        var uptimeMs = 10_000L
        val timer = DrivingSessionTimer { uptimeMs }
        timer.start()
        assertEquals(0L, timer.elapsedSeconds())
        uptimeMs += 1000L
        assertEquals(1L, timer.elapsedSeconds())
    }

    @Test fun restoredDriveContinuesFromServerDurationIncludingTimeAsleep() {
        var uptimeMs = 10_000L
        val timer = DrivingSessionTimer { uptimeMs }
        timer.start(65L)
        assertEquals(65L, timer.elapsedSeconds())
        uptimeMs += 125_000L
        assertEquals(190L, timer.elapsedSeconds())
    }

    @Test fun wallClockCorrectionsDoNotChangeDrivingDuration() {
        var uptimeMs = 10_000L
        var wallClockMs = 1_792_234_453_000L
        val timer = DrivingSessionTimer { uptimeMs }
        timer.start()
        wallClockMs -= 3_600_000L
        uptimeMs += 3000L
        assertEquals(3L, timer.elapsedSeconds())
        wallClockMs += 7_200_000L
        assertEquals(1_792_238_053_000L, wallClockMs)
        assertEquals(3L, timer.elapsedSeconds())
    }

    @Test fun nextDriveResetsToZeroAndInvalidNegativeRestoreIsClamped() {
        var uptimeMs = 10_000L
        val timer = DrivingSessionTimer { uptimeMs }
        assertEquals(0L, timer.elapsedSeconds())
        timer.start(65L)
        uptimeMs += 5000L
        assertEquals(70L, timer.elapsedSeconds())
        timer.start()
        assertEquals(0L, timer.elapsedSeconds())
        timer.start(-28L)
        assertEquals(0L, timer.elapsedSeconds())
    }
}
