package com.jsm.nsnd.network

import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class DetectionCommandsTest {
    private fun error(code: Int) = HttpException(Response.error<Unit>(code, "{}".toResponseBody()))

    @Test fun successfulStopDoesNotNeedAStatusRequest() = runBlocking {
        confirmDetectionStopped({}, { fail("unexpected status request"); true })
    }

    @Test fun alreadyStoppedIsConfirmedBeforeAccepting404() = runBlocking {
        var checked = false
        confirmDetectionStopped({ throw error(404) }, { checked = true; false })
        assertTrue(checked)
    }

    @Test fun stillRunning404MustNotBecomeSuccess() = runBlocking {
        try {
            confirmDetectionStopped({ throw error(404) }, { true })
            fail("running detection cannot be treated as stopped")
        } catch (e: HttpException) { assertEquals(404, e.code()) }
    }

    @Test fun serverFailureDoesNotBecomeSuccess() = runBlocking {
        try {
            confirmDetectionStopped({ throw error(500) }, { fail("unexpected status request"); false })
            fail("server failure cannot become success")
        } catch (e: HttpException) { assertEquals(500, e.code()) }
    }
}
