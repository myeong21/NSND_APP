package com.jsm.nsnd.data.local

/** Shares the live SMS trigger, but consumes each episode even when no call can be made. */
object AutomaticVideoCallPolicy {
    fun dispatch(
        sample: DetectionSample,
        phones: List<String>,
        claim: (episode: String, phone: String?) -> Boolean,
        isBusy: () -> Boolean,
        placeCall: (String) -> Unit
    ): Boolean {
        if (!AutomaticSmsPolicy.requiresDispatch(sample)) return false
        val phone = phones.map { it.filter(Char::isDigit) }
            .filter { it.length in 8..15 }.distinct().randomOrNull()
        // Persist before any side effect, including skips, so reconnects never replay a call.
        if (!claim(sample.episodeId!!, phone) || phone == null || isBusy()) return false
        placeCall(phone)
        return true
    }
}
