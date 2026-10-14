package com.jsm.nsnd.data.local

import org.junit.Assert.*
import org.junit.Test

class DrowsinessAlarmPolicyTest {
    private fun sample(stage: Int) = DetectionSample(stage, .2, .4)

    @Test fun repeatsAndDownwardTransitionsNeverRearmAcknowledgedAudio() {
        val policy = DrowsinessAlarmPolicy()
        assertTrue(policy.accept(sample(2)))
        policy.acknowledge()
        repeat(100) { assertFalse(policy.accept(sample(2))) }
        assertFalse(policy.accept(sample(1)))
        assertTrue(policy.accept(sample(3)))
    }

    @Test fun invalidObservationsDoNotResetRiskOrTriggerAlerts() {
        val policy = DrowsinessAlarmPolicy()
        assertFalse(policy.accept(sample(3).copy(observationValid = false, alertActive = false)))
        assertTrue(policy.accept(sample(3)))
        policy.acknowledge()
        assertFalse(policy.accept(sample(0).copy(observationValid = false)))
        assertFalse(policy.accept(sample(3)))
    }

    @Test fun validNormalRecoveryAllowsTheNextIndependentEvent() {
        val policy = DrowsinessAlarmPolicy()
        assertTrue(policy.accept(sample(1)))
        policy.acknowledge()
        assertFalse(policy.accept(sample(0)))
        assertTrue(policy.accept(sample(1)))
    }

    @Test fun aNewEmergencyAtTheSameLevelRearmsOnceAfterUserStop() {
        val policy = DrowsinessAlarmPolicy()
        assertTrue(policy.accept(sample(3)))
        policy.acknowledge()
        assertTrue(policy.accept(sample(3).copy(emergencyCondition = true)))
        repeat(20) { assertFalse(policy.accept(sample(3).copy(emergencyCondition = true))) }
    }

    @Test fun repeatedEventFramesDoNotUndoAUsersStop() {
        val policy = DrowsinessAlarmPolicy()
        assertTrue(policy.accept(sample(3).copy(newDrowsinessEvent = true)))
        policy.acknowledge()
        assertFalse(policy.accept(sample(3).copy(newDrowsinessEvent = true)))
        assertFalse(policy.accept(sample(3)))
        assertTrue(policy.accept(sample(3).copy(newDrowsinessEvent = true)))
    }

    @Test fun serverAckPropagationDoesNotImmediatelyRestartAudio() {
        val policy = DrowsinessAlarmPolicy()
        assertTrue(policy.accept(sample(2).copy(alarmAckRequired = true)))
        policy.acknowledge()
        assertFalse(policy.accept(sample(2).copy(alarmAcknowledged = false, alarmAckRequired = true)))
        assertFalse(policy.accept(sample(2).copy(alarmAcknowledged = true, alarmAckRequired = false)))
        assertTrue(policy.accept(sample(2).copy(alarmAcknowledged = false, alarmAckRequired = true)))
        assertFalse(policy.accept(sample(2).copy(alarmAckRequired = true)))
    }

    @Test fun reconnectingToAnAlreadyAcknowledgedRiskDoesNotSoundAgain() {
        assertFalse(DrowsinessAlarmPolicy().accept(sample(3).copy(alarmAcknowledged = true)))
    }

    @Test fun risingEventCountRecoversAMissedSingleFrameEventSignal() {
        val policy = DrowsinessAlarmPolicy()
        assertTrue(policy.accept(sample(3).copy(recentEventCount = 3)))
        policy.acknowledge()
        assertFalse(policy.accept(sample(3).copy(recentEventCount = 3)))
        assertTrue(policy.accept(sample(3).copy(recentEventCount = 4)))
        assertFalse(policy.accept(sample(3).copy(recentEventCount = 4)))
    }
}
