package com.jsm.nsnd.data.local

import android.content.Intent
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.jsm.nsnd.R
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.service.DetectionForegroundService
import com.jsm.nsnd.ui.main.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import org.hamcrest.Matchers.*
import org.hamcrest.Matcher
import android.view.View
import android.widget.DatePicker
import android.webkit.WebView
import java.util.Calendar

/** A real service/WebSocket/UI test against an in-emulator fixture, never the user's server. */
@RunWith(AndroidJUnit4::class)
class DetectionServiceIntegrationTest {
    @Test fun serviceRestartRestoresUnstoppedAlarmBeforeReceivingAnotherFrame() {
        withAlarmFixture { fixture, scenario ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val risk = java.util.UUID.randomUUID().toString()
            fixture.send(riskFrame(risk))
            await { DetectionForegroundService.alarmUiState.sounding }
            context.stopService(Intent(context,DetectionForegroundService::class.java))
            await { !DetectionForegroundService.alarmUiState.sounding && DetectionForegroundService.latestDetection == null }
            scenario.onActivity { activity -> ContextCompat.startForegroundService(activity,
                Intent(activity,DetectionForegroundService::class.java).apply {
                    action=DetectionForegroundService.ACTION_START
                    putExtra(DetectionForegroundService.EXTRA_SESSION_ID,55111)
                }) }
            await { DetectionForegroundService.alarmUiState.sounding && fixture.connections.get() >= 2 }
            assertNull(DetectionForegroundService.latestDetection)
            DetectionForegroundService.requestAlarmStop(context,55111,risk)
            await { !DetectionForegroundService.alarmUiState.sounding }
            val account = SessionManager(context).getDetectionAccountKey()!!
            assertNull(AlarmStateStore.activeAlarm(context,account,55111))
            fixture.send(riskFrame(risk))
            await { DetectionForegroundService.latestDetection != null }
            assertFalse(DetectionForegroundService.alarmUiState.sounding)
        }
    }

    @Test fun alarmSurvivesScreenDestructionMissingFaceAndNormalFrameUntilExplicitStop() {
        withAlarmFixture { fixture, scenario ->
            val risk = java.util.UUID.randomUUID().toString()
            fixture.send(riskFrame(risk))
            await { DetectionForegroundService.alarmUiState.sounding }
            scenario.recreate()
            assertTrue(DetectionForegroundService.alarmUiState.sounding)
            scenario.close()
            assertTrue(DetectionForegroundService.alarmUiState.sounding)
            fixture.send("""{"drowsy_level":3,"observation_valid":false,"state":"NO_FACE","risk_id":"$risk"}""")
            await { DetectionForegroundService.latestDetection?.sample?.state == "NO_FACE" }
            assertTrue(DetectionForegroundService.alarmUiState.sounding)
            fixture.send("""{"drowsy_level":0,"observation_valid":true,"state":"AWAKE"}""")
            await { DetectionForegroundService.latestDetection?.stage == 0 }
            assertTrue(DetectionForegroundService.alarmUiState.sounding)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            DetectionForegroundService.requestAlarmStop(context,55111,risk)
            await { !DetectionForegroundService.alarmUiState.sounding }
            assertTrue(fixture.acknowledgements.isEmpty())
            val account = SessionManager(context).getDetectionAccountKey()!!
            assertTrue(SmsHistoryStore.history(context,account).isEmpty()) // This fixture has no registered recipients; no SMS can be sent.
        }
    }

