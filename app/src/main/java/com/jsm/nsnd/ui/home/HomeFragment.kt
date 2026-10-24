package com.jsm.nsnd.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.jsm.nsnd.R
import com.jsm.nsnd.data.api.ApiClient
import com.jsm.nsnd.data.api.ContactDto
import com.jsm.nsnd.data.session.ServerConfig
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.data.local.DetectionMessageParser
import com.jsm.nsnd.data.local.DetectionSample
import com.jsm.nsnd.databinding.FragmentHomeBinding
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.network.confirmDetectionStopped
import com.jsm.nsnd.network.model.DetectionRequest
import com.jsm.nsnd.network.model.SessionEndRequest
import com.jsm.nsnd.service.DetectionForegroundService
import com.jsm.nsnd.service.LocationTracker
import com.jsm.nsnd.ui.SharedContactViewModel
import com.jsm.nsnd.ui.auth.LoginActivity
import com.jsm.nsnd.ui.common.ApiErrorMessage
import com.jsm.nsnd.ui.contact.ContactItem
import com.jsm.nsnd.ui.contact.ContactLocalStore
import com.jsm.nsnd.ui.overlay.AlertOverlayActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    /**
     * 운전 세션 3단계 상태 정의
     * - IDLE: 운전 안 함 (운전 시작 버튼 노출)
     * - SESSION_ACTIVE: 세션만 시작됨 (감지 시작 버튼 + 운전 종료 버튼 노출)
     * - DETECTING: 졸음 감지 작동 중 (감지 일시정지 + 운전 종료 버튼 노출)
     */
    enum class DrivingState {
        IDLE, SESSION_ACTIVE, DETECTING
    }

    private enum class ServerStatus {
        CHECKING, CONNECTED, OFFLINE
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var drivingState = DrivingState.IDLE
    private var serverStatus = ServerStatus.CHECKING
    private var currentSessionId: Int = -1

    private var serverCheckInProgress = false
    private var sessionActionInProgress = false
    private var sessionActionGeneration = 0

    private fun beginSessionAction(): Boolean {
        if (sessionActionInProgress) return false
        sessionActionInProgress = true
        sessionActionGeneration++
        return true
    }

    private val eventItems = mutableListOf<EventItem>()
    private lateinit var eventAdapter: EventAdapter
    private var lastEventStage = 0

    // 주행 시간 타이머
    private var timerJob: Job? = null
    private var sessionStartTimeMs: Long = 0L

    private val sessionManager by lazy { SessionManager(requireContext()) }
    private val token: String get() = sessionManager.getToken().orEmpty()

    private val contactList = mutableListOf<ContactItem>()
    private val sharedViewModel: SharedContactViewModel by activityViewModels()

    // 감지 백그라운드 브로드캐스트 리시버
    private val detectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DetectionForegroundService.ACTION_DETECTION_EVENT -> {
                    intent.getStringExtra(DetectionForegroundService.EXTRA_PAYLOAD)
                        ?.let { handleWebSocketMessage(it) }
                }
                DetectionForegroundService.ACTION_CONNECTION_STATE -> {
                    when (intent.getStringExtra(DetectionForegroundService.EXTRA_STATE)) {
                        DetectionForegroundService.STATE_CONNECTED -> {
                            if (drivingState != DrivingState.DETECTING) {
                                transitionTo(DrivingState.DETECTING)
                            }
                        }
                        DetectionForegroundService.STATE_AUTH_EXPIRED -> handleSessionExpired()
                        DetectionForegroundService.STATE_SESSION_ENDED -> {
                            handleServerSessionEnded()
                        }
                        DetectionForegroundService.STATE_ERROR -> {
                            transitionTo(DrivingState.SESSION_ACTIVE)
                            renderDetectionError(intent.getStringExtra(DetectionForegroundService.EXTRA_MESSAGE)
                                ?: "카메라 또는 추론 오류가 발생했습니다.")
                        }
                        DetectionForegroundService.STATE_STOPPED -> {
                            if (drivingState == DrivingState.DETECTING) transitionTo(DrivingState.SESSION_ACTIVE)
                        }
                        DetectionForegroundService.STATE_RECONNECTING -> {
                            _binding?.tvLiveStatusText?.text = "감지 결과 연결을 다시 시도하고 있습니다."
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupCo2Display()
        setupEventRecyclerView()
        setupButtons()
        setupLocationTracking()

        // 긴급 연락처 로드
        val cachedContacts = ContactLocalStore.load(requireContext())
        contactList.clear()
        contactList.addAll(cachedContacts)
        sharedViewModel.contacts.value = cachedContacts
        sharedViewModel.contacts.observe(viewLifecycleOwner) { contacts ->
            contactList.clear()
            contactList.addAll(contacts)
        }
        refreshEmergencyContacts(cachedContacts)

        // 초기 상태 UI 적용
        transitionTo(DrivingState.IDLE)
        checkServerStatus()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            requireContext(),
            detectionReceiver,
            IntentFilter().apply {
                addAction(DetectionForegroundService.ACTION_DETECTION_EVENT)
                addAction(DetectionForegroundService.ACTION_CONNECTION_STATE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { requireContext().unregisterReceiver(detectionReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            checkServerStatus()
            DetectionForegroundService.latestDetection?.takeIf {
                it.account == sessionManager.getDetectionAccountKey() &&
                    it.sessionId == sessionManager.getActiveSessionId()
            }?.let { handleWebSocketMessage(it.payload) }
            DetectionForegroundService.latestFailure?.let(::renderDetectionError)
        }
    }

    override fun onDestroyView() {
        stopTimer()
        LocationTracker.onDistanceChangedListener = null
        _binding = null
        super.onDestroyView()
    }

    // ─────────────────────────────────────────
    // 위치 추적 설정 및 거리 리스너
    // ─────────────────────────────────────────
    private fun setupLocationTracking() {
        LocationTracker.onDistanceChangedListener = { distanceKm ->
            _binding?.tvDriveDistance?.text = String.format(Locale.US, "%.2f km", distanceKm)
        }
    }

    // ─────────────────────────────────────────
    // 버튼 이벤트 리스너 등록
    // ─────────────────────────────────────────
    private fun setupButtons() {
        // 1. [운전 시작] (IDLE -> SESSION_ACTIVE)
        binding.btnStart.setOnClickListener {
            if (serverStatus == ServerStatus.OFFLINE) {
                Toast.makeText(requireContext(), "서버에 연결할 수 없습니다. 서버 상태를 확인하세요.", Toast.LENGTH_SHORT).show()
                checkServerStatus(showResultToast = true)
                return@setOnClickListener
            }
            startDrivingSession()
        }

        // 2. [졸음 감지 시작] (SESSION_ACTIVE -> DETECTING)
        binding.btnStartDetection.setOnClickListener {
            startDrowsinessDetection()
        }

        // 3. [감지 일시정지] (DETECTING -> SESSION_ACTIVE)
        binding.btnPauseDetection.setOnClickListener {
            pauseDrowsinessDetection()
        }

        // 4. [운전 종료] (SESSION_ACTIVE 또는 DETECTING -> IDLE)
        binding.btnEndSessionActive.setOnClickListener {
            endDrivingSession()
        }
        binding.btnStop.setOnClickListener {
            endDrivingSession()
        }

        // 5. 경보 테스트
        binding.btnTestAlert.setOnClickListener {
            val intent = Intent(requireContext(), AlertOverlayActivity::class.java).apply {
                putExtra(AlertOverlayActivity.EXTRA_STAGE, 1)
            }
            startActivity(intent)
        }

        // 6. 서버 새로고침
        binding.btnRefreshServer.setOnClickListener {
            binding.btnRefreshServer.animate()
                .rotationBy(360f)
                .setDuration(450L)
                .start()
            checkServerStatus(showResultToast = true)
        }
    }

    // ─────────────────────────────────────────
    // 1-1. 운전 세션 시작 (POST /sessions/start)
    // ─────────────────────────────────────────
    private fun startDrivingSession() {
        if (!beginSessionAction()) return
        binding.btnStart.isEnabled = false
        binding.btnStart.text = "세션 시작 중…"

        // Permission requests are completed at app startup, before driving begins.
        if (LocationTracker.hasLocationPermission(requireContext())) {
            LocationTracker.startTracking(requireContext())
        } else {
            Toast.makeText(context, "위치 권한이 없어 감지 기록은 위치 없이 저장됩니다.", Toast.LENGTH_LONG).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = RetrofitClient.apiService(requireContext())
                val resp = api.startSession(RetrofitClient.authHeader(token))
                currentSessionId = resp.session_id
                sessionManager.saveActiveSessionId(currentSessionId)

                sessionStartTimeMs = com.jsm.nsnd.ui.common.ServerTime.millis(resp.started_at) ?: System.currentTimeMillis()
                startTimer()

                transitionTo(DrivingState.SESSION_ACTIVE)
                Toast.makeText(requireContext(), "운전 세션(#$currentSessionId)이 시작되었습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    handleSessionExpired()
                } else {
                    Toast.makeText(requireContext(), ApiErrorMessage.fromHttpException(e), Toast.LENGTH_LONG).show()
                    transitionTo(DrivingState.IDLE)
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "startDrivingSession error", e)
                Toast.makeText(requireContext(), ApiErrorMessage.fromThrowable(e, "운전 시작"), Toast.LENGTH_LONG).show()
                transitionTo(DrivingState.IDLE)
            } finally {
                sessionActionInProgress = false
                if (_binding != null) {
                    binding.btnStart.isEnabled = true
                    binding.btnStart.text = "운전 시작"
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // 1-1. 졸음 감지 시작 (POST /detection/start)
    // ─────────────────────────────────────────
    private fun startDrowsinessDetection() {
        if (currentSessionId <= 0) return
        if (!beginSessionAction()) return
        binding.btnStartDetection.isEnabled = false
        binding.btnStartDetection.text = "감지 연결 중…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = RetrofitClient.apiService(requireContext())
                val response = api.startDetection(
                    RetrofitClient.authHeader(token),
                    DetectionRequest(currentSessionId)
                )
                startDetectionService(currentSessionId)
                loadRecentEvents(currentSessionId)
                transitionTo(DrivingState.DETECTING)
                binding.tvLiveStatusText.text = response.message
                binding.tvStageStatus.text = "개인 보정 준비 중"
                Toast.makeText(requireContext(), response.message, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    handleSessionExpired()
                } else {
                    Toast.makeText(requireContext(), ApiErrorMessage.fromHttpException(e), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "startDrowsinessDetection error", e)
                Toast.makeText(requireContext(), ApiErrorMessage.fromThrowable(e, "졸음 감지 시작"), Toast.LENGTH_LONG).show()
            } finally {
                sessionActionInProgress = false
                if (_binding != null) {
                    binding.btnStartDetection.isEnabled = true
                    binding.btnStartDetection.text = "졸음 감지 시작"
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // 1-1. 졸음 감지 일시정지 (POST /detection/stop)
    // ─────────────────────────────────────────
    private fun pauseDrowsinessDetection() {
        if (currentSessionId <= 0) return
        if (!beginSessionAction()) return
        val pausingSessionId = currentSessionId
        binding.btnPauseDetection.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = RetrofitClient.apiService(requireContext())
                val auth = RetrofitClient.authHeader(token)
                confirmDetectionStopped(
                    stop = { api.stopDetection(auth, DetectionRequest(pausingSessionId)) },
                    isRunning = { api.getDetectionStatus(auth, pausingSessionId).is_running }
                )
                if (currentSessionId != pausingSessionId) return@launch
                stopDetectionService()
                updateSleepStage(0)
                transitionTo(DrivingState.SESSION_ACTIVE)
                Toast.makeText(requireContext(), "졸음 감지를 일시정지했습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 401) handleSessionExpired()
                else Toast.makeText(context, ApiErrorMessage.fromHttpException(e), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("HomeFragment", "pauseDrowsinessDetection error", e)
                Toast.makeText(context, ApiErrorMessage.fromThrowable(e, "감지 일시정지"), Toast.LENGTH_LONG).show()
            } finally {
                sessionActionInProgress = false
                if (_binding != null) {
                    binding.btnPauseDetection.isEnabled = true
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // 1-1. 운전 종료 (POST /sessions/end)
    // ─────────────────────────────────────────
    private fun endDrivingSession() {
        if (currentSessionId <= 0) {
            transitionTo(DrivingState.IDLE)
            return
        }

        val endingSessionId = currentSessionId
        if (!beginSessionAction()) return
        binding.btnStop.isEnabled = false
        binding.btnEndSessionActive.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = RetrofitClient.apiService(requireContext())

                // The server's end endpoint also stops its camera. Preserve monitoring until success.
                api.endSession(
                    RetrofitClient.authHeader(token),
                    SessionEndRequest(endingSessionId)
                )
                stopDetectionService()
                val distanceKm = LocationTracker.stopTracking()
                currentSessionId = -1
                sessionManager.clearActiveSessionId()
                stopTimer()
                updateSleepStage(0)

                transitionTo(DrivingState.IDLE)
                Toast.makeText(
                    requireContext(),
                    "운전 세션이 종료되었습니다. (주행 거리: ${String.format(Locale.US, "%.2f km", distanceKm)})",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    handleSessionExpired()
                } else {
                    Toast.makeText(requireContext(), ApiErrorMessage.fromHttpException(e), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "endDrivingSession error", e)
                Toast.makeText(requireContext(), ApiErrorMessage.fromThrowable(e, "운전 종료"), Toast.LENGTH_LONG).show()
            } finally {
                sessionActionInProgress = false
                if (_binding != null) {
                    binding.btnStop.isEnabled = true
                    binding.btnEndSessionActive.isEnabled = true
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // 3가지 상태 UI 전환
    // ─────────────────────────────────────────
    private fun transitionTo(newState: DrivingState) {
        drivingState = newState
        val b = _binding ?: return

        when (newState) {
            DrivingState.IDLE -> {
                b.cardDrivingStats.visibility = View.GONE
                b.cardDetectingState.visibility = View.GONE
                b.cardRecentEvents.visibility = View.GONE
                b.cardServerStatus.visibility = View.VISIBLE

                b.layoutIdleControls.visibility = View.VISIBLE
                b.layoutSessionActiveControls.visibility = View.GONE
                b.layoutDetectingControls.visibility = View.GONE

                b.btnStart.isEnabled = serverStatus == ServerStatus.CONNECTED
            }

            DrivingState.SESSION_ACTIVE -> {
                b.cardDrivingStats.visibility = View.VISIBLE
                b.tvSessionBadge.text = "세션 #$currentSessionId (대기 중)"
                b.cardDetectingState.visibility = View.GONE
                b.cardRecentEvents.visibility = View.GONE
                b.cardServerStatus.visibility = View.VISIBLE

                b.layoutIdleControls.visibility = View.GONE
                b.layoutSessionActiveControls.visibility = View.VISIBLE
                b.layoutDetectingControls.visibility = View.GONE
            }

            DrivingState.DETECTING -> {
                b.cardDrivingStats.visibility = View.VISIBLE
                b.tvSessionBadge.text = "세션 #$currentSessionId (감지 중)"
                b.cardDetectingState.visibility = View.VISIBLE
                b.cardRecentEvents.visibility = View.VISIBLE
                b.cardServerStatus.visibility = View.GONE

                b.layoutIdleControls.visibility = View.GONE
                b.layoutSessionActiveControls.visibility = View.GONE
                b.layoutDetectingControls.visibility = View.VISIBLE
            }
        }
    }

    // ─────────────────────────────────────────
    // 주행 시간 타이머 (1초 주기)
    // ─────────────────────────────────────────
    private fun startTimer() {
        stopTimer()
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - sessionStartTimeMs) / 1000
                val hours = elapsed / 3600
                val minutes = (elapsed % 3600) / 60
                val seconds = elapsed % 60
                _binding?.tvDriveDuration?.text =
                    String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                delay(1000L)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    // ─────────────────────────────────────────
    // 1-2. WebSocket 실시간 메시지 처리 (EAR, 졸음 단계)
    // ─────────────────────────────────────────
    private fun handleWebSocketMessage(text: String) {
        try {
            val json = JSONObject(text)
            if (json.optBoolean("ping", false)) return

            if (json.has("error") || json.optString("state") == "ERROR") {
                renderDetectionError(json.optString("error", "카메라 또는 추론 오류가 발생했습니다."))
                return
            }
            val sample = DetectionMessageParser.parse(text) ?: return
            val stage = sample.stage

            Handler(Looper.getMainLooper()).post {
                val b = _binding ?: return@post

                updateSleepStage(stage)
                renderObservation(sample)

                // EAR 지표 표시
                b.tvEarValue.text = sample.ear?.takeIf { sample.observationValid }
                    ?.let { String.format(Locale.US, "EAR %.2f", it) } ?: "EAR 측정 대기"
                b.tvEarValue.setTextColor(requireContext().getColor(R.color.text_secondary))

                if (sample.observationValid && stage > 0 && stage != lastEventStage) {
                    addRecentEvent(stage)
                    lastEventStage = stage
                } else if (sample.observationValid && stage == 0) {
                    lastEventStage = 0
                }

                // 전경 경보는 MainActivity가 모든 탭에서 수신해 표시합니다.
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "WebSocket message parse error", e)
        }
    }

    private fun renderObservation(sample: DetectionSample) {
        val b = _binding ?: return
        b.tvLiveStatusText.text = sample.statusText()
        if (!sample.observationValid || sample.state in setOf("WARMING_UP", "DROWSY_PENDING")) {
            b.tvStageStatus.text = when (sample.state) {
                "CALIBRATING" -> "개인 보정 중"
                "WARMING_UP" -> "모델 준비 중"
                "DROWSY_PENDING" -> "눈 상태 확인 중"
                else -> "측정 불가"
            }
            b.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_warn)
            b.tvStageStatus.setTextColor(requireContext().getColor(R.color.status_warn))
        }
    }

    private fun renderDetectionError(reason: String) {
        val b = _binding ?: return
        b.cardDetectingState.visibility = View.VISIBLE
        b.tvStageStatus.text = "감지 오류"
        b.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_danger)
        b.tvStageStatus.setTextColor(requireContext().getColor(R.color.status_danger))
        b.tvLiveStatusText.text = "$reason\n졸음 감지를 다시 시작해주세요."
        b.tvEarValue.text = "EAR 측정 중단"
    }

    // ─────────────────────────────────────────
    // 세션 복구 로직 (onResume 시 실행)
    // ─────────────────────────────────────────
    private fun checkServerStatus(showResultToast: Boolean = false) {
        if (serverCheckInProgress) return
        serverCheckInProgress = true
        val requestedToken = token

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // /health opens the server camera to probe it; home only checks connectivity.
                withTimeout(8_000L) {
                    RetrofitClient.apiService(requireContext()).getCurrentUser(RetrofitClient.authHeader(requestedToken))
                }
                if (requestedToken != token) return@launch
                serverStatus = ServerStatus.CONNECTED
                updateServerStatusUi()

                // 현재 세션 상태 복구
                recoverActiveSession(showResultToast)
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (requestedToken != token) return@launch
                if (e is HttpException && e.code() == 401) {
                    handleSessionExpired()
                    return@launch
                }
                serverStatus = ServerStatus.OFFLINE
                updateServerStatusUi()
                if (showResultToast && isAdded) {
                    Toast.makeText(
                        requireContext(),
                        ApiErrorMessage.fromThrowable(e, "서버 연결 확인"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                serverCheckInProgress = false
            }
        }
    }

    private suspend fun recoverActiveSession(showResultToast: Boolean = false) {
        if (sessionActionInProgress) return
        val generation = sessionActionGeneration
        val account = sessionManager.getDetectionAccountKey()
        val api = RetrofitClient.apiService(requireContext())
        try {
            val current = api.getCurrentSession(RetrofitClient.authHeader(token))
            val detection = api.getDetectionStatus(
                RetrofitClient.authHeader(token),
                current.id
            )
            if (sessionActionInProgress || generation != sessionActionGeneration ||
                account != sessionManager.getDetectionAccountKey()) return
            currentSessionId = current.id
            sessionManager.saveActiveSessionId(currentSessionId)
            LocationTracker.restoreSession(requireContext(), current.distance_meters)

            // 타이머 재개
            sessionStartTimeMs = com.jsm.nsnd.ui.common.ServerTime.millis(current.started_at) ?: System.currentTimeMillis()
            startTimer()
            _binding?.tvDriveDistance?.text = String.format(Locale.US, "%.2f km", LocationTracker.getDistanceKm())
            LocationTracker.startTracking(requireContext())

            if (detection.is_running) {
                transitionTo(DrivingState.DETECTING)
                startDetectionService(currentSessionId)
                loadRecentEvents(currentSessionId)
            } else {
                transitionTo(DrivingState.SESSION_ACTIVE)
                DetectionForegroundService.latestFailure?.let(::renderDetectionError)
            }

            if (showResultToast && isAdded) {
                Toast.makeText(requireContext(), "진행 중인 세션(#$currentSessionId)을 복구했습니다.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (generation != sessionActionGeneration || account != sessionManager.getDetectionAccountKey()) return
            if (e.code() == 404) {
                currentSessionId = -1
                sessionManager.clearActiveSessionId()
                stopTimer()
                transitionTo(DrivingState.IDLE)
            } else if (e.code() == 401) {
                handleSessionExpired()
            } else {
                Toast.makeText(context, ApiErrorMessage.fromHttpException(e), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            if (showResultToast && _binding != null) {
                Toast.makeText(context, ApiErrorMessage.fromThrowable(e, "세션 상태 확인"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleServerSessionEnded() {
        currentSessionId = -1
        sessionManager.clearActiveSessionId()
        stopTimer()
        LocationTracker.stopTracking()
        transitionTo(DrivingState.IDLE)
        Toast.makeText(requireContext(), "서버에서 운전 세션이 종료되었습니다.", Toast.LENGTH_LONG).show()
    }

    private fun updateServerStatusUi() {
        val b = _binding ?: return
        val serverAddress = "${ServerConfig.getIp(requireContext())}:8000"
        val checkedTime = SimpleDateFormat("HH:mm:ss", Locale.KOREAN).format(Date())
        if (drivingState == DrivingState.IDLE) {
            b.btnStart.isEnabled = serverStatus == ServerStatus.CONNECTED && !sessionActionInProgress
        }

        when (serverStatus) {
            ServerStatus.CHECKING -> {
                b.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_blue)
                b.ivServerStatus.setImageResource(R.drawable.ic_status_checking)
                b.ivServerStatus.setColorFilter(requireContext().getColor(R.color.accent_primary))
                b.tvSystemAvailability.text = "연결 확인 중"
                b.tvSystemAvailabilityDescription.text = "서버 응답 대기"
                b.tvServerMeta.text = "$serverAddress · 확인 중"
            }
            ServerStatus.CONNECTED -> {
                b.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_safe)
                b.ivServerStatus.setImageResource(R.drawable.ic_status_online)
                b.ivServerStatus.setColorFilter(requireContext().getColor(R.color.status_safe))
                b.tvSystemAvailability.text = "서버 연결됨"
                b.tvSystemAvailabilityDescription.text = "카메라·모델은 감지 시작 시 확인합니다."
                b.tvServerMeta.text = "$serverAddress · $checkedTime 확인"
            }
            ServerStatus.OFFLINE -> {
                b.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_danger)
                b.ivServerStatus.setImageResource(R.drawable.ic_status_offline)
                b.ivServerStatus.setColorFilter(requireContext().getColor(R.color.status_danger))
                b.tvSystemAvailability.text = "서버 연결 안 됨"
                b.tvSystemAvailabilityDescription.text = "서버 주소·네트워크 확인"
                b.tvServerMeta.text = "$serverAddress · $checkedTime 실패"
            }
        }
    }

    private fun updateSleepStage(stage: Int) {
        val b = _binding ?: return
        b.tvStageNumber.text = stage.toString()

        when (stage) {
            0 -> {
                b.tvStageNumber.setTextColor(requireContext().getColor(R.color.accent_primary))
                b.tvStageStatus.text = getString(R.string.home_sleep_normal)
                b.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_safe)
                b.tvStageStatus.setTextColor(requireContext().getColor(R.color.status_safe))
                b.tvLiveStatusText.text = "실시간 감지 중"
            }
            1 -> {
                b.tvStageNumber.setTextColor(requireContext().getColor(R.color.stage_1))
                b.tvStageStatus.text = "1단계"
                b.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_stage1)
                b.tvStageStatus.setTextColor(requireContext().getColor(R.color.stage_1))
                b.tvLiveStatusText.text = "⚠️ 1단계 졸음 징후 감지"
            }
            2 -> {
                b.tvStageNumber.setTextColor(requireContext().getColor(R.color.stage_2))
                b.tvStageStatus.text = "2단계"
                b.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_stage2)
                b.tvStageStatus.setTextColor(requireContext().getColor(R.color.stage_2))
                b.tvLiveStatusText.text = "⚠️ 2단계 졸음 징후 감지"
            }
            3 -> {
                b.tvStageNumber.setTextColor(requireContext().getColor(R.color.stage_3))
                b.tvStageStatus.text = "3단계"
                b.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_stage3)
                b.tvStageStatus.setTextColor(requireContext().getColor(R.color.stage_3))
                b.tvLiveStatusText.text = "🚨 3단계 위험 졸음 감지!"
            }
        }
    }

    private fun addRecentEvent(stage: Int) {
        val time = SimpleDateFormat("HH:mm", Locale.KOREAN).format(Date())
        eventItems.add(0, EventItem(time, "$stage 단계 졸음 징후가 감지되었습니다."))
        while (eventItems.size > 10) eventItems.removeAt(eventItems.lastIndex)
        eventAdapter.notifyDataSetChanged()
    }

    private fun loadRecentEvents(sessionId: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val report = RetrofitClient.apiService(requireContext()).getReportDetail(
                    RetrofitClient.authHeader(token),
                    sessionId
                )
                eventItems.clear()
                eventItems.addAll(report.events.takeLast(10).reversed().map { event ->
                    val time = com.jsm.nsnd.ui.common.ServerTime.display(event.timestamp, "HH:mm")
                    EventItem(time, "${event.drowsy_level} 단계 졸음 징후가 감지되었습니다.")
                })
                eventAdapter.notifyDataSetChanged()
            } catch (_: Exception) {
            }
        }
    }

    private fun startDetectionService(sessionId: Int) {
        ContextCompat.startForegroundService(
            requireContext(),
            Intent(requireContext(), DetectionForegroundService::class.java).apply {
                action = DetectionForegroundService.ACTION_START
                putExtra(DetectionForegroundService.EXTRA_SESSION_ID, sessionId)
            }
        )
    }

    private fun stopDetectionService() {
        requireContext().startService(
            Intent(requireContext(), DetectionForegroundService::class.java).apply {
                action = DetectionForegroundService.ACTION_STOP
            }
        )
    }

    private fun handleSessionExpired() {
        if (_binding == null) return
        sessionManager.clear()
        Toast.makeText(requireContext(), "로그인이 만료되었습니다. 다시 로그인해주세요", Toast.LENGTH_SHORT).show()
        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        requireActivity().finish()
    }

    private fun setupCo2Display() {
        showCo2Unavailable(R.string.co2_connecting)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var expiry: Job? = null
                val appContext = requireContext().applicationContext
                try {
                    while (isActive) {
                        val requestToken = token
                        val requestServer = ServerConfig.getHttpBaseUrl(appContext)
                        if (requestToken.isBlank()) {
                            showCo2Unavailable(R.string.co2_login_required)
                            return@repeatOnLifecycle
                        }
                        val started = SystemClock.elapsedRealtime()
                        try {
                            val response = withTimeout(4_000L) {
                                RetrofitClient.apiService(appContext).getDeviceTelemetry(
                                    RetrofitClient.authHeader(requestToken)
                                )
                            }
                            expiry?.cancel()
                            if (token != requestToken || ServerConfig.getHttpBaseUrl(appContext) != requestServer) {
                                showCo2Unavailable(R.string.co2_connecting)
                            } else {
                                val elapsed = SystemClock.elapsedRealtime() - started
                                val reading = response.freshReading(elapsed)
                                if (reading != null) {
                                    _binding?.tvCo2Value?.text = String.format(Locale.getDefault(), "%,d", reading.ppm)
                                    _binding?.tvCo2Time?.text = getString(R.string.co2_received_age,
                                        ((10_000 - reading.remainingFreshMillis) / 1000).toInt())
                                    updateCo2Status(reading.ppm)
                                    expiry = launch {
                                        delay(reading.remainingFreshMillis)
                                        showCo2Unavailable(R.string.co2_waiting)
                                    }
                                } else {
                                    showCo2Unavailable(when {
                                        !response.arduino_enabled -> R.string.co2_not_configured
                                        !response.arduino_connected -> R.string.co2_disconnected
                                        else -> R.string.co2_waiting
                                    })
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            expiry?.cancel()
                            showCo2Unavailable(R.string.co2_server_unavailable)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: HttpException) {
                            expiry?.cancel()
                            if (token == requestToken && ServerConfig.getHttpBaseUrl(appContext) == requestServer && e.code() == 401) {
                                handleSessionExpired()
                                return@repeatOnLifecycle
                            }
                            showCo2Unavailable(if (e.code() == 404) R.string.co2_api_unavailable else R.string.co2_server_unavailable)
                        } catch (e: Exception) {
                            expiry?.cancel()
                            showCo2Unavailable(R.string.co2_server_unavailable)
                        }
                        delay(2_000L)
                    }
                } finally {
                    expiry?.cancel()
                    showCo2Unavailable(R.string.co2_connecting)
                }
            }
        }
    }

    private fun showCo2Unavailable(reason: Int) {
        val b = _binding ?: return
        b.tvCo2Value.text = "—"
        b.tvCo2Status.setText(R.string.co2_no_reading)
        b.tvCo2Status.setBackgroundResource(R.drawable.bg_badge_warn)
        b.tvCo2Status.setTextColor(ContextCompat.getColor(b.root.context, R.color.text_secondary))
        b.tvCo2Time.setText(reason)
    }

    private fun updateCo2Status(ppm: Int) {
        when {
            ppm < 1000 -> {
                binding.tvCo2Status.text = getString(R.string.home_ventilation_good)
                binding.tvCo2Status.setBackgroundResource(R.drawable.bg_badge_safe)
                binding.tvCo2Status.setTextColor(requireContext().getColor(R.color.status_safe))
            }
            ppm < 2000 -> {
                binding.tvCo2Status.text = getString(R.string.home_ventilation_warn)
                binding.tvCo2Status.setBackgroundResource(R.drawable.bg_badge_warn)
                binding.tvCo2Status.setTextColor(requireContext().getColor(R.color.status_warn))
            }
            else -> {
                binding.tvCo2Status.text = getString(R.string.home_ventilation_danger)
                binding.tvCo2Status.setBackgroundResource(R.drawable.bg_badge_danger)
                binding.tvCo2Status.setTextColor(requireContext().getColor(R.color.status_danger))
            }
        }
    }

    private fun setupEventRecyclerView() {
        eventAdapter = EventAdapter(eventItems)
        binding.rvEvents.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = eventAdapter
        }
    }

    private fun refreshEmergencyContacts(cachedContacts: List<ContactItem>) {
        val account = sessionManager.getDetectionAccountKey()
        val auth = sessionManager.getAuthHeader()
        ApiClient.contactApi(requireContext()).getContacts(sessionManager.getAuthHeader())
            .enqueue(object : Callback<List<ContactDto>> {
                override fun onResponse(
                    call: Call<List<ContactDto>>,
                    response: retrofit2.Response<List<ContactDto>>
                ) {
                    if (!isAdded || _binding == null) return
                    if (sessionManager.getDetectionAccountKey() != account || sessionManager.getAuthHeader() != auth) return
                    if (response.code() == 401) {
                        handleSessionExpired()
                        return
                    }
                    if (!response.isSuccessful) return
                    val serverContacts = response.body() ?: return
                    val serverItems = serverContacts.map {
                        ContactItem(it.id, it.name, it.phone, it.message)
                    }
                    // Keep pending legacy contacts until ContactFragment completes migration.
                    if (!ContactLocalStore.isServerMigrationComplete(requireContext()) && cachedContacts.isNotEmpty()) {
                        sharedViewModel.contacts.value = cachedContacts
                    } else if (ContactLocalStore.load(requireContext()) == cachedContacts) {
                        ContactLocalStore.save(requireContext(), serverItems)
                        sharedViewModel.contacts.value = serverItems
                    }
                }

                override fun onFailure(call: Call<List<ContactDto>>, error: Throwable) {
                    // 캐시 연락처 유지
                }
            })
    }
}
