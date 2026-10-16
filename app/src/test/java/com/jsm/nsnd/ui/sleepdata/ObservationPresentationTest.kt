package com.jsm.nsnd.ui.sleepdata

import org.junit.Assert.*
import org.junit.Test

class ObservationPresentationTest {
    @Test fun oldLogsWithoutObservationTimeAreExplicitlyInsufficient() {
        val text = ObservationPresentation.describe(null,0.0,0.0,0.0,null,null,0)
        assertTrue(text.contains("자료 없음"))
        assertTrue(text.contains("판정 자료 부족"))
        assertFalse(text.contains("100"))
    }
    @Test fun observationCoverageAndEventsHaveDifferentLabels() {
        val text = ObservationPresentation.describe(2,120.0,60.0,30.0,2.0/3,60.0,3)
        assertTrue(text.contains("독립 졸음 사건 2회"))
        assertTrue(text.contains("유효 관측 2.0분"))
        assertTrue(text.contains("66.7%"))
        assertTrue(text.contains("단계 상승 기록"))
    }
}
