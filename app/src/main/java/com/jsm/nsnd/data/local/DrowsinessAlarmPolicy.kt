package com.jsm.nsnd.data.local

/** Tracks valid server observations separately from the user's local audio acknowledgement. */
class DrowsinessAlarmPolicy {
    private var previousStage = 0
    private var locallyStopped = false
    private var sawServerAcknowledgement = false
    private var previousNewEvent = false
    private var previousEmergency = false
    private var previousEventCount: Int? = null

    @Synchronized fun acknowledge() {
        locallyStopped = true
        sawServerAcknowledgement = false
    }

    @Synchronized fun accept(sample: DetectionSample): Boolean {
        if (!sample.observationValid) return false
        if (sample.alarmAcknowledged == true) sawServerAcknowledgement = true
        val newRisk = (sample.newDrowsinessEvent && !previousNewEvent) ||
            (sample.emergencyCondition && !previousEmergency) ||
            (sample.recentEventCount != null && previousEventCount != null && sample.recentEventCount > previousEventCount!!) ||
            (locallyStopped && sawServerAcknowledgement && sample.alarmAckRequired == true)
        val shouldAlert = sample.alertActive && sample.stage > 0 &&
            sample.alarmAcknowledged != true &&
            (sample.stage > previousStage || (locallyStopped && newRisk))
        previousStage = sample.stage
        previousNewEvent = sample.newDrowsinessEvent
        previousEmergency = sample.emergencyCondition
        previousEventCount = sample.recentEventCount
        if (shouldAlert || sample.stage == 0) {
            locallyStopped = false
            sawServerAcknowledgement = false
        }
        return shouldAlert
    }
}