    @Test fun oneTapStopsAlarmClosesScreenAndServiceRetriesAckAndRejectsStaleStopCommands() {
        withAlarmFixture { fixture, scenario ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val first = java.util.UUID.randomUUID().toString()
            fixture.ackStatus.set(503)
            fixture.send(riskFrame(first))
            await { DetectionForegroundService.alarmUiState.sounding }
            scenario.onActivity { it.findViewById<View>(R.id.btnDismiss).performClick() }
            await { !DetectionForegroundService.alarmUiState.sounding && DetectionForegroundService.alarmUiState.ackPending }
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            await { fixture.acknowledgements.size >= 2 }
            assertTrue(fixture.acknowledgements.first().contains("Bearer test."))
            assertTrue(fixture.acknowledgements.first().contains("\"risk_id\":\"$first\""))
            fixture.send(riskFrame(first,acknowledged=true))
            await { !DetectionForegroundService.alarmUiState.ackPending }
            val next = java.util.UUID.randomUUID().toString()
            fixture.send(riskFrame(next))
            await { DetectionForegroundService.alarmUiState.riskId == next && DetectionForegroundService.alarmUiState.sounding }
            DetectionForegroundService.requestAlarmStop(context,55111,first)
            await { DetectionForegroundService.alarmUiState.error != null }
            assertTrue(DetectionForegroundService.alarmUiState.sounding)
            fixture.ackStatus.set(409)
            DetectionForegroundService.requestAlarmStop(context,55111,next)
            await { !DetectionForegroundService.alarmUiState.sounding &&
                !DetectionForegroundService.alarmUiState.ackPending && DetectionForegroundService.alarmUiState.error != null }
            val attempts = fixture.acknowledgements.size
            repeat(10) { fixture.send(riskFrame(next)) }
            android.os.SystemClock.sleep(2500)
            assertEquals(attempts,fixture.acknowledgements.size)
        }
    }

    private fun riskFrame(risk: String, acknowledged: Boolean = false) =
        """{"drowsy_level":3,"observation_valid":true,"alert_active":true,"alarm_acknowledged":$acknowledged,"risk_id":"$risk","episode_id":"$risk"}"""

