package com.jsm.nsnd.data.local

import org.junit.Assert.*
import org.junit.Test

class AutomaticVideoCallPolicyTest {
    private val handled = mutableMapOf<String, String?>()
    private val calls = mutableListOf<String>()
    private var busy = false
    private fun dangerous(episode: String = "episode-1") = DetectionSample(
        stage = 3, ear = .1, predictionScore = .9, observationValid = true,
        alertActive = true, episodeId = episode)

    private fun dispatch(sample: DetectionSample = dangerous(), phones: List<String> = listOf("010-1234-5678")) =
        AutomaticVideoCallPolicy.dispatch(sample, phones,
            claim = { episode, phone ->
                if (handled.containsKey(episode)) false else { handled[episode] = phone; true }
            }, isBusy = { busy }, placeCall = { calls.add(it) })

    @Test fun callsOneRegisteredNumberPerEpisodeEvenWhenAlarmWasAcknowledged() {
        val phones = listOf("01011112222", "01033334444", "01055556666")
        assertTrue(dispatch(dangerous().copy(alarmAcknowledged = true), phones))
        assertEquals(1, calls.size)
        assertTrue(calls.single() in phones)
        assertFalse(dispatch(dangerous(), phones))
        assertEquals(calls.single(), handled["episode-1"])
    }

    @Test fun sameStageNewEpisodeCanCallAgain() {
        assertTrue(dispatch())
        assertTrue(dispatch(dangerous("episode-2")))
        assertEquals(2, calls.size)
    }

    @Test fun busyEpisodeIsSkippedEvenAfterCallEnds() {
        busy = true
        assertFalse(dispatch())
        busy = false
        assertFalse(dispatch())
        assertTrue(dispatch(dangerous("episode-2")))
        assertEquals(1, calls.size)
    }

    @Test fun emptyOrInvalidContactsNeverCallOrReplayAfterContactsAreAdded() {
        assertFalse(dispatch(phones = emptyList()))
        assertFalse(dispatch())
        assertFalse(dispatch(dangerous("episode-2"), listOf("", "123", "1234567890123456")))
        assertTrue(calls.isEmpty())
    }

    @Test fun onlyLiveValidStageThreeEpisodesCanCall() {
        for (stage in 0..2) assertFalse(dispatch(dangerous().copy(stage = stage)))
        assertFalse(dispatch(dangerous().copy(observationValid = false)))
        assertFalse(dispatch(dangerous().copy(alertActive = false)))
        assertFalse(dispatch(dangerous().copy(episodeId = null)))
        assertFalse(dispatch(dangerous().copy(episodeId = "")))
        assertTrue(handled.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test fun failedDurableClaimDoesNotPlaceCall() {
        assertFalse(AutomaticVideoCallPolicy.dispatch(dangerous(), listOf("01012345678"),
            claim = { _, _ -> false }, isBusy = { false }, placeCall = { calls.add(it) }))
        assertTrue(calls.isEmpty())
    }

    @Test fun failedCallIsConsumedWithoutRetryingAnotherRecipient() {
        runCatching {
            AutomaticVideoCallPolicy.dispatch(dangerous(), listOf("01012345678"),
                claim = { episode, phone -> handled[episode] = phone; true },
                isBusy = { false }, placeCall = { error("unsupported video call") })
        }
        assertFalse(dispatch())
        assertTrue(calls.isEmpty())
    }
}
