package com.jsm.nsnd.data.local

import org.junit.Assert.*
import org.junit.Test

class AutomaticSmsPolicyTest {
    private fun dangerous() = DetectionSample(3,.15,.9,episodeId="episode-1")

    @Test fun liveStageThreeRequiresSmsWithoutAnOptInSetting() {
        assertTrue(AutomaticSmsPolicy.requiresDispatch(dangerous()))
    }

    @Test fun stoppingTheAudibleAlarmDoesNotCancelRequiredSms() {
        assertTrue(AutomaticSmsPolicy.requiresDispatch(dangerous().copy(alarmAcknowledged=true)))
    }

    @Test fun invalidObservationsAndLowerStagesDoNotSendSms() {
        assertFalse(AutomaticSmsPolicy.requiresDispatch(dangerous().copy(observationValid=false)))
        assertFalse(AutomaticSmsPolicy.requiresDispatch(dangerous().copy(alertActive=false)))
        for (stage in 0..2) assertFalse(AutomaticSmsPolicy.requiresDispatch(dangerous().copy(stage=stage)))
    }

    @Test fun missingEpisodeIdentityDoesNotBypassDuplicateProtection() {
        assertFalse(AutomaticSmsPolicy.requiresDispatch(dangerous().copy(episodeId=null)))
        assertFalse(AutomaticSmsPolicy.requiresDispatch(dangerous().copy(episodeId="")))
    }
}
