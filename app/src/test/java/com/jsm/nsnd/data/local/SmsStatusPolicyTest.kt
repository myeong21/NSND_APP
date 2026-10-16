package com.jsm.nsnd.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsStatusPolicyTest {
    @Test fun multipartWaitsForEveryPartAndNeverCallsSentDelivered() {
        assertEquals("requested",SmsStatusPolicy.state(listOf(-1,null),listOf(null,null),0,1,null))
        assertEquals("sent",SmsStatusPolicy.state(listOf(-1,-1),listOf(-1,null),0,1,null))
        assertEquals("delivered",SmsStatusPolicy.state(listOf(-1,-1),listOf(-1,-1),0,1,null))
    }
    @Test fun deliveryBeforeSendCallbacksIsStillConfirmed() {
        assertEquals("delivered",SmsStatusPolicy.state(listOf(null),listOf(-1),0,1,null))
    }
    @Test fun failuresAndMissingCallbacksAreDistinct() {
        assertEquals("failed",SmsStatusPolicy.state(listOf(-1,4),listOf(null,null),0,1,null))
        assertEquals("delivery_failed",SmsStatusPolicy.state(listOf(-1),listOf(2),0,1,null))
        assertEquals("unknown",SmsStatusPolicy.state(listOf(null),listOf(null),0,120_000,null))
        assertEquals("failed",SmsStatusPolicy.state(listOf(null),listOf(null),0,1,"권한 없음"))
    }
}
