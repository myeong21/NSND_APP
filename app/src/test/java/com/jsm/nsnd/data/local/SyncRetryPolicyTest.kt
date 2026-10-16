package com.jsm.nsnd.data.local

import org.junit.Assert.*
import org.junit.Test

class SyncRetryPolicyTest {
    @Test fun delayedServerInsertRemainsRetryableButPersistentMissingRecordNeedsReview() {
        assertEquals("pending", SyncRetryPolicy.failure(1,404,1000).state)
        assertEquals("needs_review", SyncRetryPolicy.failure(12,404,1000).state)
    }
    @Test fun invalidPayloadsStopAutomaticRetryWhileNetworkFailureBacksOff() {
        for (code in listOf(400,403,422)) assertEquals("failed",SyncRetryPolicy.failure(1,code,1000).state)
        val first = SyncRetryPolicy.failure(1,null,1000)
        val second = SyncRetryPolicy.failure(2,503,1000)
        assertEquals("pending",second.state)
        assertTrue(second.nextAttemptAt > first.nextAttemptAt)
        assertEquals(3_601_000L,SyncRetryPolicy.failure(30,503,1000).nextAttemptAt)
    }
}
