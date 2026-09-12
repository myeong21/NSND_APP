package com.jsm.nsnd.service

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jsm.nsnd.R
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.ui.contact.ContactLocalStore
import com.jsm.nsnd.ui.overlay.AlertOverlayActivity
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** 홈 화면과 독립적으로 실시간 감지 연결과 위험 경보를 유지합니다. */
class DetectionForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private var sessionId = -1
    private var shouldRun = false
    private var retryCount = 0
    private var lastBackgroundAlertStage = 0
    private var lastBackgroundAlertAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopConnection()
            ACTION_START, null -> {
                sessionId = intent?.getIntExtra(EXTRA_SESSION_ID, -1)
                    ?.takeIf { it > 0 }
                    ?: SessionManager(this).getActiveSessionId()
                if (sessionId <= 0) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                shouldRun = true
                startForeground(NOTIFICATION_ID, buildStatusNotification("실시간 감지 연결 중"))
                connect()
            }
        }
        return START_STICKY
    }

    private fun connect() {
        if (!shouldRun || sessionId <= 0 || webSocket != null) return
        val token = SessionManager(this).getToken().orEmpty()
        if (token.isBlank()) {
            broadcastConnection(STATE_AUTH_EXPIRED, "로그인이 만료되었습니다")
            stopConnection()
            return
        }

        val request = okhttp3.Request.Builder()
            .url("${RetrofitClient.wsBaseUrl(this)}/detection/ws/$sessionId")
            .header("Authorization", "Bearer $token")
            .build()
        webSocket = RetrofitClient.okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryCount = 0
                broadcastConnection(STATE_CONNECTED, null)
                updateStatusNotification("실시간 감지 작동 중")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (json.optBoolean("ping", false)) return
                val stage = json.optInt("drowsy_level", 0)
                sendBroadcast(Intent(ACTION_DETECTION_EVENT).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PAYLOAD, text)
                })
                if (!isAppForeground() && stage > 0) showBackgroundAlert(stage)
                if (stage == 0) lastBackgroundAlertStage = 0
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@DetectionForegroundService.webSocket = null
                if (response?.code == 401) {
                    broadcastConnection(STATE_AUTH_EXPIRED, "로그인이 만료되었습니다")
                    stopConnection()
                } else {
                    scheduleReconnect(t.message ?: "실시간 연결이 끊어졌습니다")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@DetectionForegroundService.webSocket = null
                when (code) {
                    4001 -> {
                        broadcastConnection(STATE_AUTH_EXPIRED, reason)
                        stopConnection()
                    }
                    4004 -> {
                        broadcastConnection(STATE_SESSION_ENDED, reason)
                        stopConnection()
                    }
                    else -> if (shouldRun) scheduleReconnect(reason)
                }
            }
        })
    }

    private fun scheduleReconnect(reason: String) {
        if (!shouldRun) return
        retryCount++
        val delay = minOf(30_000L, 2_000L * retryCount)
        broadcastConnection(STATE_RECONNECTING, reason)
        updateStatusNotification("연결 재시도 중")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ connect() }, delay)
    }

    private fun showBackgroundAlert(stage: Int) {
        val now = System.currentTimeMillis()
        if (stage == lastBackgroundAlertStage && now - lastBackgroundAlertAt < ALERT_COOLDOWN_MS) return
        lastBackgroundAlertStage = stage
        lastBackgroundAlertAt = now

        val fullScreenIntent = Intent(this, AlertOverlayActivity::class.java).apply {
            putExtra(AlertOverlayActivity.EXTRA_STAGE, stage)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            100 + stage,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_warning_triangle)
            .setContentTitle("졸음 $stage 단계 감지")
            .setContentText(if (stage >= 3) "즉시 안전한 곳에서 휴식하세요." else "운전자 상태를 확인해주세요.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID + stage, notification)

        if (stage >= 3) sendEmergencySms()
    }

    private fun sendEmergencySms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val smsManager = SmsManager.getDefault()
            ContactLocalStore.load(this).forEach { contact ->
                smsManager.sendTextMessage(contact.phone, null, contact.message, null, null)
            }
        }
    }

    private fun stopConnection() {
        shouldRun = false
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "감지 서비스 종료")
        webSocket = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastConnection(state: String, message: String?) {
        sendBroadcast(Intent(ACTION_CONNECTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
        })
    }

    private fun isAppForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun buildStatusNotification(text: String) = NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_status_online)
        .setContentTitle("NSND 시스템 작동 중")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun updateStatusNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildStatusNotification(text))
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL_ID, "시스템 작동 상태", NotificationManager.IMPORTANCE_LOW)
        )
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "졸음 위험 경보",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 700, 250, 900)
            setSound(
                android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            )
        }
        manager.createNotificationChannel(alertChannel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.jsm.nsnd.action.START_DETECTION_SERVICE"
        const val ACTION_STOP = "com.jsm.nsnd.action.STOP_DETECTION_SERVICE"
        const val ACTION_DETECTION_EVENT = "com.jsm.nsnd.action.DETECTION_EVENT"
        const val ACTION_CONNECTION_STATE = "com.jsm.nsnd.action.DETECTION_CONNECTION"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val STATE_CONNECTED = "connected"
        const val STATE_RECONNECTING = "reconnecting"
        const val STATE_AUTH_EXPIRED = "auth_expired"
        const val STATE_SESSION_ENDED = "session_ended"
        private const val STATUS_CHANNEL_ID = "nsnd_detection_status"
        private const val ALERT_CHANNEL_ID = "nsnd_drowsy_alert"
        private const val NOTIFICATION_ID = 4100
        private const val ALERT_NOTIFICATION_ID = 4200
        private const val ALERT_COOLDOWN_MS = 10_000L
    }
}
