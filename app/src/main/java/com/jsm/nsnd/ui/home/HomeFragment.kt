package com.jsm.nsnd.ui.home

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jsm.nsnd.R
import com.jsm.nsnd.data.api.ApiClient
import com.jsm.nsnd.data.api.ContactDto
import com.jsm.nsnd.data.session.ServerConfig
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.databinding.FragmentHomeBinding
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.network.model.DetectionRequest
import com.jsm.nsnd.network.model.HealthResponse
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
        CHECKING, READY, NOT_READY, OFFLINE
    }

    companion object {
        private const val ALERT_COOLDOWN_MS = 10_000L
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var drivingState = DrivingState.IDLE
    private var serverStatus = ServerStatus.CHECKING
    private var currentSessionId: Int = -1

    private var healthResponse: HealthResponse? = null
    private var healthCheckInProgress = false

    private val eventItems = mutableListOf<EventItem>()
    private lateinit var eventAdapter: EventAdapter
    private var lastEventStage = 0

    // 경보 중복 방지 플래그
    private var lastAlertedStage = 0
    private var isAlertActive = false
    private var lastAlertDismissedAt = 0L

    // 주행 시간 타이머
    private var timerJob: Job? = null
    private var sessionStartTimeMs: Long = 0L

    private val sessionManager by lazy { SessionManager(requireContext()) }
    private val token: String get() = sessionManager.getToken().orEmpty()

    private val contactList = mutableListOf<ContactItem>()
    private val sharedViewModel: SharedContactViewModel by activityViewModels()

    // 위치 권한 요청
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            if (fine || coarse) {
                LocationTracker.startTracking(requireContext())
            }
        }

    // SMS 권한 요청
    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                sendEmergencySms()
            } else {
                Toast.makeText(
                    requireContext(),
                    "SMS 권한이 없어 긴급 메시지를 발송할 수 없습니다.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // 알림 권한 요청 (Android 13+)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 경보 오버레이 런처
    private val alertLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            isAlertActive = false
            lastAlertDismissedAt = System.currentTimeMillis()
        }

    // 감지 백그라운드 브로드캐스트 리시버
    private val detectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DetectionForegroundService.ACTION_DETECTION_EVENT -> {
                    intent.getStringExtra(DetectionForegroundService.EXTRA_PAYLOAD)
                        ?.let(::handleWebSocketMessage)
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
        }
    }

    override fun onDestroyView() {
        stopTimer()
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
        binding.btnStart.isEnabled = false
        binding.btnStart.text = "세션 시작 중…"

        // 위치 권한 확인 및 요청
        if (!LocationTracker.hasLocationPermission(requireContext())) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            LocationTracker.startTracking(requireContext())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = RetrofitClient.apiService(requireContext())
                val resp = api.startSession(RetrofitClient.authHeader(token))
                currentSessionId = resp.session_id
                sessionManager.saveActiveSessionId(currentSessionId)

                sessionStartTimeMs = System.currentTimeMillis()
                startTimer()

                transitionTo(DrivingState.SESSION_ACTIVE)
                Toast.makeText(requireContext(), "운전 세션(#$currentSessionId)이 시작되었습니다.", Toast.LENGTH_SHORT).show()
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
        binding.btnStartDetection.isEnabled = false
        binding.btnStartDetection.text = "감지 연결 중…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = RetrofitClient.apiService(requireContext())
                api.startDetection(
                    RetrofitClient.authHeader(token),
                    DetectionRequest(currentSessionId)
                )
                startDetectionService(currentSessionId)
                loadRecentEvents(currentSessionId)
                transitionTo(DrivingState.DETECTING)
                Toast.makeText(requireContext(), "실시간 졸음 감지가 시작되었습니다.", Toast.LENGTH_SHORT).show()
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
        binding.btnPauseDetection.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = RetrofitClient.apiService(requireContext())
                runCatching {
                    api.stopDetection(
                        RetrofitClient.authHeader(token),
                        DetectionRequest(currentSessionId)
                    )
                }
                stopDetectionService()
                updateSleepStage(0)
                transitionTo(DrivingState.SESSION_ACTIVE)
                Toast.makeText(requireContext(), "졸음 감지를 일시정지했습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("HomeFragment", "pauseDrowsinessDetection error", e)
            } finally {
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
        binding.btnStop.isEnabled = false
        binding.btnEndSessionActive.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = RetrofitClient.apiService(requireContext())

                // 감지 중이었으면 감지 먼저 중지
                if (drivingState == DrivingState.DETECTING) {
                    runCatching {
                        api.stopDetection(
                            RetrofitClient.authHeader(token),
                            DetectionRequest(endingSessionId)
                        )
                    }
                    stopDetectionService()
                }

                // 위치 추적 종료 및 최종 거리 확인
                val distanceKm = LocationTracker.stopTracking()

                // 세션 종료 API 호출
                api.endSession(
                    RetrofitClient.authHeader(token),
                    SessionEndRequest(endingSessionId)
                )

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

                b.btnStart.isEnabled = serverStatus == ServerStatus.READY
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

            val stage = json.optInt("drowsy_level", 0)
            val ear = json.optDouble("ear", Double.NaN)

            Handler(Looper.getMainLooper()).post {
                val b = _binding ?: return@post

                updateSleepStage(stage)

                // EAR 지표 표시
                if (!ear.isNaN()) {
                    b.tvEarValue.text = String.format(Locale.US, "EAR %.2f", ear)
                    if (ear < 0.22) {
                        b.tvEarValue.setTextColor(requireContext().getColor(R.color.status_danger))
                    } else {
                        b.tvEarValue.setTextColor(requireContext().getColor(R.color.status_safe))
                    }
                }

                if (stage > 0 && stage != lastEventStage) {
                    addRecentEvent(stage)
                    lastEventStage = stage
                } else if (stage == 0) {
                    lastEventStage = 0
                    lastAlertedStage = 0
                }

                // 위험 경보 트리거
                if (stage > 0 && !isAlertActive) {
                    val stageChanged = stage != lastAlertedStage
                    val cooldownPassed = System.currentTimeMillis() - lastAlertDismissedAt >= ALERT_COOLDOWN_MS
                    if (stageChanged || cooldownPassed) {
                        isAlertActive = true
                        lastAlertedStage = stage
                        triggerAlert(stage)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "WebSocket message parse error", e)
        }
    }

    // ─────────────────────────────────────────
    // 세션 복구 로직 (onResume 시 실행)
    // ─────────────────────────────────────────
    private fun checkServerStatus(showResultToast: Boolean = false) {
        if (healthCheckInProgress) return
        healthCheckInProgress = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withTimeout(8_000L) {
                    RetrofitClient.apiService(requireContext()).healthCheck()
                }
                healthResponse = response
                serverStatus = if (response.models_ready && response.camera_available) {
                    ServerStatus.READY
                } else {
                    ServerStatus.NOT_READY
                }
                updateServerStatusUi()

                // 현재 세션 상태 복구
                recoverActiveSession(showResultToast)
            } catch (e: Exception) {
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
                healthCheckInProgress = false
            }
        }
    }

    private suspend fun recoverActiveSession(showResultToast: Boolean = false) {
        val api = RetrofitClient.apiService(requireContext())
        try {
            val current = api.getCurrentSession(RetrofitClient.authHeader(token))
            currentSessionId = current.id
            sessionManager.saveActiveSessionId(currentSessionId)

            val detection = api.getDetectionStatus(
                RetrofitClient.authHeader(token),
                currentSessionId
            )

            // 타이머 재개
            sessionStartTimeMs = System.currentTimeMillis()
            startTimer()
            LocationTracker.startTracking(requireContext())

            if (detection.is_running) {
                transitionTo(DrivingState.DETECTING)
                startDetectionService(currentSessionId)
                loadRecentEvents(currentSessionId)
            } else {
                transitionTo(DrivingState.SESSION_ACTIVE)
            }

            if (showResultToast && isAdded) {
                Toast.makeText(requireContext(), "진행 중인 세션(#$currentSessionId)을 복구했습니다.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: HttpException) {
            if (e.code() == 404) {
                currentSessionId = -1
                sessionManager.clearActiveSessionId()
                transitionTo(DrivingState.IDLE)
            }
        } catch (_: Exception) {
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

        when (serverStatus) {
            ServerStatus.CHECKING -> {
                b.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_blue)
                b.ivServerStatus.setImageResource(R.drawable.ic_status_checking)
                b.ivServerStatus.setColorFilter(requireContext().getColor(R.color.accent_primary))
                b.tvSystemAvailability.text = "서버 연결 확인 중"
                b.tvSystemAvailabilityDescription.text = "시스템 연결 상태를 확인하고 있습니다."
                b.tvServerMeta.text = "$serverAddress · 확인 중"
            }
            ServerStatus.READY -> {
                b.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_safe)
                b.ivServerStatus.setImageResource(R.drawable.ic_status_online)
                b.ivServerStatus.setColorFilter(requireContext().getColor(R.color.status_safe))
                b.tvSystemAvailability.text = "시스템 준비 완료"
                b.tvSystemAvailabilityDescription.text = "운전을 시작하면 안전 모니터링이 준비됩니다."
                b.tvServerMeta.text = "$serverAddress · $checkedTime 확인"
                if (drivingState == DrivingState.IDLE) b.btnStart.isEnabled = true
            }
            ServerStatus.NOT_READY -> {
                b.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_danger)
                b.ivServerStatus.setImageResource(R.drawable.ic_status_offline)
                b.ivServerStatus.setColorFilter(requireContext().getColor(R.color.status_warn))
                b.tvSystemAvailability.text = "시스템 점검 필요"
                val health = healthResponse
                b.tvSystemAvailabilityDescription.text = when {
                    health?.models_ready != true -> "AI 감지 모델이 준비되지 않았습니다."
                    health.camera_available != true -> "카메라를 사용할 수 없습니다."
                    else -> "감지 시스템 준비 상태를 확인해주세요."
                }
                b.tvServerMeta.text = "$serverAddress · $checkedTime 점검 필요"
            }
            ServerStatus.OFFLINE -> {
                b.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_danger)
                b.ivServerStatus.setImageResource(R.drawable.ic_status_offline)
                b.ivServerStatus.setColorFilter(requireContext().getColor(R.color.status_danger))
                b.tvSystemAvailability.text = "서버 연결 안 됨"
                b.tvSystemAvailabilityDescription.text = "서버 주소와 네트워크를 확인해주세요."
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
                b.tvLiveStatusText.text = "● 실시간 AI 졸음 감지 중"
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
                    val time = event.timestamp.substringAfter('T', event.timestamp).take(5)
                    EventItem(time, "${event.drowsy_level} 단계 졸음 징후가 감지되었습니다.")
                })
                eventAdapter.notifyDataSetChanged()
            } catch (_: Exception) {
            }
        }
    }

    private fun triggerAlert(stage: Int) {
        val intent = Intent(requireContext(), AlertOverlayActivity::class.java).apply {
            putExtra(AlertOverlayActivity.EXTRA_STAGE, stage)
        }
        alertLauncher.launch(intent)

        if (stage >= 3) {
            requestSmsAndSend()
        }
    }

    private fun requestSmsAndSend() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            sendEmergencySms()
        } else {
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
    }

    private fun sendEmergencySms() {
        if (contactList.isEmpty()) {
            Toast.makeText(requireContext(), "등록된 긴급 연락처가 없어 SMS를 발송하지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val smsManager = SmsManager.getDefault()
            contactList.forEach { contact ->
                smsManager.sendTextMessage(contact.phone, null, contact.message, null, null)
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "SMS send error", e)
        }
    }

    private fun startDetectionService(sessionId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        val dummyCo2Value = 1240
        val dummyTime = "09:41"
        binding.tvCo2Value.text = String.format("%,d", dummyCo2Value)
        binding.tvCo2Time.text = "측정시각 $dummyTime"
        updateCo2Status(dummyCo2Value)
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
        ApiClient.contactApi(requireContext()).getContacts(sessionManager.getAuthHeader())
            .enqueue(object : Callback<List<ContactDto>> {
                override fun onResponse(
                    call: Call<List<ContactDto>>,
                    response: retrofit2.Response<List<ContactDto>>
                ) {
                    if (!isAdded || _binding == null) return
                    if (response.code() == 401) {
                        handleSessionExpired()
                        return
                    }
                    val serverContacts = response.body() ?: return
                    val serverItems = serverContacts.map {
                        ContactItem(it.id, it.name, it.phone, it.message)
                    }
                    ContactLocalStore.save(requireContext(), serverItems)
                    sharedViewModel.contacts.value = serverItems
                }

                override fun onFailure(call: Call<List<ContactDto>>, error: Throwable) {
                    // 캐시 연락처 유지
                }
            })
    }
}
