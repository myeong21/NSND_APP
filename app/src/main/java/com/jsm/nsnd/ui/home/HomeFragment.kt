package com.jsm.nsnd.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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

class HomeFragment : Fragment() {

    private enum class ServerStatus { CHECKING, AVAILABLE, UNAVAILABLE }

    companion object {
        private const val STATE_CONNECTED = "state_connected"
        private const val STATE_SESSION_ID = "state_session_id"
        private const val STATE_LAST_ALERT_STAGE = "state_last_alert_stage"
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var isConnected = false
    private var currentSessionId: Int = -1
    private var webSocket: WebSocket? = null
    private var serverStatus = ServerStatus.CHECKING
    private var isStartingDetection = false

    // 단계별 경보 중복 방지 플래그
    private var lastAlertedStage = 0
    private var isAlertActive = false
    private var lastAlertDismissedAt: Long = 0L          // 추가
    private val ALERT_COOLDOWN_MS = 10_000L              // 추가

    private val sessionManager by lazy { SessionManager(requireContext()) }
    private val token: String get() = sessionManager.getToken().orEmpty()

    // 연락처 목록 (ContactFragment와 공유하려면 추후 ViewModel로 이동)
    private val contactList = mutableListOf<ContactItem>()

    private val sharedViewModel: SharedContactViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isConnected = savedInstanceState?.getBoolean(STATE_CONNECTED, false) ?: false
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

    // 경보창이 닫히면 isAlertActive 해제
    private val alertLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            isAlertActive = false
            lastAlertDismissedAt = System.currentTimeMillis()  // 종료 시각 기록
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

        // 테마 변경은 Activity를 다시 만들므로, 진행 중이던 서버 세션에는 재접속만 합니다.
        if (isConnected && currentSessionId != -1) {
            connectWebSocket(currentSessionId)
        }

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
                        val serverKeys = serverItems.map { Triple(it.name, it.phone, it.message) }.toSet()
                        serverItems + cachedContacts.filter {
                            Triple(it.name, it.phone, it.message) !in serverKeys
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
        binding.rvEvents.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = EventAdapter(mutableListOf())
        }
    }

