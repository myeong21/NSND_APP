package com.jsm.nsnd.data.local

/** A live stage-three episode requires SMS even if the audible alarm was acknowledged. */
object AutomaticSmsPolicy {
    fun requiresDispatch(sample: DetectionSample): Boolean =
        sample.observationValid && sample.stage == 3 && sample.alertActive && !sample.episodeId.isNullOrBlank()
}
