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
            fixture.send("{\"error\":\"camera failure\"}")
            fixture.send("{\"drowsy_level\":1,\"ear_value\":0.21,\"pred_score\":0.65}")
            await { runBlocking { store.query(account).size == 1 } }
            assertEquals(.21, store.query(account).first().ear!!, .0001)
            assertNull(store.query(account).first().latitude)
            fixture.disconnect()
            await { fixture.connections.get() >= 2 }
            fixture.send("{\"drowsy_level\":1,\"ear_value\":0.21,\"pred_score\":0.65}")
            fixture.send("{\"drowsy_level\":0,\"ear_value\":0.3,\"pred_score\":0.1}")
            fixture.send("{\"drowsy_level\":2,\"ear_value\":0.19,\"pred_score\":0.8}")
            await { runBlocking { store.query(account).size == 2 } }
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
            onView(withText(containsString("서버 기록 시각: 2026-09-20 14:15:30"))).check(matches(isDisplayed()))
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
            assertEquals(6, store.query(account).size)
        } finally {
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
        private val server = ServerSocket(8000)
        private val executor = Executors.newCachedThreadPool()
        @Volatile private var socket: Socket? = null
        val connections = AtomicInteger()
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
                            val body = when {
                                request.contains("/report/by-date?date=2026-09-20") || request.contains("/report/detail/55103") -> legacyReport()
                                request.contains("/report/history") -> """{"total_sessions":1,"sessions":[{"session_id":55103,"started_at":"2026-09-20T14:00:00","ended_at":"2026-09-20T15:00:00","duration_minutes":60,"safety_score":80,"grade":"caution","total_drowsy_count":4}]}"""
                                else -> "{\"detail\":\"isolated test fixture\"}"
                            }
                            val status = if (request.contains("/report/") && body.contains("events") || request.contains("/report/history"))
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
            require(bytes.size < 126)
            val out = socket!!.getOutputStream()
            synchronized(out) { out.write(0x81); out.write(bytes.size); out.write(bytes); out.flush() }
        }
        private fun legacyReport() = """{"date":"2026-09-20","session_id":55103,"session_count":1,"started_at":"2026-09-20T14:00:00","ended_at":"2026-09-20T15:00:00","duration_minutes":60,"safety_score":80,"grade":"caution","total_drowsy_count":4,"level1_count":2,"level2_count":1,"level3_count":1,"most_dangerous_time":null,"chart_data":[],"events":[{"timestamp":"2026-09-20T14:15:30","drowsy_level":1,"latitude":37.3948,"longitude":127.1119},{"timestamp":"2026-09-20T14:32:10","drowsy_level":2,"latitude":37.3664,"longitude":127.1082},{"timestamp":"2026-09-20T14:48:50","drowsy_level":3,"latitude":37.3501,"longitude":127.1065},{"timestamp":"2026-09-20T14:50:00","drowsy_level":1,"latitude":null,"longitude":null}]}"""
        fun disconnect() { socket?.close(); socket = null }
        fun close() { disconnect(); server.close(); executor.shutdownNow() }
    }
}
