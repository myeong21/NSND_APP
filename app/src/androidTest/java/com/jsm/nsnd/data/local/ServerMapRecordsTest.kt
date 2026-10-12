package com.jsm.nsnd.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jsm.nsnd.network.model.DrowsyEvent
import com.jsm.nsnd.ui.common.MapRecord
import com.jsm.nsnd.ui.common.ServerMapRecords
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerMapRecordsTest {
    @Test fun legacyCoordinatesRemainServerRowsAndMissingCoordinatesAreNotInvented() {
        val events = listOf(
            DrowsyEvent("2026-09-20T14:15:30", 1, 37.3948, 127.1119),
            DrowsyEvent("2026-09-20T14:15:30", 2, 37.3948, 127.1119),
            DrowsyEvent("2026-09-20T14:48:50", 3, null, null)
        )
        val rows = ServerMapRecords.rows(events, "date:2026-09-20")
        assertEquals(3, rows.size)
        assertEquals(2, rows.count { it.hasCoordinates })
        assertEquals(3, rows.map { it.id }.distinct().size)
        assertTrue(rows.all { it.local == null && !it.isLocal })
        assertEquals(37.3948, rows[0].latitude!!, .000001)
        assertEquals(127.1119, rows[0].longitude!!, .000001)
        assertNull(rows[2].latitude)
        assertNull(rows[0].ear)
        assertEquals("서버 기록 시각", rows[0].timeLabel())
        assertFalse(rows[0].id == rows[1].id)
        assertTrue(rows[1].markerJavascript().contains(rows[1].id))
        assertFalse(MapRecord.fromServer(DrowsyEvent("2026-09-20", 1, 91.0, 127.0), "invalid", 0).hasCoordinates)
    }
}
