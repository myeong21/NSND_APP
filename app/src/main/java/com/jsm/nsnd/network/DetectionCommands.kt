package com.jsm.nsnd.network

import retrofit2.HttpException

/** A 404 is idempotent only after the server confirms detection is inactive. */
suspend fun confirmDetectionStopped(stop: suspend () -> Unit, isRunning: suspend () -> Boolean) {
    try {
        stop()
    } catch (error: HttpException) {
        if (error.code() != 404 || isRunning()) throw error
    }
}
