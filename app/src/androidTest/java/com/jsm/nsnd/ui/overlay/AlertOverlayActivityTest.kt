package com.jsm.nsnd.ui.overlay

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jsm.nsnd.R
import com.jsm.nsnd.service.DetectionForegroundService
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Synthetic app-internal broadcasts only: no backend connection or actual SMS. */
@RunWith(AndroidJUnit4::class)
class AlertOverlayActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sessionId = 99107
    private var savedAnimatorScale = "1"

    @Before fun disableLoopingAnimationsForInstrumentationIdle() {
        context.contentResolver.let {
            savedAnimatorScale = android.provider.Settings.Global.getString(it, "animator_duration_scale") ?: "1"
        }
        setAnimatorScale("0")
    }

    @After fun restoreAnimatorScale() {
        setAnimatorScale(savedAnimatorScale)
    }

    private fun setAnimatorScale(value: String) {
        instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale $value").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        SystemClock.sleep(250)
    }

    private fun launch() = ActivityScenario.launch<AlertOverlayActivity>(
        Intent(context, AlertOverlayActivity::class.java).apply {
            putExtra(AlertOverlayActivity.EXTRA_STAGE, 1)
            // Avoid sending messages to the user's contacts during a UI test.
            putExtra(AlertOverlayActivity.EXTRA_SMS_HANDLED, true)
            putExtra(DetectionForegroundService.EXTRA_SESSION_ID, sessionId)
        }
    )

    private fun broadcast(payload: String, session: Int = sessionId) {
        context.sendBroadcast(Intent(DetectionForegroundService.ACTION_DETECTION_EVENT).apply {
            setPackage(context.packageName)
            putExtra(DetectionForegroundService.EXTRA_SESSION_ID, session)
            putExtra(DetectionForegroundService.EXTRA_PAYLOAD, payload)
        })
        // A broadcast crosses Binder before being queued on the activity's main thread.
        SystemClock.sleep(150)
    }

    private fun assertStage(scenario: ActivityScenario<AlertOverlayActivity>, stage: Int) {
        scenario.onActivity { activity ->
            val label = activity.findViewById<TextView>(R.id.tvAlertStage)
            assertTrue(label.text.toString().startsWith("${stage}단계"))
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, label.accessibilityLiveRegion)
            assertEquals(if (stage >= 2 && android.animation.ValueAnimator.areAnimatorsEnabled()) View.VISIBLE else View.GONE,
                activity.findViewById<View>(R.id.viewFlash).visibility)
            val segments = activity.findViewById<LinearLayout>(R.id.layoutStageIndicator)
            assertEquals(3, segments.childCount)
            for (index in 0..2) assertEquals(
                "${index + 1} 단계 ${if (index < stage) "활성" else "비활성"}",
                segments.getChildAt(index).contentDescription
            )
        }
    }

    @Test fun sameScreenTracksRiseFallAndIgnoresInvalidOrOtherSessionMessages() {
        launch().use { scenario ->
            assertStage(scenario, 1)
            broadcast("{\"drowsy_level\":2}")
            assertStage(scenario, 2)
            broadcast("{\"drowsy_level\":3}")
            assertStage(scenario, 3)
            broadcast("{\"drowsy_level\":2}")
            assertStage(scenario, 2)
            broadcast("{\"drowsy_level\":1}")
            assertStage(scenario, 1)
            broadcast("{\"drowsy_level\":3}", sessionId + 1)
            broadcast("{\"ping\":true}")
            broadcast("{\"error\":\"camera unavailable\",\"drowsy_level\":0}")
            broadcast("{\"drowsy_level\":4}")
            assertStage(scenario, 1)
        }
    }

    @Test fun reducedMotionAndRepeatedStagesKeepStopButtonAvailableAndRecreationKeepsStage() {
        launch().use { scenario ->
            broadcast("{\"drowsy_level\":2}")
            broadcast("{\"drowsy_level\":2}")
            scenario.onActivity {
                assertNull(it.findViewById<ImageView>(R.id.ivRotatingRing).animation)
            }
            broadcast("{\"drowsy_level\":3}")
            scenario.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.btnDismiss).visibility) }
            scenario.recreate()
            assertStage(scenario, 3)
            scenario.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.btnDismiss).visibility) }
        }
    }

    @Test fun normalStageKeepsAudioUntilUserStopsIt() {
        launch().use { scenario ->
            broadcast("{\"drowsy_level\":0}")
            assertStage(scenario, 0)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                val playerField = AlertOverlayActivity::class.java.getDeclaredField("mediaPlayer").apply { isAccessible = true }
                val player = playerField.get(activity) as android.media.MediaPlayer
                assertTrue(player.isPlaying)
                activity.findViewById<View>(R.id.btnDismiss).performClick()
            }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            assertFalse(AlertOverlayActivity.isOpen)
        }
    }

    @Test fun invalidObservationExplainsMissingMeasurementWithoutSilencingAudio() {
        launch().use { scenario ->
            broadcast("{\"drowsy_level\":0,\"state\":\"NO_FACE\",\"observation_valid\":false,\"alert_active\":false}")
            scenario.onActivity { activity ->
                assertEquals("측정 불가", activity.findViewById<TextView>(R.id.tvAlertStage).text)
                assertTrue(activity.findViewById<TextView>(R.id.tvAlertDescription).text.contains("얼굴을 찾지 못했습니다"))
                val playerField = AlertOverlayActivity::class.java.getDeclaredField("mediaPlayer").apply { isAccessible = true }
                assertTrue((playerField.get(activity) as android.media.MediaPlayer).isPlaying)
            }
            broadcast("{\"drowsy_level\":0,\"state\":\"AWAKE\",\"observation_valid\":true,\"alert_active\":false}")
            assertStage(scenario, 0)
        }
    }

    @Test fun stopButtonSendsAuthenticatedAcknowledgementAndStopsAudioImmediately() {
        withFixturePreferences {
            HttpFixture(202, "{\"session_id\":$sessionId,\"status\":\"accepted\",\"message\":\"accepted\"}").use { server ->
                launchAt(2).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.findViewById<View>(R.id.btnDismiss).performClick()
                        assertNull(playerField().get(activity))
                    }
                    assertTrue(server.requestReceived.await(5, TimeUnit.SECONDS))
                    val request = server.request.get()
                    assertTrue(request.startsWith("POST /detection/alarm/acknowledge "))
                    assertTrue(request.contains("Authorization: Bearer fixture-token", ignoreCase = true))
                    assertTrue(request.contains("\"session_id\":$sessionId"))
                    server.releaseResponse.countDown()
                    await { scenario.state == Lifecycle.State.DESTROYED }
                }
            }
        }
    }

    @Test fun failedAcknowledgementLeavesAudioMutedAndAllowsRetry() {
        withFixturePreferences {
            HttpFixture(503, "{\"detail\":\"fixture temporarily unavailable\"}").use { server ->
                server.releaseResponse.countDown()
                launchAt(2).use { scenario ->
                    scenario.onActivity { it.findViewById<View>(R.id.btnDismiss).performClick() }
                    var retryAvailable = false
                    await {
                        scenario.onActivity {
                            retryAvailable = it.findViewById<TextView>(R.id.btnDismiss).text == "중지 확인 재시도"
                        }
                        retryAvailable
                    }
                    broadcast("{\"drowsy_level\":2,\"state\":\"DROWSY\",\"alarm_ack_required\":true}")
                    scenario.onActivity {
                        assertFalse(it.isFinishing)
                        assertNull(playerField().get(it))
                        assertTrue(it.findViewById<TextView>(R.id.tvAlertDescription).text.contains("fixture temporarily unavailable"))
                    }
                }
            }
        }
    }

    private fun playerField() = AlertOverlayActivity::class.java.getDeclaredField("mediaPlayer").apply { isAccessible = true }

    private fun launchAt(stage: Int) = ActivityScenario.launch<AlertOverlayActivity>(
        Intent(context, AlertOverlayActivity::class.java).apply {
            putExtra(AlertOverlayActivity.EXTRA_STAGE, stage)
            putExtra(AlertOverlayActivity.EXTRA_SMS_HANDLED, true)
            putExtra(DetectionForegroundService.EXTRA_SESSION_ID, sessionId)
        }
    )

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for the alert UI", condition())
    }

    private fun withFixturePreferences(block: () -> Unit) {
        val prefs = context.getSharedPreferences("nsnd_prefs", 0)
        val oldServer = prefs.getString("server_ip", null)
        val oldToken = prefs.getString("access_token", null)
        val oldSession = prefs.getInt("active_session_id", -1)
        try {
            prefs.edit().putString("server_ip", "127.0.0.1").putString("access_token", "fixture-token")
                .putInt("active_session_id", sessionId).commit()
            block()
        } finally {
            val editor = prefs.edit()
            if (oldServer == null) editor.remove("server_ip") else editor.putString("server_ip", oldServer)
            if (oldToken == null) editor.remove("access_token") else editor.putString("access_token", oldToken)
            if (oldSession == -1) editor.remove("active_session_id") else editor.putInt("active_session_id", oldSession)
            editor.commit()
        }
    }

    /** A one-request HTTP fixture inside the emulator, never the real backend. */
    private class HttpFixture(status: Int, response: String) : java.io.Closeable {
        private val socket = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress("127.0.0.1", 8000))
        }
        val requestReceived = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val request = AtomicReference("")
        private val worker = Thread {
            runCatching {
                socket.accept().use { client ->
                    client.soTimeout = 5000
                    val reader = client.getInputStream().bufferedReader()
                    val headers = StringBuilder()
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers.append(line).append('\n')
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = reader.read(body, offset, length - offset)
                        if (count < 0) break
                        offset += count
                    }
                    request.set(headers.toString() + String(body))
                    requestReceived.countDown()
                    releaseResponse.await(5, TimeUnit.SECONDS)
                    val bytes = response.toByteArray()
                    val head = "HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().write(head.toByteArray() + bytes)
                    client.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            releaseResponse.countDown()
            socket.close()
            worker.join(1000)
        }
    }
}
