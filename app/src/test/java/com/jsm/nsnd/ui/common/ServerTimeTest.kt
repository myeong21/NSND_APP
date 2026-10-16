package com.jsm.nsnd.ui.common

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ServerTimeTest {
    @Test fun legacyUtcAndExplicitOffsetsReferToTheSameInstant() {
        assertEquals(ServerTime.millis("2026-10-12T23:30:00"), ServerTime.millis("2026-10-13T08:30:00+09:00"))
        assertEquals(ServerTime.millis("2026-10-12T23:30:00Z"), ServerTime.millis("2026-10-12T23:30:00"))
    }
    @Test fun koreaDayAndDisplayUseLocalDateInsteadOfUtcDate() {
        val zone = ZoneId.of("Asia/Seoul")
        assertEquals(540, ServerTime.utcOffsetMinutes("2026-10-13", zone))
        assertEquals("2026.10.13 08:30", ServerTime.display("2026-10-12T23:30:00", zone = zone))
    }
    @Test fun malformedTimestampCannotCreateAnIncorrectTimer() {
        assertNull(ServerTime.millis("invalid"))
    }
}
