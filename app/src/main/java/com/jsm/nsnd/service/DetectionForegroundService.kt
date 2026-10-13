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
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.jsm.nsnd.data.local.DetectionMessageParser
import com.jsm.nsnd.data.local.DetectionSample
import com.jsm.nsnd.data.local.LocalDetectionStore
import com.jsm.nsnd.data.local.LocationSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
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
    @Volatile private var webSocket: WebSocket? = null
    private var sessionId = -1
    @Volatile private var shouldRun = false
    private var retryCount = 0
    private var lastBackgroundAlertStage = 0
    private var lastBackgroundAlertAt = 0L
    private var ownerAccount: String? = null
    private var ownerUserId: Long? = null
    @Volatile private var connectionGeneration = 0
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class PendingRecord(val account: String, val userId: Long, val session: Int,
        val sample: DetectionSample, val receivedAtMs: Long, val location: LocationSnapshot)
    private val pendingRecords = Channel<PendingRecord>(Channel.UNLIMITED)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        storageScope.launch {
            try {
                val store = LocalDetectionStore.get(this@DetectionForegroundService)
                val processedStages = mutableMapOf<Pair<String, Int>, Int>()
                for (record in pendingRecords) {
                    val key = record.account to record.session
                    if (processedStages[key] == record.sample.stage) continue
                    runCatching {
                        store.recordTransition(record.account, record.userId, record.session, record.sample,
                            record.receivedAtMs, record.location)
                        processedStages[key] = record.sample.stage
                    }.onFailure {
                        android.util.Log.e("DetectionService", "로컬 감지 기록 저장 실패", it)
                        updateStatusNotification("감지 중 · 로컬 기록 저장 실패")
                    }
                }
            } finally { storageScope.cancel() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopConnection()
            ACTION_START, null -> {
                val manager = SessionManager(this)
                val requestedSession = intent?.getIntExtra(EXTRA_SESSION_ID, -1)
                    ?.takeIf { it > 0 }
                    ?: manager.getActiveSessionId()
                val account = manager.getDetectionAccountKey()
                if (requestedSession <= 0 || account == null || requestedSession != manager.getActiveSessionId()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (sessionId != requestedSession || ownerAccount != account) {
                    latestDetection = null
                    connectionGeneration++
                    webSocket?.cancel()
                    webSocket = null
                    handler.removeCallbacksAndMessages(null)
                }
                sessionId = requestedSession
                ownerAccount = account
                ownerUserId = manager.getUserId()
                shouldRun = true
                startMonitoringForeground()
                connect()
            }
        }
        return START_STICKY
    }

    private fun connect() {
        if (!shouldRun || sessionId <= 0 || webSocket != null) return
        val manager = SessionManager(this)
        val token = manager.getToken().orEmpty()
        if (manager.getDetectionAccountKey() != ownerAccount || manager.getActiveSessionId() != sessionId) {
            stopConnection()
            return
        }
        if (token.isBlank()) {
            broadcastConnection(STATE_AUTH_EXPIRED, "로그인이 만료되었습니다")
            stopConnection()
            return
        }

        val generation = ++connectionGeneration
        val connectedSession = sessionId
        val account = ownerAccount ?: return
        val userId = ownerUserId ?: return
        val request = okhttp3.Request.Builder()
            .url("${RetrofitClient.wsBaseUrl(this)}/detection/ws/$sessionId")
            .header("Authorization", "Bearer $token")
            .build()
        webSocket = RetrofitClient.okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != connectionGeneration || !shouldRun) { webSocket.cancel(); return }
                retryCount = 0
                broadcastConnection(STATE_CONNECTED, null)
                updateStatusNotification("실시간 감지 작동 중")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val receivedAtMs = System.currentTimeMillis()
                if (generation != connectionGeneration || !shouldRun) return
                val active = SessionManager(this@DetectionForegroundService)
                if (active.getDetectionAccountKey() != account || active.getActiveSessionId() != connectedSession) {
                    handler.post { stopConnection() }
                    return
                }
                val sample = DetectionMessageParser.parse(text) ?: return
                val stage = sample.stage
                latestDetection = LiveDetection(account, connectedSession, stage)
                pendingRecords.trySend(PendingRecord(account, userId, connectedSession, sample, receivedAtMs,
                    LocationTracker.snapshot(this@DetectionForegroundService)))
                sendBroadcast(Intent(ACTION_DETECTION_EVENT).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PAYLOAD, text)
                    putExtra(EXTRA_SESSION_ID, connectedSession)
                })
                if (!isAppForeground() && !AlertOverlayActivity.isOpen && stage > 0) showBackgroundAlert(stage)
                if (stage == 0) lastBackgroundAlertStage = 0
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != connectionGeneration || !shouldRun) return
                this@DetectionForegroundService.webSocket = null
                if (response?.code == 401) {
                    SessionManager(this@DetectionForegroundService).clear()
                    broadcastConnection(STATE_AUTH_EXPIRED, "로그인이 만료되었습니다")
                    stopConnection()
                } else {
                    scheduleReconnect(t.message ?: "실시간 연결이 끊어졌습니다")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration || !shouldRun) return
                this@DetectionForegroundService.webSocket = null
                when (code) {
                    4001 -> {
                        SessionManager(this@DetectionForegroundService).clear()
                        broadcastConnection(STATE_AUTH_EXPIRED, reason)
                        stopConnection()
                    }
                    4004 -> {
                        SessionManager(this@DetectionForegroundService).clearActiveSessionId()
                        broadcastConnection(STATE_SESSION_ENDED, reason)
                        stopConnection()
                    }
                    else -> if (shouldRun) scheduleReconnect(reason)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
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
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(AlertOverlayActivity.EXTRA_SMS_HANDLED, stage >= 3)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
        latestDetection = null
        broadcastConnection(STATE_STOPPED, null)
        connectionGeneration++
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "감지 서비스 종료")
        webSocket = null
        LocationTracker.stopTracking("detection")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startMonitoringForeground() {
        val baseType = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val canLocate = LocationTracker.hasLocationPermission(this)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildStatusNotification("실시간 감지 연결 중"),
                baseType or if (canLocate) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            if (canLocate) LocationTracker.startTracking(this, "detection")
        } catch (e: SecurityException) {
            // Background restart may lack while-in-use location eligibility. Detection still works.
            ServiceCompat.startForeground(this, NOTIFICATION_ID,
                buildStatusNotification("감지 중 · 위치 권한/앱 실행 상태 확인 필요"), baseType)
            LocationTracker.stopTracking("detection")
        }
    }

    override fun onDestroy() {
        shouldRun = false
        latestDetection = null
        broadcastConnection(STATE_STOPPED, null)
        connectionGeneration++
        handler.removeCallbacksAndMessages(null)
        webSocket?.cancel()
        webSocket = null
        LocationTracker.stopTracking("detection")
        pendingRecords.close() // Drain already received events on the IO worker.
        super.onDestroy()
    }

    private fun broadcastConnection(state: String, message: String?) {
        sendBroadcast(Intent(ACTION_CONNECTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_SESSION_ID, sessionId)
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
        data class LiveDetection(val account: String, val sessionId: Int, val stage: Int)
        @Volatile var latestDetection: LiveDetection? = null
            private set
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
        const val STATE_STOPPED = "stopped"
        private const val STATUS_CHANNEL_ID = "nsnd_detection_status"
        private const val ALERT_CHANNEL_ID = "nsnd_drowsy_alert"
        private const val NOTIFICATION_ID = 4100
        private const val ALERT_NOTIFICATION_ID = 4200
        private const val ALERT_COOLDOWN_MS = 10_000L
    }
}
