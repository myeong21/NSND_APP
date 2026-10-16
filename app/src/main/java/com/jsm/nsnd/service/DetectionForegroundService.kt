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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.jsm.nsnd.data.local.DetectionMessageParser
import com.jsm.nsnd.data.local.DetectionSample
import com.jsm.nsnd.data.local.LocalDetectionStore
import com.jsm.nsnd.data.local.LocationSnapshot
import com.jsm.nsnd.data.local.DrowsinessAlarmPolicy
import com.jsm.nsnd.data.local.AlarmUiState
import com.jsm.nsnd.data.local.AlarmStateStore
import com.jsm.nsnd.ui.contact.SmsSettingsStore
import com.jsm.nsnd.network.model.DetectionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
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
    private var ownerAccount: String? = null
    private var ownerUserId: Long? = null
    @Volatile private var connectionGeneration = 0
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val alarmScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var alarmOutput: AlarmOutput
    private var acknowledgementJob: Job? = null
    private var acknowledgementRisk: String? = null
    private var blockedAcknowledgement = false
    private var lastStorageError: String? = null
    private val smsEpisodes = mutableSetOf<String>()
    private data class PendingRecord(val account: String, val userId: Long, val session: Int,
        val sample: DetectionSample, val receivedAtMs: Long, val location: LocationSnapshot)
    private val pendingRecords = Channel<PendingRecord>(Channel.UNLIMITED)

    override fun onCreate() {
        super.onCreate()
        alarmOutput = AlarmOutput(this)
        createNotificationChannels()
        storageScope.launch {
            try {
                val store = LocalDetectionStore.get(this@DetectionForegroundService)
                val processedStages = mutableMapOf<Pair<String, Int>, Pair<Int, String?>>()
                for (record in pendingRecords) {
                    val key = record.account to record.session
                    val identity = record.sample.stage to record.sample.serverEventId
                    if (processedStages[key] == identity) continue
                    runCatching {
                        val inserted = store.recordTransition(record.account, record.userId, record.session, record.sample,
                            record.receivedAtMs, record.location)
                        processedStages[key] = identity
                        // Fresh receipts should not wait behind an older item's job backoff.
                        RecordSyncService.schedule(this@DetectionForegroundService, immediately=inserted != null)
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
            ACTION_STOP -> stopConnection(endAlarm = true)
            ACTION_MUTE -> {
                if (intent.getIntExtra(EXTRA_SESSION_ID,-1) == sessionId &&
                    intent.getStringExtra(EXTRA_ACCOUNT) == ownerAccount) muteAlarm(intent.getStringExtra(EXTRA_RISK_ID))
            }
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
                if (sessionId != requestedSession || ownerAccount != account || !shouldRun) {
                    latestDetection = null
                    alarmPolicy = DrowsinessAlarmPolicy()
                    alarmPolicy.restoreMutedRisk(AlarmStateStore.mutedRisk(this,account,requestedSession))
                    acknowledgementJob?.cancel()
                    acknowledgementRisk = null
                    blockedAcknowledgement = false
                    if (sessionId != requestedSession || ownerAccount != account) {
                        alarmOutput.stop()
                        smsEpisodes.clear()
                        alarmUiState = AlarmUiState(account,requestedSession)
                    }
                    latestFailure = null
                    connectionGeneration++
                    webSocket?.cancel()
                    webSocket = null
                    handler.removeCallbacksAndMessages(null)
                }
                sessionId = requestedSession
                ownerAccount = account
                ownerUserId = manager.getUserId()
                shouldRun = true
                latestFailure = null
                startMonitoringForeground()
                if (!alarmUiState.sounding) AlarmStateStore.activeAlarm(this,account,requestedSession)?.let { saved ->
                    val error = runCatching { alarmOutput.start(saved.stage) }.exceptionOrNull()
                    publishAlarm(AlarmUiState(account,requestedSession,saved.risk,sounding=error == null,
                        error=error?.let { "경고음 복원 실패 · 기기 음량을 확인해주세요" }))
                }
                connect()
                RecordSyncService.schedule(this, recover = true, immediately = true)
                alarmScope.launch { runCatching { SmsSettingsStore.refresh(this@DetectionForegroundService) } }
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
                RecordSyncService.schedule(this@DetectionForegroundService, recover = true, immediately = true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val receivedAtMs = System.currentTimeMillis()
                if (generation != connectionGeneration || !shouldRun) return
                val active = SessionManager(this@DetectionForegroundService)
                if (active.getDetectionAccountKey() != account || active.getActiveSessionId() != connectedSession) {
                    handler.post { stopConnection() }
                    return
                }
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (json.optBoolean("ping")) return
                if (json.has("error") || json.optString("state") == "ERROR") {
                    failDetection(json.optString("error", json.optString("message", "카메라 또는 추론 오류가 발생했습니다.")))
                    return
                }
                val sample = DetectionMessageParser.parse(text) ?: return
                val stage = sample.stage
                if (sample.observationValid) {
                    pendingRecords.trySend(PendingRecord(account, userId, connectedSession, sample, receivedAtMs,
                        LocationTracker.snapshot(this@DetectionForegroundService, receivedAtMs)))
                }
                handler.post {
                    if (generation != connectionGeneration || !shouldRun || SessionManager(this@DetectionForegroundService).getDetectionAccountKey() != account) return@post
                    val alertRequired = publishDetection(account, connectedSession, sample, text).alertRequired
                    handleLiveAlarm(sample,alertRequired)
                    sendBroadcast(Intent(ACTION_DETECTION_EVENT).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_PAYLOAD,text); putExtra(EXTRA_SESSION_ID,connectedSession)
                        putExtra(EXTRA_ALERT_REQUIRED,alertRequired)
                    })
                    if (!isAppForeground() && !AlertOverlayActivity.isOpen && alertRequired) showBackgroundAlert(stage)
                }
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
                    1011 -> failDetection("카메라 또는 추론 오류입니다. 졸음 감지를 다시 시작해주세요.")
                    1000 -> stopConnection()
                    4001 -> {
                        SessionManager(this@DetectionForegroundService).clear()
                        broadcastConnection(STATE_AUTH_EXPIRED, reason)
                        stopConnection()
                    }
                    4004 -> {
                        // An inactive detector does not mean the driving session ended.
                        // Keep its ID; HomeFragment will reconcile /sessions/current.
                        failDetection(reason.ifBlank { "감지가 실행 중이 아닙니다. 다시 시작해주세요." })
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
        val fullScreenIntent = Intent(this, AlertOverlayActivity::class.java).apply {
            putExtra(AlertOverlayActivity.EXTRA_STAGE, stage)
            putExtra(EXTRA_SESSION_ID, sessionId)
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

    }

    private fun publishAlarm(state: AlarmUiState) {
        alarmUiState = state
        sendBroadcast(Intent(ACTION_ALARM_STATE).setPackage(packageName).putExtra(EXTRA_SESSION_ID,sessionId))
        updateStatusNotification(when {
            state.sounding -> "위험 경보 중 · 중지 버튼으로 확인해주세요"
            state.ackPending -> "경보음 중지 · 서버 확인 전달 중"
            state.error != null -> state.error
            else -> if (shouldRun) "실시간 감지 작동 중" else "감지 종료"
        })
    }

    private fun handleLiveAlarm(sample: DetectionSample, alertRequired: Boolean) {
        val account = ownerAccount ?: return
        if (sample.riskId != acknowledgementRisk) {
            acknowledgementJob?.cancel(); acknowledgementRisk = sample.riskId
            blockedAcknowledgement = false
        }
        val savedMuted = sample.riskId != null && AlarmStateStore.mutedRisk(this,account,sessionId) == sample.riskId
        if (alertRequired && !savedMuted) {
            val error = runCatching { alarmOutput.start(sample.stage) }.exceptionOrNull()
            if (error == null) AlarmStateStore.rememberActive(this,account,sessionId,sample.riskId,sample.stage)
            publishAlarm(AlarmUiState(account,sessionId,sample.riskId,sounding=error == null,
                error=error?.let { "경고음 재생 실패 · 음량과 기기 상태를 확인해주세요" }))
        } else if (savedMuted) {
            alarmOutput.stop()
            val pending = sample.stage in 2..3 && sample.alarmAcknowledged != true && !blockedAcknowledgement
            if (!alarmUiState.muted || alarmUiState.riskId != sample.riskId || alarmUiState.ackPending != pending)
                publishAlarm(AlarmUiState(account,sessionId,sample.riskId,muted=true,ackPending=pending,error=if (blockedAcknowledgement) alarmUiState.error else null))
        }
        if (!savedMuted && !alertRequired && sample.riskId != null && alarmUiState.riskId != sample.riskId) {
            // Even an already acknowledged new risk must replace a stale stop-action ID.
            publishAlarm(alarmUiState.copy(riskId=sample.riskId,ackPending=false,error=null))
        }
        if (savedMuted && sample.stage in 2..3 && sample.alarmAcknowledged != true) sendAcknowledgement(sample.riskId)
        if (sample.alarmAcknowledged == true || (sample.observationValid && sample.stage == 0)) {
            acknowledgementJob?.cancel(); acknowledgementRisk = null
            if (alarmUiState.ackPending) publishAlarm(alarmUiState.copy(ackPending=false,error=null))
        }
        // Same stage can contain multiple independent episodes. Only live, valid results enter here.
        val episode = sample.episodeId
        if (sample.observationValid && sample.stage == 3 && sample.alertActive &&
            sample.alarmAcknowledged != true && episode != null && smsEpisodes.add(episode)) {
            val eventSession = sessionId
            alarmScope.launch {
                var confirmedContacts: List<com.jsm.nsnd.ui.contact.ContactItem>? = null
                try { withTimeout(5_000) { confirmedContacts = SmsSettingsStore.refresh(this@DetectionForegroundService).contacts.map {
                    com.jsm.nsnd.ui.contact.ContactItem(it.id,it.name,it.phone,it.message)
                } } }
                catch (cancelled: kotlinx.coroutines.CancellationException) {
                    if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                } catch (_: Exception) { /* Use the last explicitly confirmed account setting offline. */ }
                val current = latestDetection
                if (SessionManager(this@DetectionForegroundService).getDetectionAccountKey() == account &&
                    current?.account == account && current.sessionId == eventSession && sessionId == eventSession &&
                    SmsSettingsStore.enabled(this@DetectionForegroundService)) {
                    EmergencySmsSender.send(this@DetectionForegroundService,confirmedContacts ?: ContactLocalStore.load(this@DetectionForegroundService),
                        "episode:$episode","3단계 자동 경보",account)
                }
            }
        }
        if (lastStorageError != sample.storageError) {
            lastStorageError = sample.storageError
            sample.storageError?.let { updateStatusNotification(it) } ?: publishAlarm(alarmUiState)
        }
    }

    private fun muteAlarm(requestedRisk: String?) {
        val account = ownerAccount ?: return
        val current = latestDetection?.takeIf { it.account == account && it.sessionId == sessionId }
        val risk = current?.sample?.riskId ?: alarmUiState.riskId
        if (requestedRisk != risk) {
            publishAlarm(alarmUiState.copy(error="새 위험이 발생했습니다. 현재 경보에서 다시 중지해주세요"))
            return
        }
        alarmOutput.stop()
        markAlarmStopped(sessionId)
        alarmPolicy.restoreMutedRisk(risk)
        AlarmStateStore.stop(this,account,sessionId,risk)
        cancelAlertNotifications(this)
        val needsAck = current?.sample?.stage in 2..3 && shouldRun
        publishAlarm(AlarmUiState(account,sessionId,risk,muted=true,ackPending=needsAck))
        blockedAcknowledgement = false // An explicit tap authorizes another attempt after a terminal response.
        if (needsAck) sendAcknowledgement(risk)
        else if (!shouldRun) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private fun sendAcknowledgement(risk: String?) {
        if (blockedAcknowledgement && acknowledgementRisk == risk) return
        if (acknowledgementJob?.isActive == true && acknowledgementRisk == risk) return
        acknowledgementJob?.cancel()
        acknowledgementRisk = risk
        val account = ownerAccount ?: return
        val stoppedSession = sessionId
        acknowledgementJob = alarmScope.launch {
            var attempt = 0
            while (shouldRun && ownerAccount == account && sessionId == stoppedSession && alarmUiState.muted && alarmUiState.riskId == risk) {
                try {
                    val manager = SessionManager(this@DetectionForegroundService)
                    if (manager.getDetectionAccountKey() != account) return@launch
                    RetrofitClient.apiService(this@DetectionForegroundService).acknowledgeAlarm(manager.getAuthHeader(),DetectionRequest(stoppedSession,risk))
                    // Receipt is not application: keep retrying until a live server frame confirms ACK.
                } catch (error: HttpException) {
                    if (error.code() in setOf(401,403,404,409)) {
                        blockedAcknowledgement = true
                        publishAlarm(alarmUiState.copy(ackPending=false,error=if(error.code()==409)
                            "서버 경보가 변경되었습니다. 최신 상태를 확인해주세요." else "중지 확인 불가 · 서버/로그인 상태를 확인해주세요."))
                        return@launch
                    }
                    publishAlarm(alarmUiState.copy(ackPending=true,error="경보음은 중지됨 · 서버 확인을 다시 전송합니다."))
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                } catch (_: Exception) {
                    publishAlarm(alarmUiState.copy(ackPending=true,error="경보음은 중지됨 · 통신 복구 후 중지 확인을 재전송합니다."))
                }
                attempt++
                delay(minOf(30_000L,2_000L * attempt))
            }
        }
    }

    private fun failDetection(reason: String) {
        shouldRun = false
        connectionGeneration++
        handler.removeCallbacksAndMessages(null)
        webSocket?.cancel()
        webSocket = null
        latestFailure = reason
        latestDetection = latestDetection?.copy(alertRequired = false)
        // Keep any already-playing local alarm audible until the user stops it.
        broadcastConnection(STATE_ERROR, reason)
        updateStatusNotification("감지 오류 · 앱에서 다시 시작해주세요")
        LocationTracker.stopTracking("detection")
    }

    private fun stopConnection(endAlarm: Boolean = false) {
        if (endAlarm) ownerAccount?.let { AlarmStateStore.clearActive(this,it,sessionId) }
        shouldRun = false
        latestDetection = null
        latestFailure = null
        broadcastConnection(STATE_STOPPED, null)
        connectionGeneration++
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "감지 서비스 종료")
        webSocket = null
        LocationTracker.stopTracking("detection")
        acknowledgementJob?.cancel()
        if (!endAlarm && alarmUiState.sounding) {
            updateStatusNotification("감지 종료 · 경보 중지 버튼으로 소리를 멈출 수 있습니다")
            return
        }
        alarmOutput.stop()
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
        alarmOutput.stop()
        alarmScope.cancel()
        alarmUiState = alarmUiState.copy(sounding=false,ackPending=false)
        shouldRun = false
        latestDetection = null
        latestFailure = null
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

    private fun buildStatusNotification(text: String): android.app.Notification {
        val builder = NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_status_online)
        .setContentTitle("NSND 시스템 작동 중")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        if (alarmUiState.sounding || alarmUiState.ackPending) {
            val mute = PendingIntent.getService(this,901,Intent(this,DetectionForegroundService::class.java).apply {
                action=ACTION_MUTE; putExtra(EXTRA_SESSION_ID,sessionId); putExtra(EXTRA_ACCOUNT,ownerAccount)
                putExtra(EXTRA_RISK_ID,alarmUiState.riskId)
            },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(R.drawable.ic_warning_triangle,"경보 중지",mute)
        }
        return builder.build()
    }

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
            // Audio and vibration have one owner: AlarmOutput in this service.
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(alertChannel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        data class LiveDetection(val account: String, val sessionId: Int,
            val sample: DetectionSample, val payload: String, val alertRequired: Boolean) {
            val stage: Int get() = sample.stage
        }
        @Volatile var latestDetection: LiveDetection? = null
            private set
        @Volatile var latestFailure: String? = null
            private set
        @Volatile var alarmUiState = AlarmUiState()
            private set
        @Volatile private var alarmPolicy = DrowsinessAlarmPolicy()

        @Synchronized fun markAlarmStopped(sessionId: Int) {
            latestDetection?.takeIf { it.sessionId == sessionId }?.let {
                alarmPolicy.acknowledge()
                latestDetection = it.copy(alertRequired = false)
            }
        }

        @Synchronized private fun publishDetection(account: String, sessionId: Int,
            sample: DetectionSample, payload: String): LiveDetection {
            return LiveDetection(account, sessionId, sample, payload, alarmPolicy.accept(sample)).also {
                latestDetection = it
            }
        }

        fun cancelAlertNotifications(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            for (stage in 1..3) manager.cancel(ALERT_NOTIFICATION_ID + stage)
        }
        const val ACTION_START = "com.jsm.nsnd.action.START_DETECTION_SERVICE"
        const val ACTION_MUTE = "com.jsm.nsnd.action.MUTE_ALARM"
        const val ACTION_ALARM_STATE = "com.jsm.nsnd.action.ALARM_STATE"
        const val EXTRA_RISK_ID = "risk_id"
        const val EXTRA_ACCOUNT = "account"
        fun requestAlarmStop(context: Context, session: Int, risk: String?) {
            context.startService(Intent(context,DetectionForegroundService::class.java).apply {
                action=ACTION_MUTE; putExtra(EXTRA_SESSION_ID,session); putExtra(EXTRA_RISK_ID,risk)
                putExtra(EXTRA_ACCOUNT,SessionManager(context).getDetectionAccountKey())
            })
        }
        const val ACTION_STOP = "com.jsm.nsnd.action.STOP_DETECTION_SERVICE"
        const val ACTION_DETECTION_EVENT = "com.jsm.nsnd.action.DETECTION_EVENT"
        const val ACTION_CONNECTION_STATE = "com.jsm.nsnd.action.DETECTION_CONNECTION"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_ALERT_REQUIRED = "alert_required"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val STATE_CONNECTED = "connected"
        const val STATE_RECONNECTING = "reconnecting"
        const val STATE_AUTH_EXPIRED = "auth_expired"
        const val STATE_SESSION_ENDED = "session_ended"
        const val STATE_STOPPED = "stopped"
        const val STATE_ERROR = "error"
        private const val STATUS_CHANNEL_ID = "nsnd_detection_status"
        private const val ALERT_CHANNEL_ID = "nsnd_drowsy_alert_service_v2"
        private const val NOTIFICATION_ID = 4100
        private const val ALERT_NOTIFICATION_ID = 4200
    }
}
