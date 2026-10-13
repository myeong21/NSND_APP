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
            assertTrue(label.text.toString().startsWith("${stage}단계 경보"))
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

    @Test fun reducedMotionAndRepeatedStagesKeepDismissDelayAndRecreationKeepsStage() {
        launch().use { scenario ->
            broadcast("{\"drowsy_level\":2}")
            broadcast("{\"drowsy_level\":2}")
            scenario.onActivity {
                assertNull(it.findViewById<ImageView>(R.id.ivRotatingRing).animation)
            }
            SystemClock.sleep(3100)
            broadcast("{\"drowsy_level\":3}")
            scenario.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.btnDismiss).visibility) }
            scenario.recreate()
            assertStage(scenario, 3)
            scenario.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.btnDismiss).visibility) }
        }
    }

    @Test fun normalStageClosesAlarm() {
        launch().use { scenario ->
            broadcast("{\"drowsy_level\":0}")
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            assertFalse(AlertOverlayActivity.isOpen)
        }
    }
}
