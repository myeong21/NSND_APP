package com.jsm.nsnd.ui.home

import android.Manifest
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
import com.jsm.nsnd.databinding.FragmentHomeBinding
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.network.model.DetectionRequest
import com.jsm.nsnd.network.model.SessionEndRequest
import com.jsm.nsnd.ui.contact.ContactItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import androidx.fragment.app.activityViewModels
import com.jsm.nsnd.ui.SharedContactViewModel

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var isConnected = false
    private var currentSessionId: Int = -1
    private var webSocket: WebSocket? = null

    // 단계별 경보 중복 방지 플래그
    private var lastAlertedStage = 0
    private var isAlertActive = false
    private var lastAlertDismissedAt: Long = 0L          // 추가
    private val ALERT_COOLDOWN_MS = 10_000L              // 추가

    // TODO: 로그인 연동 후 SharedPreferences에서 토큰 가져오기
    private val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIzIiwiZXhwIjoxNzgxNTg2MTY2fQ.jvKM8OaraA14jnuF4ykVyIYs6mMcpKXM-_uST0SDc6Y"

    // 연락처 목록 (ContactFragment와 공유하려면 추후 ViewModel로 이동)
    private val contactList = mutableListOf<ContactItem>()

    private val sharedViewModel: SharedContactViewModel by activityViewModels()

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) sendEmergencySms()
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
        updateConnectionState()

        // 연락처 변경 관찰
        sharedViewModel.contacts.observe(viewLifecycleOwner) { contacts ->
            contactList.clear()
            contactList.addAll(contacts)
        }
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
            startDetection()
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
        lifecycleScope.launch {
            try {
                // 1. 세션 시작
                val sessionResp = RetrofitClient.apiService.startSession(
                    RetrofitClient.authHeader(token)
                )
                currentSessionId = sessionResp.session_id

                // 2. 감지 시작
                RetrofitClient.apiService.startDetection(
                    RetrofitClient.authHeader(token),
                    DetectionRequest(currentSessionId)
                )

                // 3. UI 전환
                isConnected = true
                lastAlertedStage = 0
                updateConnectionState()

                // 4. WebSocket 연결
                connectWebSocket(currentSessionId)

            } catch (e: Exception) {
                Log.e("HomeFragment", "startDetection error", e)
                Toast.makeText(requireContext(), "서버 연결 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
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
                RetrofitClient.apiService.stopDetection(
                    RetrofitClient.authHeader(token),
                    DetectionRequest(currentSessionId)
                )
                RetrofitClient.apiService.endSession(
                    RetrofitClient.authHeader(token),
                    SessionEndRequest(currentSessionId)
                )
            } catch (e: Exception) {
                Log.e("HomeFragment", "stopDetection error", e)
            } finally {
                currentSessionId = -1
                isConnected = false
                withContext(Dispatchers.Main) {
                    updateSleepStage(0)
                    updateConnectionState()
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // WebSocket 연결 및 실시간 데이터 수신
    // ─────────────────────────────────────────
    private fun connectWebSocket(sessionId: Int) {
        val request = okhttp3.Request.Builder()
            .url("${RetrofitClient.WS_BASE_URL}/detection/ws/$sessionId")
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
                        if (isConnected) {
                            Toast.makeText(requireContext(), "연결 끊김: ${t.message}", Toast.LENGTH_SHORT).show()
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
        if (contactList.isEmpty()) return
        try {
            val smsManager = SmsManager.getDefault()
            contactList.forEach { contact ->
                smsManager.sendTextMessage(contact.phone, null, contact.message, null, null)
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "SMS send error", e)
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
}