    private fun withAlarmFixture(block: (FixtureServer, ActivityScenario<com.jsm.nsnd.ui.overlay.AlertOverlayActivity>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("nsnd_prefs",0)
        val saved = prefs.all.toMap()
        val animator = android.provider.Settings.Global.getString(context.contentResolver,"animator_duration_scale") ?: "1"
        val field = LocalDetectionStore::class.java.getDeclaredField("instance").apply { isAccessible=true }
        val previous = field.get(null)
        val name = "alarm-service-${java.util.UUID.randomUUID()}.db"
        val store = LocalDetectionStore.isolated(context,name)
        val fixture = FixtureServer()
        var scenario: ActivityScenario<com.jsm.nsnd.ui.overlay.AlertOverlayActivity>? = null
        try {
            com.jsm.nsnd.service.RecordSyncService.cancel(context)
            instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale 0").close()
            field.set(null,store)
            val payload = Base64.encodeToString("""{"sub":"99111"}""".toByteArray(),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            prefs.edit().clear().putString("server_ip","127.0.0.1").putString("access_token","test.$payload.fixture")
                .putInt("active_session_id",55111).commit()
            fixture.start()
            scenario = ActivityScenario.launch(Intent(context,com.jsm.nsnd.ui.overlay.AlertOverlayActivity::class.java).apply {
                putExtra(DetectionForegroundService.EXTRA_SESSION_ID,55111)
            })
            scenario.onActivity { activity -> ContextCompat.startForegroundService(activity,
                Intent(activity,DetectionForegroundService::class.java).apply {
                    action=DetectionForegroundService.ACTION_START
                    putExtra(DetectionForegroundService.EXTRA_SESSION_ID,55111)
                }) }
            await { fixture.connections.get() >= 1 }
            block(fixture,scenario)
        } finally {
            com.jsm.nsnd.service.RecordSyncService.cancel(context)
            context.stopService(Intent(context,DetectionForegroundService::class.java))
            await { !DetectionForegroundService.alarmUiState.sounding && DetectionForegroundService.latestDetection == null }
            scenario?.close()
            instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<com.jsm.nsnd.ui.overlay.AlertOverlayActivity>().forEach { it.finish() }
            }
            fixture.close()
            instrumentation.waitForIdleSync()
            field.set(null,previous)
            store.close(); context.deleteDatabase(name)
            val editor = prefs.edit().clear()
            for ((key,value) in saved) when (value) {
                is String -> editor.putString(key,value)
                is Int -> editor.putInt(key,value)
                is Long -> editor.putLong(key,value)
                is Boolean -> editor.putBoolean(key,value)
                is Float -> editor.putFloat(key,value)
            }
            editor.commit()
            instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale $animator").close()
        }
    }

    @Test fun v6InvalidObservationsAreNotSavedAndCameraErrorsDoNotReconnect(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("nsnd_prefs", 0)
        val oldServer = prefs.getString("server_ip", null)
        val oldToken = prefs.getString("access_token", null)
        val oldSession = prefs.getInt("active_session_id", -1)
        val oldAnimatorScale = android.provider.Settings.Global.getString(context.contentResolver, "animator_duration_scale") ?: "1"
        val field = LocalDetectionStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousStore = field.get(null)
        val store = LocalDetectionStore.isolated(context, "isolated_v6_detection.db")
        val fixture = FixtureServer()
        var scenario: ActivityScenario<com.jsm.nsnd.ui.overlay.AlertOverlayActivity>? = null
        try {
            instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale 0").close()
            android.os.SystemClock.sleep(250)
            field.set(null, store)
            val payload = Base64.encodeToString("{\"sub\":\"99111\"}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            prefs.edit().putString("server_ip", "127.0.0.1").putString("access_token", "test.$payload.fixture")
                .putInt("active_session_id", 55111).commit()
            val account = SessionManager(context).getDetectionAccountKey()!!
            fixture.start()
            scenario = ActivityScenario.launch(Intent(context, com.jsm.nsnd.ui.overlay.AlertOverlayActivity::class.java).apply {
                putExtra(com.jsm.nsnd.ui.overlay.AlertOverlayActivity.EXTRA_STAGE, 1)
                putExtra(com.jsm.nsnd.ui.overlay.AlertOverlayActivity.EXTRA_SMS_HANDLED, true)
                putExtra(DetectionForegroundService.EXTRA_SESSION_ID, 55111)
            })
            ContextCompat.startForegroundService(context, Intent(context, DetectionForegroundService::class.java).apply {
                action = DetectionForegroundService.ACTION_START
                putExtra(DetectionForegroundService.EXTRA_SESSION_ID, 55111)
            })
            await { fixture.connections.get() == 1 }
            fixture.send("{\"drowsy_level\":0,\"state\":\"CALIBRATING\",\"observation_valid\":false,\"calibration_progress\":0.5}")
            await { DetectionForegroundService.latestDetection?.sample?.state == "CALIBRATING" }
            fixture.send("{\"drowsy_level\":3,\"state\":\"NO_FACE\",\"observation_valid\":false,\"alert_active\":false}")
            await { DetectionForegroundService.latestDetection?.sample?.state == "NO_FACE" }
            assertTrue(store.query(account).isEmpty())
            assertFalse(DetectionForegroundService.latestDetection!!.alertRequired)
            fixture.send("{\"drowsy_level\":1,\"ear_value\":0.21}")
            await { runBlocking { store.query(account).size == 1 } }
            fixture.send("{\"state\":\"ERROR\",\"error\":\"fixture camera unavailable\"}")
            await { DetectionForegroundService.latestFailure == "fixture camera unavailable" }
            android.os.SystemClock.sleep(2500)
            assertEquals(1, fixture.connections.get())
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertTrue(it.findViewById<android.widget.TextView>(R.id.tvAlertDescription).text.contains("fixture camera unavailable"))
            }
        } finally {
            com.jsm.nsnd.service.RecordSyncService.cancel(context)
            context.stopService(Intent(context, DetectionForegroundService::class.java))
            await { DetectionForegroundService.latestDetection == null }
            scenario?.close()
            fixture.close()
            field.set(null, previousStore)
            store.close()
            context.deleteDatabase("isolated_v6_detection.db")
            val editor = prefs.edit()
            if (oldServer == null) editor.remove("server_ip") else editor.putString("server_ip", oldServer)
            if (oldToken == null) editor.remove("access_token") else editor.putString("access_token", oldToken)
            if (oldSession == -1) editor.remove("active_session_id") else editor.putInt("active_session_id", oldSession)
            editor.commit()
            instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale $oldAnimatorScale").close()
        }
    }

    @Test fun serviceStoresOutsideHomeReconnectsAndMapTabReturnsToSleep() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("nsnd_prefs", 0)
        val savedPreferences = prefs.all.toMap()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousLocationMode = android.provider.Settings.Secure.getInt(context.contentResolver,
            android.provider.Settings.Secure.LOCATION_MODE, 0)
        val field = LocalDetectionStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousStore = field.get(null)
        val testContext = context
        val name = "isolated_service_integration.db"
        testContext.deleteDatabase(name)
        val store = LocalDetectionStore.isolated(testContext, name)
        var scenario: ActivityScenario<MainActivity>? = null
        val fixture = FixtureServer()
        try {
            automation.executeShellCommand("settings put secure location_mode 0").close()
            await {
                val manager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                return@await !LocationManagerCompat.isLocationEnabled(manager)
            }
            field.set(null, store)
            val payload = Base64.encodeToString("{\"sub\":\"99101\"}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            prefs.edit().clear().putString("server_ip", "127.0.0.1")
                .putString("access_token", "test.$payload.fixture").putInt("active_session_id", 55101).commit()
            val account = SessionManager(context).getDetectionAccountKey()!!
            fixture.start()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.sleepDataFragment
                ContextCompat.startForegroundService(activity, Intent(activity, DetectionForegroundService::class.java).apply {
                    action = DetectionForegroundService.ACTION_START
                    putExtra(DetectionForegroundService.EXTRA_SESSION_ID, 55101)
                })
            }
            await { fixture.connections.get() >= 1 }
            fixture.send("{\"ping\":true}")
            fixture.send("{\"drowsy_level\":1,\"ear_value\":0.21,\"pred_score\":0.65}")
            await { runBlocking { store.query(account).size == 1 } }
            dismissFixtureAlert()
            assertEquals(.21, store.query(account).first().ear!!, .0001)
            assertNull(store.query(account).first().latitude)
            fixture.disconnect()
            await { fixture.connections.get() >= 2 }
            fixture.send("{\"drowsy_level\":1,\"ear_value\":0.21,\"pred_score\":0.65}")
            fixture.send("{\"drowsy_level\":0,\"ear_value\":0.3,\"pred_score\":0.1}")
            fixture.send("{\"drowsy_level\":2,\"ear_value\":0.19,\"pred_score\":0.8}")
            await { runBlocking { store.query(account).size == 2 } }
            dismissFixtureAlert()
            assertEquals(setOf(1, 2), store.query(account).map { it.stage }.toSet())

            // Add A/B plus overlapping B records to the isolated fixture store, separately from live GPS.
            val now = System.currentTimeMillis()
            store.recordTransition(account, 99101, 55102, DetectionSample(1, .2, .7), now,
                LocationSnapshot(37.4, 127.1, now, 5f, "available"))
            store.recordTransition(account, 99101, 55102, DetectionSample(2, .18, .8), now + 1,
                LocationSnapshot(37.5, 127.2, now, 5f, "available"))
            store.recordTransition(account, 99101, 55102, DetectionSample(3, .17, .9), now + 2,
                LocationSnapshot(37.5, 127.2, now, 5f, "available"))
            scenario.onActivity { activity ->
                assertEquals(R.id.sleepDataFragment, controller(activity).currentDestination?.id)
                assertTrue(activity.findViewById<android.view.View>(R.id.btnOpenFullMap).performClick())
            }
            await {
                var ready = false
                scenario.onActivity { activity ->
                    ready = controller(activity).currentDestination?.id == R.id.mapFragment &&
                        activity.findViewById<android.widget.TextView>(R.id.tvTotalCount)?.text?.contains("5건") == true
                }
                ready
            }
            scenario.onActivity { activity ->
                val fragment = currentFragment(activity)
                val records = fragment.javaClass.getDeclaredField("locationEvents").apply { isAccessible = true }.get(fragment) as List<*>
                assertEquals(3, records.size)
            }
            // This emulator uses the real Kakao WebView fallback, including its actual marker objects.
            await(20_000) { javascript(scenario, "typeof markers !== 'undefined' ? markers.length : -1") == "3" }
            val positions = javascript(scenario,
                "JSON.stringify(markers.map(function(m){return [m.getPosition().getLat(),m.getPosition().getLng()];}))")
            val points = org.json.JSONArray(org.json.JSONArray("[$positions]").getString(0))
            fun containsPoint(lat: Double, lng: Double) = (0 until points.length()).any {
                val point = points.getJSONArray(it)
                kotlin.math.abs(point.getDouble(0) - lat) < .000001 && kotlin.math.abs(point.getDouble(1) - lng) < .000001
            }
            assertTrue("A marker: $positions", containsPoint(37.4, 127.1))
            assertTrue("B marker: $positions", containsPoint(37.5, 127.2))
            javascript(scenario, "kakao.maps.event.trigger(markers[0], 'click'); true;")
            onView(withText("로컬 기록 2건 · 앱 수신 시각")).check(matches(isDisplayed()))
            onData(anything()).inAdapterView(isAssignableFrom(android.widget.ListView::class.java)).atPosition(0).perform(click())
            onView(withText(containsString("EAR: 0.17"))).check(matches(isDisplayed()))
            onView(withId(android.R.id.button1)).perform(click())

            // Select tomorrow: no receipt-time records should remain; all-mode restores the five.
            scenario.onActivity { it.findViewById<View>(R.id.btnFilterDate).performClick() }
            val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
            onView(isAssignableFrom(DatePicker::class.java)).perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
                override fun getDescription() = "날짜 필터를 내일로 선택"
                override fun perform(uiController: UiController, view: View) {
                    (view as DatePicker).updateDate(tomorrow.get(Calendar.YEAR), tomorrow.get(Calendar.MONTH), tomorrow.get(Calendar.DAY_OF_MONTH))
                }
            })
            onView(withId(android.R.id.button1)).perform(click())
            await {
                var empty = false
                scenario.onActivity { empty = it.findViewById<android.widget.TextView>(R.id.tvTotalCount)?.text?.contains("0건") == true }
                empty
            }
            await { javascript(scenario, "markers.length") == "0" }
            scenario.onActivity { it.findViewById<View>(R.id.btnFilterAll).performClick() }
            await { javascript(scenario, "markers.length") == "3" }