    // ─────────────────────────────────────────
    // 작동 시작 버튼
    // ─────────────────────────────────────────
    private fun setupStartButton() {
        binding.btnStart.setOnClickListener {
            when {
                isStartingDetection -> Toast.makeText(
                    requireContext(),
                    "시스템 연결을 진행하고 있습니다. 잠시만 기다려주세요.",
                    Toast.LENGTH_SHORT
                ).show()
                serverStatus == ServerStatus.AVAILABLE -> startDetection()
                serverStatus == ServerStatus.CHECKING -> Toast.makeText(
                    requireContext(),
                    "서버 연결 상태를 확인하고 있습니다.",
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
        if (!isConnected && _binding != null) checkServerStatus()
    }

    private fun checkServerStatus(showResultToast: Boolean = false) {
        serverStatus = ServerStatus.CHECKING
        updateServerStatusUi()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withTimeout(8_000L) {
                    RetrofitClient.apiService(requireContext()).healthCheck()
                }
                serverStatus = if (response.status.equals("ok", ignoreCase = true)) {
                    ServerStatus.AVAILABLE
                } else {
                    ServerStatus.UNAVAILABLE
                }
                if (showResultToast && serverStatus == ServerStatus.AVAILABLE) {
                    Toast.makeText(requireContext(), "서버 연결이 확인되었습니다.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: TimeoutCancellationException) {
                serverStatus = ServerStatus.UNAVAILABLE
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
                serverStatus = ServerStatus.UNAVAILABLE
                if (showResultToast && isAdded) {
                    Toast.makeText(
                        requireContext(),
                        ApiErrorMessage.fromThrowable(e, "서버 상태 확인"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                if (_binding != null) updateServerStatusUi()
            }
        }
    }

    private fun updateServerStatusUi() {
        if (_binding == null || isConnected) return
        val serverAddress = "${ServerConfig.getIp(requireContext())}:8000"
        val checkedTime = SimpleDateFormat("HH:mm:ss", Locale.KOREAN).format(Date())
        when (serverStatus) {
            ServerStatus.CHECKING -> {
                binding.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_blue)
                binding.ivServerStatus.setImageResource(R.drawable.ic_status_checking)
                binding.ivServerStatus.setColorFilter(requireContext().getColor(R.color.accent_primary))
                binding.tvSystemAvailability.text = "서버 연결 확인 중"
                binding.tvSystemAvailabilityDescription.text = "시스템 연결 상태를 확인하고 있습니다."
                binding.tvServerMeta.text = "$serverAddress · 확인 중"
                binding.btnStart.alpha = 0.72f
            }
            ServerStatus.AVAILABLE -> {
                binding.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_safe)
                binding.ivServerStatus.setImageResource(R.drawable.ic_status_online)
                binding.ivServerStatus.setColorFilter(requireContext().getColor(R.color.status_safe))
                binding.tvSystemAvailability.text = "시스템 준비 완료"
                binding.tvSystemAvailabilityDescription.text =
                    "작동을 시작하면 운전자 상태를 실시간으로 확인합니다."
                binding.tvServerMeta.text = "$serverAddress · $checkedTime 확인"
                binding.btnStart.alpha = 1f
            }
            ServerStatus.UNAVAILABLE -> {
                binding.layoutServerStatusIcon.setBackgroundResource(R.drawable.bg_icon_danger)
                binding.ivServerStatus.setImageResource(R.drawable.ic_status_offline)
                binding.ivServerStatus.setColorFilter(requireContext().getColor(R.color.status_danger))
                binding.tvSystemAvailability.text = "서버 연결 안 됨"
                binding.tvSystemAvailabilityDescription.text =
                    "서버 실행 상태와 설정된 서버 주소를 확인해주세요."
                binding.tvServerMeta.text = "$serverAddress · $checkedTime 실패"
                binding.btnStart.alpha = 1f
            }
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
        if (isStartingDetection) return
        isStartingDetection = true
        binding.btnStart.isEnabled = false
        binding.btnStart.text = "시스템 연결 중..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1. 세션 시작
                val sessionResp = RetrofitClient.apiService(requireContext()).startSession(
                    RetrofitClient.authHeader(token)
                )
                currentSessionId = sessionResp.session_id

                // 2. 감지 시작
                RetrofitClient.apiService(requireContext()).startDetection(
                    RetrofitClient.authHeader(token),
                    DetectionRequest(currentSessionId)
                )

                // 3. UI 전환
                isConnected = true
                lastAlertedStage = 0
                updateConnectionState()

                // 4. WebSocket 연결
                connectWebSocket(currentSessionId)

            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    handleSessionExpired()
                } else {
                    serverStatus = ServerStatus.AVAILABLE
                    Log.e("HomeFragment", "startDetection error", e)
                    Toast.makeText(requireContext(), ApiErrorMessage.fromHttpException(e), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                serverStatus = ServerStatus.UNAVAILABLE
                Log.e("HomeFragment", "startDetection error", e)
                Toast.makeText(requireContext(), ApiErrorMessage.fromThrowable(e, "감지 시작"), Toast.LENGTH_LONG).show()
            } finally {
                isStartingDetection = false
                if (_binding != null && !isConnected) {
                    binding.btnStart.isEnabled = true
                    binding.btnStart.text = getString(R.string.home_start_btn)
                    updateServerStatusUi()
                }
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
        webSocket?.close(1000, "사용자 종료")
        webSocket = null

        if (currentSessionId == -1) {
            isConnected = false
            updateConnectionState()
            return
        }

        lifecycleScope.launch {
            try {
                RetrofitClient.apiService(requireContext()).stopDetection(
                    RetrofitClient.authHeader(token),
                    DetectionRequest(currentSessionId)
                )
                RetrofitClient.apiService(requireContext()).endSession(
                    RetrofitClient.authHeader(token),
                    SessionEndRequest(currentSessionId)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeFragment", "stopDetection error", e)
                if (isAdded && _binding != null) {
                    Toast.makeText(
                        requireContext(),
                        ApiErrorMessage.fromThrowable(e, "감지 종료"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                currentSessionId = -1
                isConnected = false
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        updateSleepStage(0)
                        updateConnectionState()
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // WebSocket 연결 및 실시간 데이터 수신
    // ─────────────────────────────────────────
    private fun connectWebSocket(sessionId: Int) {
        val request = okhttp3.Request.Builder()
            .url("${RetrofitClient.wsBaseUrl(requireContext())}/detection/ws/$sessionId")
            .build()

        webSocket = RetrofitClient.okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleWebSocketMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("HomeFragment", "WebSocket failure", t)
                    Handler(Looper.getMainLooper()).post {
                        if (isConnected && isAdded && _binding != null) {
                            Toast.makeText(
                                requireContext(),
                                ApiErrorMessage.fromThrowable(t, "실시간 연결"),
                                Toast.LENGTH_LONG
                            ).show()
                            stopDetection()
                        }
                    }
                }
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

                // 단계가 올라갔을 때만 경보 트리거 (중복 방지)
                if (drowsyLevel > 0 && !isAlertActive) {
                    val cooldownPassed = (System.currentTimeMillis() - lastAlertDismissedAt) >= ALERT_COOLDOWN_MS
                    if (cooldownPassed) {
                        isAlertActive = true
                        lastAlertedStage = drowsyLevel
                        triggerAlert(drowsyLevel)
                    }
                } else if (drowsyLevel == 0) {
                    lastAlertedStage = 0
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
        alertLauncher.launch(intent)  // startActivity 대신 launcher 사용

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
            updateSleepStage(0)

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
        webSocket?.close(1000, "View destroyed")
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_CONNECTED, isConnected)
        outState.putInt(STATE_SESSION_ID, currentSessionId)
        outState.putInt(STATE_LAST_ALERT_STAGE, lastAlertedStage)
        super.onSaveInstanceState(outState)
    }
}
