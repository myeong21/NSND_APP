package com.jsm.nsnd.data.local

import org.junit.Assert.*
import org.junit.Test

class DetectionPolicyTest {
    @Test fun transitionsRequirePositiveChangedStageAndNormalResets() {
        val stages = listOf(0, 1, 1, 2, 2, 0, 2, 3, 0)
        var previous = 0
        val saved = stages.filter { stage ->
            DetectionPolicy.shouldSave(previous, stage).also { previous = stage }
        }
        assertEquals(listOf(1, 2, 2, 3), saved)
        assertFalse(DetectionPolicy.shouldSave(1, -1))
        assertFalse(DetectionPolicy.shouldSave(1, 4))
    }

    @Test fun fixesRejectStaleInaccurateInvalidAndFutureTimesButAcceptOverseasGps() {
        assertTrue(DetectionPolicy.usableFix(37.4, 127.1, 5f, 3_000))
        assertTrue(DetectionPolicy.usableFix(37.4, -122.1, 5f, 3_000))
        assertFalse(DetectionPolicy.usableFix(37.4, 127.1, 5f, 30_001))
        assertFalse(DetectionPolicy.usableFix(37.4, 127.1, 101f, 0))
        assertFalse(DetectionPolicy.usableFix(37.4, 127.1, null, 0))
        assertFalse(DetectionPolicy.usableFix(37.4, 127.1, 5f, -1))
        assertFalse(DetectionPolicy.usableFix(Double.NaN, 127.1, 5f, 0))
        assertFalse(DetectionPolicy.usableFix(91.0, 127.1, 5f, 0))
    }
}
