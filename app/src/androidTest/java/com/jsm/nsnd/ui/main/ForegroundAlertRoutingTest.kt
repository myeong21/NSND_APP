package com.jsm.nsnd.ui.main

import android.content.Intent
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.jsm.nsnd.R
import com.jsm.nsnd.service.DetectionForegroundService
import com.jsm.nsnd.ui.overlay.AlertOverlayActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Synthetic level-one alerts only: no camera, location injection or actual SMS. */
@RunWith(AndroidJUnit4::class)
class ForegroundAlertRoutingTest {
    @Test fun everyBottomTabRoutesAnAlertAndRejectsAStaleSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("nsnd_prefs", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = ServerSocket(8000) // Fail instead of interfering if another fixture owns the port.
        var scenario: ActivityScenario<MainActivity>? = null
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(" ")?.getOrNull(1).orEmpty()
                        requests.add(path)
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val body = when {
                            path == "/auth/me" -> """{"id":99101,"username":"fixture","name":"Fixture","emergency_contact":null}"""
                            path == "/health" -> """{"status":"not_ready","models_ready":false,"camera_available":false}"""
                            path == "/sessions/current" -> """{"id":55101,"user_id":99101,"started_at":"2026-10-14T00:00:00Z","ended_at":null}"""
                            path.startsWith("/detection/status/") -> """{"session_id":55101,"is_running":false}"""
                            path == "/contacts" -> "[]"
                            path == "/report/history" -> """{"total_sessions":0,"sessions":[]}"""
                            else -> """{"detail":"fixture has no report"}"""
                        }
                        val status = if (path.startsWith("/report/by-date")) "404 Not Found" else "200 OK"
                        val bytes = body.toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                } catch (_: java.io.IOException) { }
            }
        }
        fun broadcast(session: Int) {
            context.sendBroadcast(Intent(DetectionForegroundService.ACTION_DETECTION_EVENT).apply {
                setPackage(context.packageName)
                putExtra(DetectionForegroundService.EXTRA_SESSION_ID, session)
                putExtra(DetectionForegroundService.EXTRA_ALERT_REQUIRED, true)
                putExtra(DetectionForegroundService.EXTRA_PAYLOAD, """{"drowsy_level":1,"observation_valid":true,"alert_active":true}""")
            })
        }
        try {
            val payload = Base64.encodeToString("""{"sub":"99101","account_key":"${"a".repeat(64)}"}""".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            prefs.edit().putString("access_token", "fixture.$payload.signature")
                .putString("server_ip", "127.0.0.1").putInt("active_session_id", 55101).commit()
            for (permission in listOf(android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_PHONE_STATE)) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} $permission")).use { it.readBytes() }
            }
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            scenario.moveToState(Lifecycle.State.RESUMED)
            val connectionDeadline = SystemClock.elapsedRealtime() + 5_000
            var connected = false
            while (!connected && SystemClock.elapsedRealtime() < connectionDeadline) {
                scenario.onActivity {
                    connected = it.findViewById<android.widget.TextView>(R.id.tvSystemAvailability).text.toString() == "서버 연결됨"
                }
                if (!connected) SystemClock.sleep(25)
            }
            assertTrue("home must check connectivity without probing the camera", connected)
            for (tab in listOf(R.id.homeFragment, R.id.contactFragment, R.id.massagerFragment, R.id.sleepDataFragment, R.id.mapFragment)) {
                scenario.onActivity { it.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = tab }
                broadcast(55102)
                SystemClock.sleep(150)
                assertFalse("stale session must not open an alert", AlertOverlayActivity.isOpen)
                broadcast(55101)
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (!AlertOverlayActivity.isOpen && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
                assertTrue("tab $tab must open its alert", AlertOverlayActivity.isOpen)
                instrumentation.runOnMainSync {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<AlertOverlayActivity>().forEach { it.findViewById<View>(R.id.btnDismiss).performClick() }
                }
                val closeDeadline = SystemClock.elapsedRealtime() + 5_000
                while (AlertOverlayActivity.isOpen && SystemClock.elapsedRealtime() < closeDeadline) SystemClock.sleep(25)
                assertFalse(AlertOverlayActivity.isOpen)
            }
            scenario.onActivity { it.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.homeFragment }
            // A fresh server check still recovers the session and never touches /health.
            val previousChecks = requests.count { it == "/sessions/current" }
            val refreshDeadline = SystemClock.elapsedRealtime() + 5_000
            while (requests.count { it == "/sessions/current" } <= previousChecks && SystemClock.elapsedRealtime() < refreshDeadline) {
                scenario.onActivity { it.findViewById<View>(R.id.btnRefreshServer).performClick() }
                SystemClock.sleep(50)
            }
            assertTrue("session recovery must remain active", requests.count { it == "/sessions/current" } > previousChecks)
            assertFalse("home visits and refresh must not open the server camera", requests.any { it == "/health" })
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<AlertOverlayActivity>().forEach { it.finish() }
            }
            scenario?.close()
            server.close()
            worker.join(1_000)
            val editor = prefs.edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            editor.commit()
        }
    }
}
