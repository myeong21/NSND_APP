package com.jsm.nsnd.data.local

import org.junit.Assert.*
import org.junit.Test

class DistancePolicyTest {
    @Test fun normalRoadTravelIsCounted() {
        assertTrue(DetectionPolicy.usableDistance(90f, 3000, 5f, 5f))
    }
    @Test fun largeJumpsAndTimeReversalAreRejected() {
        assertFalse(DetectionPolicy.usableDistance(5000f, 3000, 5f, 5f))
        assertFalse(DetectionPolicy.usableDistance(20f, -1, 5f, 5f))
        assertFalse(DetectionPolicy.usableDistance(20f, 0, 5f, 5f))
    }
    @Test fun missingAndInaccurateFixesCannotAccumulateDistance() {
        assertFalse(DetectionPolicy.usableDistance(20f, 3000, null, 5f))
        assertFalse(DetectionPolicy.usableDistance(20f, 3000, 200f, 5f))
        assertFalse(DetectionPolicy.usableDistance(Float.NaN, 3000, 5f, 5f))
    }
}