            // Legacy server history remains available without copying into the local database.
            scenario.onActivity { it.findViewById<android.widget.Spinner>(R.id.spinnerRecordSource).setSelection(1) }
            await {
                var loaded = false
                scenario.onActivity { loaded = it.findViewById<android.widget.TextView>(R.id.tvTotalCount)?.text?.startsWith("서버 4건") == true }
                loaded
            }
            await { javascript(scenario, "markers.length") == "3" }
            assertEquals(5, store.query(account).size)
            scenario.onActivity { it.findViewById<View>(R.id.btnFilterDate).performClick() }
            chooseDate(2026, Calendar.SEPTEMBER, 20)
            await { javascript(scenario, "markers.length") == "3" }
            javascript(scenario, "kakao.maps.event.trigger(markers[0], 'click'); true;")
            val localServerTime = java.time.LocalDateTime.parse("2026-09-20T14:15:30")
                .atOffset(java.time.ZoneOffset.UTC).atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"))
            onView(withText(containsString("서버 기록 시각: $localServerTime"))).check(matches(isDisplayed()))
            onView(withId(android.R.id.button1)).perform(click())
            scenario.onActivity { it.findViewById<android.widget.Spinner>(R.id.spinnerRecordSource).setSelection(0) }
            await { javascript(scenario, "markers.length") == "0" }
            assertEquals(5, store.query(account).size)
            scenario.onActivity { activity ->
                activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.sleepDataFragment
            }
            await {
                var returned = false
                scenario.onActivity { returned = controller(it).currentDestination?.id == R.id.sleepDataFragment }
                returned
            }
            scenario.onActivity { it.findViewById<View>(R.id.btnCalendar).performClick() }
            chooseDate(2026, Calendar.SEPTEMBER, 20)
            scenario.onActivity { it.findViewById<android.widget.Spinner>(R.id.spinnerRecordSource).setSelection(1) }
            await {
                var loaded = false
                scenario.onActivity { loaded = it.findViewById<android.widget.TextView>(R.id.tvMapEventCount)?.text == "서버 위치 3건" }
                loaded
            }
            await { javascript(scenario, "markers.length") == "3" }
            scenario.onActivity { it.findViewById<View>(R.id.btnOpenFullMap).performClick() }
            await {
                var correct = false
                scenario.onActivity { activity ->
                    correct = activity.findViewById<android.widget.TextView>(R.id.tvMapDate)?.text == "2026.09.20" &&
                        activity.findViewById<android.widget.Spinner>(R.id.spinnerRecordSource)?.selectedItemPosition == 1 &&
                        activity.findViewById<android.widget.TextView>(R.id.tvTotalCount)?.text?.startsWith("서버 4건") == true
                }
                correct
            }
            await { javascript(scenario, "markers.length") == "3" }
            scenario.onActivity { it.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.sleepDataFragment }
            // Stop/restart the actual service; the checkpoint prevents replay of stage 2.
            com.jsm.nsnd.service.RecordSyncService.cancel(context)
            context.stopService(Intent(context, DetectionForegroundService::class.java))
            Thread.sleep(300)
            val connections = fixture.connections.get()
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity, Intent(activity, DetectionForegroundService::class.java).apply {
                    action = DetectionForegroundService.ACTION_START
                    putExtra(DetectionForegroundService.EXTRA_SESSION_ID, 55101)
                })
            }
            await { fixture.connections.get() > connections }
            fixture.send("{\"drowsy_level\":2,\"ear_value\":0.19,\"pred_score\":0.8}")
            fixture.send("{\"drowsy_level\":0}")
            fixture.send("{\"drowsy_level\":1,\"ear_value\":0.2,\"pred_score\":0.7}")
            await { runBlocking { store.query(account, sessionId = 55101).size == 3 } }
            dismissFixtureAlert()
            assertEquals(6, store.query(account).size)
        } finally {
            com.jsm.nsnd.service.RecordSyncService.cancel(context)
            context.stopService(Intent(context, DetectionForegroundService::class.java))
            scenario?.close()
            fixture.close()
            Thread.sleep(300)
            field.set(null, previousStore)
            store.close()
            testContext.deleteDatabase(name)
            val editor = prefs.edit().clear()
            savedPreferences.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
                }
            }
            editor.commit()
            automation.executeShellCommand("settings put secure location_mode $previousLocationMode").close()
        }
    }

    private fun dismissFixtureAlert() {
        await { com.jsm.nsnd.ui.overlay.AlertOverlayActivity.isOpen }
        DetectionForegroundService.requestAlarmStop(InstrumentationRegistry.getInstrumentation().targetContext,
            55101,DetectionForegroundService.alarmUiState.riskId)
        await { !DetectionForegroundService.alarmUiState.sounding }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<com.jsm.nsnd.ui.overlay.AlertOverlayActivity>().forEach { it.finish() }
        }
        await { !com.jsm.nsnd.ui.overlay.AlertOverlayActivity.isOpen }
    }

    private fun controller(activity: MainActivity) = (activity.supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment).navController
    private fun chooseDate(year: Int, month: Int, day: Int) {
        onView(isAssignableFrom(DatePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
            override fun getDescription() = "서버 과거 기록 날짜 선택"
            override fun perform(uiController: UiController, view: View) { (view as DatePicker).updateDate(year, month, day) }
        })
        onView(withId(android.R.id.button1)).perform(click())
    }
    private fun javascript(scenario: ActivityScenario<MainActivity>, script: String): String {
        val result = AtomicReference("null")
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.mapWebView)
            if (webView == null) done.countDown()
            else webView.evaluateJavascript(script) { result.set(it); done.countDown() }
        }
        done.await(3, TimeUnit.SECONDS)
        return result.get()
    }
    private fun currentFragment(activity: MainActivity): Fragment =
        (activity.supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment).childFragmentManager.primaryNavigationFragment!!
    private fun await(timeout: Long = 12_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!predicate()) {
            if (System.currentTimeMillis() >= deadline) fail("Timed out waiting for service/UI fixture")
            Thread.sleep(50)
        }
    }

    private class FixtureServer {
        private val server = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress("127.0.0.1", 8000))
        }
        private val executor = Executors.newCachedThreadPool()
        @Volatile private var socket: Socket? = null
        val connections = AtomicInteger()
        val ackStatus = AtomicInteger(202)
        val acknowledgements = java.util.concurrent.CopyOnWriteArrayList<String>()
        fun start() { executor.execute {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                executor.execute {
                    runCatching {
                        client.soTimeout = 5_000
                        val reader = client.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        if (request.contains("/detection/ws/")) {
                            val key = headers["sec-websocket-key"]!!
                            val hash = MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                            val accept = Base64.encodeToString(hash, Base64.NO_WRAP)
                            client.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                            client.getOutputStream().flush()
                            socket = client
                            connections.incrementAndGet()
                        } else {
                            val length = headers["content-length"]?.toIntOrNull() ?: 0
                            val requestBody = CharArray(length)
                            var offset = 0
                            while (offset < length) {
                                val read = reader.read(requestBody,offset,length-offset)
                                if (read < 0) break
                                offset += read
                            }
                            val isAck = request.contains("/detection/alarm/acknowledge")
                            if (isAck) acknowledgements.add(headers["authorization"].orEmpty()+"\n"+String(requestBody))
                            val body = when {
                                isAck -> """{"session_id":55111,"status":"accepted","message":"fixture"}"""
                                request.contains("/contacts/sms-settings") -> """{"enabled":false,"contact_count":0,"contacts":[]}"""
                                request.contains("/detection/events") -> """{"next_cursor":0,"has_more":false,"events":[]}"""
                                request.contains("/report/by-date?date=2026-09-20") || request.contains("/report/detail/55103") -> legacyReport()
                                request.contains("/report/history") -> """{"total_sessions":1,"sessions":[{"session_id":55103,"started_at":"2026-09-20T14:00:00","ended_at":"2026-09-20T15:00:00","duration_minutes":60,"safety_score":80,"grade":"caution","total_drowsy_count":4}]}"""
                                else -> "{\"detail\":\"isolated test fixture\"}"
                            }
                            val status = if (isAck) "${ackStatus.get()} Fixture"
                                else if (request.contains("/contacts/sms-settings") || request.contains("/detection/events")) "200 OK"
                                else if (request.contains("/report/") && body.contains("events") || request.contains("/report/history"))
                                "200 OK" else "503 Service Unavailable"
                            client.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                            client.close()
                        }
                    }
                }
            }
        } }
        fun send(json: String) {
            val bytes = json.toByteArray()
            require(bytes.size < 65536)
            val out = socket!!.getOutputStream()
            synchronized(out) {
                out.write(0x81)
                if (bytes.size < 126) out.write(bytes.size)
                else { out.write(126); out.write(bytes.size shr 8); out.write(bytes.size and 255) }
                out.write(bytes); out.flush()
            }
        }
        private fun legacyReport() = """{"date":"2026-09-20","session_id":55103,"session_count":1,"started_at":"2026-09-20T14:00:00","ended_at":"2026-09-20T15:00:00","duration_minutes":60,"safety_score":80,"grade":"caution","total_drowsy_count":4,"level1_count":2,"level2_count":1,"level3_count":1,"most_dangerous_time":null,"chart_data":[],"events":[{"timestamp":"2026-09-20T14:15:30","drowsy_level":1,"latitude":37.3948,"longitude":127.1119},{"timestamp":"2026-09-20T14:32:10","drowsy_level":2,"latitude":37.3664,"longitude":127.1082},{"timestamp":"2026-09-20T14:48:50","drowsy_level":3,"latitude":37.3501,"longitude":127.1065},{"timestamp":"2026-09-20T14:50:00","drowsy_level":1,"latitude":null,"longitude":null}]}"""
        fun disconnect() { socket?.close(); socket = null }
        fun close() { disconnect(); server.close(); executor.shutdownNow() }
    }
}
