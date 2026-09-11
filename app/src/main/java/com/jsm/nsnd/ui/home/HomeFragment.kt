package com.jsm.nsnd.ui.home

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jsm.nsnd.R
import com.jsm.nsnd.data.api.ApiClient
import com.jsm.nsnd.data.api.ContactDto
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.data.session.ServerConfig
import com.jsm.nsnd.databinding.FragmentHomeBinding
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.network.model.DetectionRequest
import com.jsm.nsnd.network.model.SessionEndRequest
import com.jsm.nsnd.ui.auth.LoginActivity
import com.jsm.nsnd.ui.contact.ContactLocalStore
import com.jsm.nsnd.ui.contact.ContactItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.fragment.app.activityViewModels
import com.jsm.nsnd.ui.SharedContactViewModel
import com.jsm.nsnd.ui.common.ApiErrorMessage
import retrofit2.Call
import retrofit2.Callback
import com.jsm.nsnd.service.DetectionForegroundService

class HomeFragment : Fragment() {

    private enum class SystemState {
        CHECKING, OFFLINE, NOT_READY, READY, STARTING, RUNNING, RECONNECTING, STOPPING
    }

    companion object {
        private const val STATE_CONNECTED = "state_connected"
        private const val STATE_SESSION_ID = "state_session_id"
        private const val STATE_LAST_ALERT_STAGE = "state_last_alert_stage"
        private const val ALERT_COOLDOWN_MS = 10_000L
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var currentSessionId: Int = -1
    private var systemState = SystemState.CHECKING
    private val isConnected: Boolean
        get() = systemState == SystemState.RUNNING ||
            systemState == SystemState.RECONNECTING ||
            systemState == SystemState.STOPPING
    private var healthResponse: com.jsm.nsnd.network.model.HealthResponse? = null
    private var healthCheckInProgress = false
    private val eventItems = mutableListOf<EventItem>()
    private lateinit var eventAdapter: EventAdapter
    private var lastEventStage = 0

    // 단계별 경보 중복 방지 플래그
    private var lastAlertedStage = 0
    private var isAlertActive = false
    private var lastAlertDismissedAt = 0L

    private val sessionManager by lazy { SessionManager(requireContext()) }
    private val token: String get() = sessionManager.getToken().orEmpty()

    // 연락처 목록 (ContactFragment와 공유하려면 추후 ViewModel로 이동)
    private val contactList = mutableListOf<ContactItem>()

    private val sharedViewModel: SharedContactViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        systemState = if (savedInstanceState?.getBoolean(STATE_CONNECTED, false) == true) {
            SystemState.RECONNECTING
        } else {
            SystemState.CHECKING
        }
        currentSessionId = savedInstanceState?.getInt(STATE_SESSION_ID, -1) ?: -1
        lastAlertedStage = savedInstanceState?.getInt(STATE_LAST_ALERT_STAGE, 0) ?: 0
    }

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

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val alertLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            isAlertActive = false
            lastAlertDismissedAt = System.currentTimeMillis()
        }

    private val detectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DetectionForegroundService.ACTION_DETECTION_EVENT -> {
                    intent.getStringExtra(DetectionForegroundService.EXTRA_PAYLOAD)
                        ?.let(::handleWebSocketMessage)
                }
                DetectionForegroundService.ACTION_CONNECTION_STATE -> {
                    when (intent.getStringExtra(DetectionForegroundService.EXTRA_STATE)) {
                        DetectionForegroundService.STATE_CONNECTED -> transitionTo(SystemState.RUNNING)
                        DetectionForegroundService.STATE_RECONNECTING -> transitionTo(SystemState.RECONNECTING)
                        DetectionForegroundService.STATE_AUTH_EXPIRED -> handleSessionExpired()
                        DetectionForegroundService.STATE_SESSION_ENDED -> {
                            currentSessionId = -1
                            sessionManager.clearActiveSessionId()
                            transitionTo(SystemState.READY)
                            Toast.makeText(
                                requireContext(),
                                "서버에서 감지 세션이 종료되었습니다.",
                                Toast.LENGTH_LONG
                            ).show()
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
        setupStartButton()
        setupStopButton()
        setupRefreshButton()
        updateConnectionState()

        // 서버 장애 중에도 마지막 계정별 연락처로 긴급 SMS를 보낼 수 있도록 캐시를 먼저 사용합니다.
        val cachedContacts = ContactLocalStore.load(requireContext())
        contactList.clear()
        contactList.addAll(cachedContacts)
        sharedViewModel.contacts.value = cachedContacts

        // 연락처 화면에서 서버 동기화가 완료되면 즉시 반영합니다.
        sharedViewModel.contacts.observe(viewLifecycleOwner) { contacts ->
            contactList.clear()
            contactList.addAll(contacts)
        }
        refreshEmergencyContacts(cachedContacts)
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
                    if (!response.isSuccessful) {
                        Toast.makeText(
                            requireContext(),
                            ApiErrorMessage.fromResponse(response),
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }
                    val serverContacts = response.body() ?: return

                    val serverItems = serverContacts.map {
                        ContactItem(it.id, it.name, it.phone, it.message)
                    }
                    val migrationPending =
                        !ContactLocalStore.isServerMigrationComplete(requireContext()) &&
                            cachedContacts.isNotEmpty()

                    // 최초 이전 전에는 서버와 로컬을 합쳐 SMS 누락을 막고, 실제 업로드는 연락처 화면에서 합니다.
                    val itemsForSms = if (migrationPending) {
                        val serverKeys = serverItems.map { it.phone.filter(Char::isDigit) }.toSet()
                        serverItems + cachedContacts.filter {
                            it.phone.filter(Char::isDigit) !in serverKeys
                        }
                    } else {
                        ContactLocalStore.markServerMigrationComplete(requireContext())
                        serverItems
                    }

                    ContactLocalStore.save(requireContext(), itemsForSms)
                    sharedViewModel.contacts.value = itemsForSms
                }

                override fun onFailure(call: Call<List<ContactDto>>, error: Throwable) {
                    if (!isAdded || _binding == null) return
                    val suffix = if (cachedContacts.isEmpty()) "" else " 저장된 연락처를 사용합니다."
                    Toast.makeText(
                        requireContext(),
                        ApiErrorMessage.fromThrowable(error, "긴급 연락처 동기화") + suffix,
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    // ─────────────────────────────────────────
    // CO₂ 표시 설정 (더미)
    // ─────────────────────────────────────────
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

    // ─────────────────────────────────────────
    // 이벤트 RecyclerView
    // ─────────────────────────────────────────
    private fun setupEventRecyclerView() {
        eventAdapter = EventAdapter(eventItems)
        binding.rvEvents.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = eventAdapter
        }
    }

    // ─────────────────────────────────────────
    // 작동 시작 버튼
    // ─────────────────────────────────────────
    private fun setupStartButton() {
        binding.btnStart.setOnClickListener {
            when (systemState) {
                SystemState.STARTING, SystemState.STOPPING -> Toast.makeText(
                    requireContext(),
                    "시스템 상태를 변경하고 있습니다. 잠시만 기다려주세요.",
                    Toast.LENGTH_SHORT
                ).show()
                SystemState.READY -> startDetection()
                SystemState.CHECKING -> Toast.makeText(
                    requireContext(),
                    "서버 연결 상태를 확인하고 있습니다.",
                    Toast.LENGTH_SHORT
                ).show()
                SystemState.NOT_READY -> Toast.makeText(
                    requireContext(),
                    "서버는 연결되었지만 AI 모델 또는 카메라가 준비되지 않았습니다.",
                    Toast.LENGTH_LONG
                ).show()
                SystemState.RUNNING, SystemState.RECONNECTING -> Toast.makeText(
                    requireContext(),
                    "시스템이 이미 작동 중입니다.",
                    Toast.LENGTH_SHORT
                ).show()
                else -> {
                    Toast.makeText(
                        requireContext(),
                        "서버에 연결할 수 없습니다. 서버 실행 상태와 주소를 확인한 뒤 다시 시도하세요.",
                        Toast.LENGTH_LONG
                    ).show()
                    checkServerStatus(showResultToast = true)
                }
            }
        }
    }

    private fun setupRefreshButton() {
        binding.btnRefreshServer.setOnClickListener {
            binding.btnRefreshServer.animate()
                .rotationBy(360f)
                .setDuration(450L)
                .start()
            checkServerStatus(showResultToast = true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) checkServerStatus()
    }

    private fun checkServerStatus(showResultToast: Boolean = false) {
        if (healthCheckInProgress) {
            if (showResultToast) {
                Toast.makeText(requireContext(), "서버 상태를 이미 확인하고 있습니다.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        healthCheckInProgress = true
        val wasConnected = isConnected
        if (!wasConnected) transitionTo(SystemState.CHECKING)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withTimeout(8_000L) {
                    RetrofitClient.apiService(requireContext()).healthCheck()
                }
                healthResponse = response
                systemState = if (response.models_ready && response.camera_available) {
                    SystemState.READY
                } else {
                    SystemState.NOT_READY
                }
                recoverActiveSession(showResultToast)
                if (showResultToast && systemState == SystemState.READY) {
                    Toast.makeText(requireContext(), "서버와 장치 준비 상태가 확인되었습니다.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: TimeoutCancellationException) {
                systemState = if (wasConnected) SystemState.RECONNECTING else SystemState.OFFLINE
                if (showResultToast && isAdded) {
                    Toast.makeText(
                        requireContext(),
                        "서버가 8초 안에 응답하지 않았습니다. 서버 주소와 네트워크를 확인하세요.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("HomeFragment", "healthCheck error", e)
                systemState = if (wasConnected) SystemState.RECONNECTING else SystemState.OFFLINE
                if (showResultToast && isAdded) {
                    Toast.makeText(
                        requireContext(),
                        ApiErrorMessage.fromThrowable(e, "서버 상태 확인"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                healthCheckInProgress = false
                if (_binding != null) transitionTo(systemState)
            }
        }
    }

    private fun updateServerStatusUi() {
        if (_binding == null || isConnected) return
        val serverAddress = "${ServerConfig.getIp(requireContext())}:8000"
        val checkedTime = SimpleDateFormat("HH:mm:ss", Locale.KOREAN).format(Date())
        when (systemState) {
            SystemState.CHECKING -> {
                binding.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_blue)
                binding.ivServerStatus.setImageResource(R.drawable.ic_status_checking)
                binding.ivServerStatus.setColorFilter(requireContext().getColor(R.color.accent_primary))
                binding.tvSystemAvailability.text = "서버 연결 확인 중"
                binding.tvSystemAvailabilityDescription.text = "시스템 연결 상태를 확인하고 있습니다."
                binding.tvServerMeta.text = "$serverAddress · 확인 중"
                binding.btnStart.alpha = 0.72f
            }
            SystemState.READY -> {
                binding.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_safe)
                binding.ivServerStatus.setImageResource(R.drawable.ic_status_online)
                binding.ivServerStatus.setColorFilter(requireContext().getColor(R.color.status_safe))
                binding.tvSystemAvailability.text = "시스템 준비 완료"
                binding.tvSystemAvailabilityDescription.text =
                    "작동을 시작하면 운전자 상태를 실시간으로 확인합니다."
                binding.tvServerMeta.text = "$serverAddress · $checkedTime 확인"
                binding.btnStart.alpha = 1f
            }
            SystemState.NOT_READY -> {
                binding.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_danger)
                binding.ivServerStatus.setImageResource(R.drawable.ic_status_offline)
                binding.ivServerStatus.setColorFilter(requireContext().getColor(R.color.status_warn))
                binding.tvSystemAvailability.text = "시스템 준비 필요"
                val health = healthResponse
                binding.tvSystemAvailabilityDescription.text = when {
                    health?.models_ready != true -> "AI 감지 모델이 준비되지 않았습니다. 서버 로그를 확인해주세요."
                    health.camera_available != true -> "카메라를 사용할 수 없습니다. 연결 상태를 확인해주세요."
                    else -> "감지 시스템 준비 상태를 확인해주세요."
                }
                binding.tvServerMeta.text = "$serverAddress · $checkedTime 점검 필요"
                binding.btnStart.alpha = 0.65f
            }
            SystemState.OFFLINE -> {
                binding.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_danger)
                binding.ivServerStatus.setImageResource(R.drawable.ic_status_offline)
                binding.ivServerStatus.setColorFilter(requireContext().getColor(R.color.status_danger))
                binding.tvSystemAvailability.text = "서버 연결 안 됨"
                binding.tvSystemAvailabilityDescription.text =
                    "서버 실행 상태와 설정된 서버 주소를 확인해주세요."
                binding.tvServerMeta.text = "$serverAddress · $checkedTime 실패"
                binding.btnStart.alpha = 1f
            }
            else -> Unit
        }
    }

    // ─────────────────────────────────────────
    // 작동 종료 버튼
    // ─────────────────────────────────────────
    private fun setupStopButton() {
        binding.btnStop.setOnClickListener {
            stopDetection()
        }
    }

    // ─────────────────────────────────────────
    // 감지 시작: 세션 시작 → 감지 시작 → WebSocket 연결
    // ─────────────────────────────────────────
    private fun startDetection() {
        if (systemState != SystemState.READY) return
        transitionTo(SystemState.STARTING)

        viewLifecycleOwner.lifecycleScope.launch {
            var createdSessionId = -1
            try {
                val sessionResp = RetrofitClient.apiService(requireContext()).startSession(
                    RetrofitClient.authHeader(token)
                )
                createdSessionId = sessionResp.session_id
                currentSessionId = sessionResp.session_id
                RetrofitClient.apiService(requireContext()).startDetection(
                    RetrofitClient.authHeader(token),
                    DetectionRequest(currentSessionId)
                )
                sessionManager.saveActiveSessionId(currentSessionId)
                lastAlertedStage = 0
                transitionTo(SystemState.RUNNING)
                startDetectionService(currentSessionId)
                loadRecentEvents(currentSessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    handleSessionExpired()
                } else if (e.code() == 400) {
                    rollbackStartedSession(createdSessionId)
                    recoverActiveSession(showResultToast = true)
                } else {
                    rollbackStartedSession(createdSessionId)
                    systemState = SystemState.READY
                    Log.e("HomeFragment", "startDetection error", e)
                    Toast.makeText(requireContext(), ApiErrorMessage.fromHttpException(e), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                rollbackStartedSession(createdSessionId)
                systemState = SystemState.OFFLINE
                Log.e("HomeFragment", "startDetection error", e)
                Toast.makeText(requireContext(), ApiErrorMessage.fromThrowable(e, "감지 시작"), Toast.LENGTH_LONG).show()
            } finally {
                if (_binding != null) transitionTo(systemState)
            }
        }
    }

    private suspend fun rollbackStartedSession(sessionId: Int) {
        if (sessionId <= 0) return
        val api = RetrofitClient.apiService(requireContext())
        runCatching {
            api.stopDetection(RetrofitClient.authHeader(token), DetectionRequest(sessionId))
        }
        runCatching {
            api.endSession(RetrofitClient.authHeader(token), SessionEndRequest(sessionId))
        }
        currentSessionId = -1
        sessionManager.clearActiveSessionId()
    }

    private suspend fun recoverActiveSession(showResultToast: Boolean = false) {
        val api = RetrofitClient.apiService(requireContext())
        try {
            val current = api.getCurrentSession(RetrofitClient.authHeader(token))
            currentSessionId = current.id
            val detection = api.getDetectionStatus(
                RetrofitClient.authHeader(token),
                currentSessionId
            )
            if (!detection.is_running) {
                api.startDetection(
                    RetrofitClient.authHeader(token),
                    DetectionRequest(currentSessionId)
                )
            }
            sessionManager.saveActiveSessionId(currentSessionId)
            transitionTo(SystemState.RUNNING)
            startDetectionService(currentSessionId)
            loadRecentEvents(currentSessionId)
            if (showResultToast) {
                Toast.makeText(requireContext(), "진행 중인 시스템 작동을 복구했습니다.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> handleSessionExpired()
                404 -> {
                    currentSessionId = -1
                    sessionManager.clearActiveSessionId()
                    systemState = if (healthResponse?.detection_active == true) {
                        SystemState.NOT_READY
                    } else if (healthResponse?.models_ready == true && healthResponse?.camera_available == true) {
                        SystemState.READY
                    } else {
                        SystemState.NOT_READY
                    }
                }
                409 -> {
                    runCatching {
                        api.endSession(
                            RetrofitClient.authHeader(token),
                            SessionEndRequest(currentSessionId)
                        )
                    }
                    currentSessionId = -1
                    sessionManager.clearActiveSessionId()
                    systemState = SystemState.NOT_READY
                    Toast.makeText(
                        requireContext(),
                        "서버의 카메라가 다른 세션에서 사용 중입니다.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                else -> throw e
            }
        }
    }

    // ─────────────────────────────────────────
// 세션 만료 처리: 로그아웃 + 로그인 화면 이동
// ─────────────────────────────────────────
    private fun handleSessionExpired() {
        if (_binding == null) return
        sessionManager.clear()
        Toast.makeText(requireContext(), "로그인이 만료되었습니다. 다시 로그인해주세요", Toast.LENGTH_SHORT).show()
        val intent = Intent(requireContext(), LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        requireActivity().finish()
    }

    // ─────────────────────────────────────────
    // 감지 종료: WebSocket 닫기 → 감지 종료 → 세션 종료
    // ─────────────────────────────────────────
    private fun stopDetection() {
        if (currentSessionId == -1) {
            transitionTo(SystemState.READY)
            return
        }

        val stoppingSessionId = currentSessionId
        transitionTo(SystemState.STOPPING)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                try {
                    RetrofitClient.apiService(requireContext()).stopDetection(
                        RetrofitClient.authHeader(token),
                        DetectionRequest(stoppingSessionId)
                    )
                } catch (e: HttpException) {
                    if (e.code() != 404) throw e
                }
                RetrofitClient.apiService(requireContext()).endSession(
                    RetrofitClient.authHeader(token),
                    SessionEndRequest(stoppingSessionId)
                )
                stopDetectionService()
                currentSessionId = -1
                sessionManager.clearActiveSessionId()
                updateSleepStage(0)
                transitionTo(SystemState.READY)
                Toast.makeText(requireContext(), "시스템 작동을 종료했습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    handleSessionExpired()
                } else {
                    transitionTo(SystemState.RUNNING)
                    Toast.makeText(requireContext(), ApiErrorMessage.fromHttpException(e), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "stopDetection error", e)
                transitionTo(SystemState.RECONNECTING)
                Toast.makeText(
                    requireContext(),
                    ApiErrorMessage.fromThrowable(e, "감지 종료") + " 서버 세션은 유지됩니다.",
                    Toast.LENGTH_LONG
                ).show()
            }
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

    // ─────────────────────────────────────────
    // WebSocket 메시지 처리
    // ─────────────────────────────────────────
    private fun handleWebSocketMessage(text: String) {
        try {
            val json = JSONObject(text)
            if (json.has("ping")) return

            val drowsyLevel = json.optInt("drowsy_level", 0)

            Handler(Looper.getMainLooper()).post {
                if (_binding == null) return@post

                updateSleepStage(drowsyLevel)
                if (drowsyLevel > 0 && drowsyLevel != lastEventStage) {
                    addRecentEvent(drowsyLevel)
                    lastEventStage = drowsyLevel
                } else if (drowsyLevel == 0) {
                    lastEventStage = 0
                    lastAlertedStage = 0
                }

                if (drowsyLevel > 0 && !isAlertActive) {
                    val stageChanged = drowsyLevel != lastAlertedStage
                    val cooldownPassed = System.currentTimeMillis() - lastAlertDismissedAt >= ALERT_COOLDOWN_MS
                    if (stageChanged || cooldownPassed) {
                        isAlertActive = true
                        lastAlertedStage = drowsyLevel
                        triggerAlert(drowsyLevel)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "WebSocket message parse error", e)
        }
    }

    // ─────────────────────────────────────────
    // 경보 트리거
    // ─────────────────────────────────────────
    private fun triggerAlert(stage: Int) {
        val intent = android.content.Intent(
            requireContext(),
            com.jsm.nsnd.ui.overlay.AlertOverlayActivity::class.java
        ).apply {
            putExtra(com.jsm.nsnd.ui.overlay.AlertOverlayActivity.EXTRA_STAGE, stage)
        }
        alertLauncher.launch(intent)

        if (stage >= 3) {
            requestSmsAndSend()
        }
    }

    // ─────────────────────────────────────────
    // SMS 발송
    // ─────────────────────────────────────────
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
            Toast.makeText(
                requireContext(),
                "등록된 긴급 연락처가 없어 SMS를 발송하지 못했습니다.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            val smsManager = SmsManager.getDefault()
            contactList.forEach { contact ->
                smsManager.sendTextMessage(contact.phone, null, contact.message, null, null)
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "SMS send error", e)
            Toast.makeText(
                requireContext(),
                "긴급 SMS 발송에 실패했습니다: ${e.message ?: "알 수 없는 오류"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ─────────────────────────────────────────
    // 연결 상태 UI 전환
    // ─────────────────────────────────────────
    private fun updateConnectionState() {
        if (isConnected) {
            binding.layoutDisconnected.visibility = View.GONE
            binding.layoutConnected.visibility = View.VISIBLE

            binding.btnTestAlert.setOnClickListener {
                val intent = android.content.Intent(
                    requireContext(),
                    com.jsm.nsnd.ui.overlay.AlertOverlayActivity::class.java
                ).apply {
                    putExtra(com.jsm.nsnd.ui.overlay.AlertOverlayActivity.EXTRA_STAGE, 1)
                }
                startActivity(intent)
            }
        } else {
            binding.layoutDisconnected.visibility = View.VISIBLE
            binding.layoutConnected.visibility = View.GONE
        }
    }

    private fun transitionTo(newState: SystemState) {
        systemState = newState
        if (_binding == null) return
        updateConnectionState()
        binding.btnStart.isEnabled = newState == SystemState.READY
        binding.btnStart.text = when (newState) {
            SystemState.STARTING -> "시스템 연결 중…"
            else -> getString(R.string.home_start_btn)
        }
        binding.btnStop.isEnabled = newState == SystemState.RUNNING || newState == SystemState.RECONNECTING
        binding.btnStop.text = if (newState == SystemState.STOPPING) "종료 중…" else "작동 종료"
        if (!isConnected) updateServerStatusUi()
        if (newState == SystemState.RECONNECTING) {
            binding.tvStageStatus.text = "재연결 중"
            binding.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_warn)
            binding.tvStageStatus.setTextColor(requireContext().getColor(R.color.status_warn))
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
            } catch (e: HttpException) {
                if (e.code() == 401) handleSessionExpired()
            } catch (_: Exception) {
                // 실시간 감지는 유지하고 이전 이벤트만 표시하지 않습니다.
            }
        }
    }

    // ─────────────────────────────────────────
    // 수면 단계 표시 업데이트
    // ─────────────────────────────────────────
    private fun updateSleepStage(stage: Int) {
        binding.tvStageNumber.text = stage.toString()

        when (stage) {
            0 -> {
                binding.tvStageNumber.setTextColor(requireContext().getColor(R.color.accent_primary))
                binding.tvStageStatus.text = getString(R.string.home_sleep_normal)
                binding.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_safe)
                binding.tvStageStatus.setTextColor(requireContext().getColor(R.color.status_safe))
            }
            1 -> {
                binding.tvStageNumber.setTextColor(requireContext().getColor(R.color.stage_1))
                binding.tvStageStatus.text = "1단계"
                binding.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_stage1)
                binding.tvStageStatus.setTextColor(requireContext().getColor(R.color.stage_1))
            }
            2 -> {
                binding.tvStageNumber.setTextColor(requireContext().getColor(R.color.stage_2))
                binding.tvStageStatus.text = "2단계"
                binding.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_stage2)
                binding.tvStageStatus.setTextColor(requireContext().getColor(R.color.stage_2))
            }
            3 -> {
                binding.tvStageNumber.setTextColor(requireContext().getColor(R.color.stage_3))
                binding.tvStageStatus.text = "3단계"
                binding.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_stage3)
                binding.tvStageStatus.setTextColor(requireContext().getColor(R.color.stage_3))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_CONNECTED, isConnected)
        outState.putInt(STATE_SESSION_ID, currentSessionId)
        outState.putInt(STATE_LAST_ALERT_STAGE, lastAlertedStage)
        super.onSaveInstanceState(outState)
    }

}
