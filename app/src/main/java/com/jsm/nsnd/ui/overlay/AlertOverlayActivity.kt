package com.jsm.nsnd.ui.overlay

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jsm.nsnd.R
import com.jsm.nsnd.databinding.ActivityAlertOverlayBinding
import android.os.Handler
import android.os.Looper
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.jsm.nsnd.data.local.DetectionMessageParser
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.service.DetectionForegroundService
import com.jsm.nsnd.ui.contact.ContactLocalStore
import com.jsm.nsnd.data.local.DetectionSample
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.network.model.DetectionRequest
import com.jsm.nsnd.ui.common.ApiErrorMessage
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import retrofit2.HttpException

class AlertOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertOverlayBinding
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentStage = -1
    private var alertSessionId = -1
    private var smsHandled = false
    private var audioMuted = false
    private var riskVersion = 0
    private var acknowledgementPending = false
    private var acknowledgementFailure: String? = null
    private var receiverRegistered = false

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) sendEmergencySms()
            else Toast.makeText(this, "SMS 권한이 없어 긴급 메시지를 발송할 수 없습니다.", Toast.LENGTH_LONG).show()
        }

    private val detectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DetectionForegroundService.ACTION_DETECTION_EVENT -> {
                    if (intent.getIntExtra(DetectionForegroundService.EXTRA_SESSION_ID, -1) != alertSessionId) return
                    val sample = intent.getStringExtra(DetectionForegroundService.EXTRA_PAYLOAD)
                        ?.let(DetectionMessageParser::parse) ?: return
                    renderSample(sample, intent.getBooleanExtra(DetectionForegroundService.EXTRA_ALERT_REQUIRED, false))
                }
                DetectionForegroundService.ACTION_CONNECTION_STATE -> {
                    if (intent.getIntExtra(DetectionForegroundService.EXTRA_SESSION_ID, -1) != alertSessionId) return
                    when (intent.getStringExtra(DetectionForegroundService.EXTRA_STATE)) {
                        DetectionForegroundService.STATE_ERROR -> {
                            binding.tvAlertDescription.text =
                                "${intent.getStringExtra(DetectionForegroundService.EXTRA_MESSAGE)}\n감지를 다시 시작해주세요. 경보음은 직접 중지할 수 있습니다."
                        }
                        DetectionForegroundService.STATE_AUTH_EXPIRED,
                        DetectionForegroundService.STATE_SESSION_ENDED,
                        DetectionForegroundService.STATE_STOPPED -> {
                            binding.tvAlertDescription.text = "감지가 종료되었습니다.\n경보음을 멈추려면 ‘경보 중지’를 눌러주세요."
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_STAGE = "extra_stage"
        const val EXTRA_SMS_HANDLED = "extra_sms_handled"
        @Volatile private var activeAlertCount = 0
        val isOpen: Boolean get() = activeAlertCount > 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        activeAlertCount++
        alertSessionId = intent.getIntExtra(DetectionForegroundService.EXTRA_SESSION_ID,
            SessionManager(this).getActiveSessionId())
        smsHandled = savedInstanceState?.getBoolean("sms_handled")
            ?: intent.getBooleanExtra(EXTRA_SMS_HANDLED, false)
        audioMuted = savedInstanceState?.getBoolean("audio_muted") ?: false
        acknowledgementFailure = savedInstanceState?.getString("acknowledgement_failure")
        ContextCompat.registerReceiver(this, detectionReceiver, IntentFilter().apply {
            addAction(DetectionForegroundService.ACTION_DETECTION_EVENT)
            addAction(DetectionForegroundService.ACTION_CONNECTION_STATE)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        updateStage(savedInstanceState?.getInt("stage") ?: intent.getIntExtra(EXTRA_STAGE, 1))
        setupDismissButton()
    }

    override fun onResume() {
        super.onResume()
        // Replay the latest sample if it arrived while this activity was being created.
        DetectionForegroundService.latestDetection?.let { latest ->
            if (latest.sessionId == alertSessionId &&
                latest.account == SessionManager(this).getDetectionAccountKey()) {
                renderSample(latest.sample, latest.alertRequired && audioMuted)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        smsHandled = smsHandled || intent.getBooleanExtra(EXTRA_SMS_HANDLED, false)
        updateStage(intent.getIntExtra(EXTRA_STAGE, currentStage))
    }

    private fun updateStage(stage: Int) {
        if (isFinishing || stage !in 0..3) return
        if (stage == currentStage) return
        val previousStage = currentStage
        currentStage = stage
        setupStageDisplay(stage)
        setupStageIndicator(stage)
        startAnimations(stage)
        if (!audioMuted && previousStage == -1) startPhysicalAlert(stage.coerceAtLeast(1))
        if (stage == 3 && previousStage == -1 && !audioMuted && !smsHandled) {
            smsHandled = true
            requestEmergencySms()
        }
    }

    private fun renderSample(sample: DetectionSample, alertRequired: Boolean) {
        if (alertRequired) {
            riskVersion++
            audioMuted = false
            acknowledgementFailure = null
            startPhysicalAlert(sample.stage.coerceAtLeast(1))
            if (sample.stage == 3 && !smsHandled) {
                smsHandled = true
                requestEmergencySms()
            }
            binding.btnDismiss.text = getString(R.string.alert_dismiss)
        }
        updateStage(sample.stage)
        // A zero level can remain unchanged while measurement readiness changes.
        if (sample.stage == 0) setupStageDisplay(0)
        binding.tvAlertDescription.text = sample.statusText() +
            (if (audioMuted) "\n경보음은 중지되었습니다." else "\n경보음을 멈추려면 ‘경보 중지’를 눌러주세요.") +
            acknowledgementFailure?.let { "\n$it\n서버의 회복 확인을 위해 중지 확인을 재시도해주세요." }.orEmpty()
        if (sample.stage == 0 && (!sample.observationValid || sample.state in setOf("WARMING_UP", "DROWSY_PENDING"))) {
            binding.tvAlertStage.text = when (sample.state) {
                "CALIBRATING" -> "개인 보정 중"
                "WARMING_UP" -> "모델 준비 중"
                "DROWSY_PENDING" -> "눈 상태 확인 중"
                else -> "측정 불가"
            }
            binding.tvAlertStage.setBackgroundResource(R.drawable.bg_badge_warn)
            binding.tvAlertStage.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
            binding.ivWarningIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_warn))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("stage", currentStage)
        outState.putBoolean("sms_handled", smsHandled)
        outState.putBoolean("audio_muted", audioMuted)
        outState.putString("acknowledgement_failure", acknowledgementFailure)
        super.onSaveInstanceState(outState)
    }

    // ─────────────────────────────────────────
    // 단계별 화면 설정
    // ─────────────────────────────────────────
    private fun setupStageDisplay(stage: Int) {
        val stageColor = ContextCompat.getColor(this, stageColor(stage))
        binding.ivWarningIcon.setColorFilter(stageColor)
        binding.tvAlertStage.setTextColor(stageColor)
        binding.tvAlertStage.setBackgroundResource(
            when (stage) {
                0 -> R.drawable.bg_badge_safe
                1 -> R.drawable.bg_badge_stage1
                2 -> R.drawable.bg_badge_stage2
                else -> R.drawable.bg_badge_stage3
            }
        )

        // 같은 빨강을 투명도만 높여 위험도가 즉시 읽히도록 한다.
        val dangerRed = ContextCompat.getColor(this, R.color.alert_red)
        val tintAlpha = when (stage) {
            0 -> 0
            1 -> 18
            2 -> 34
            else -> 54
        }
        binding.viewStageTint.setBackgroundColor(withAlpha(dangerRed, tintAlpha))

        when (stage) {
            0 -> {
                binding.tvAlertStage.text = "0단계 · 정상 회복"
                binding.tvAlertDescription.text = "정상 상태로 회복했습니다.\n경보음을 멈추려면 ‘경보 중지’를 눌러주세요."
            }
            1 -> {
                binding.tvAlertStage.text = getString(R.string.alert_stage_1)
                binding.tvAlertDescription.text = "경보음이 울리고 있습니다\n잠시 차를 세우고 휴식을 취하세요"
            }
            2 -> {
                binding.tvAlertStage.text = getString(R.string.alert_stage_2)
                binding.tvAlertDescription.text = "경보음과 진동이 작동 중입니다\n즉시 안전한 곳에 정차하세요"
            }
            3 -> {
                binding.tvAlertStage.text = getString(R.string.alert_stage_3)
                binding.tvAlertDescription.text = "위험 졸음이 감지되었습니다\n즉시 안전한 곳에 정차하세요"
            }
        }
    }

    // ─────────────────────────────────────────
    // 하단 단계 인디케이터 점 생성
    // ─────────────────────────────────────────
    private fun setupStageIndicator(stage: Int) {
        binding.layoutStageIndicator.removeAllViews()
        val segmentHeight = dp(10)
        val segmentMargin = dp(4)
        val inactiveColor = ContextCompat.getColor(this, R.color.bg_card)
        val borderColor = ContextCompat.getColor(this, R.color.border)

        for (i in 1..3) {
            val segment = View(this)
            val params = android.widget.LinearLayout.LayoutParams(0, segmentHeight, 1f).apply {
                marginStart = segmentMargin
                marginEnd = segmentMargin
            }
            segment.layoutParams = params
            segment.contentDescription = if (i <= stage) "$i 단계 활성" else "$i 단계 비활성"
            segment.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = segmentHeight / 2f
                if (i <= stage) {
                    setColor(ContextCompat.getColor(this@AlertOverlayActivity, stageColor(i)))
                } else {
                    setColor(inactiveColor)
                    setStroke(dp(1), borderColor)
                }
            }
            binding.layoutStageIndicator.addView(segment)
        }
    }

    // ─────────────────────────────────────────
    // 애니메이션 시작
    // ─────────────────────────────────────────
    private fun startAnimations(stage: Int) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            binding.ivRotatingRing.clearAnimation()
            binding.viewFlash.clearAnimation()
            binding.viewFlash.visibility = View.GONE
            return
        }
        // 링 회전 애니메이션
        if (binding.ivRotatingRing.animation == null) {
            val rotateAnim = AnimationUtils.loadAnimation(this, R.anim.rotate_ring)
            binding.ivRotatingRing.startAnimation(rotateAnim)
        }

        // 2단계 이상이면 배경 깜빡임 추가
        if (stage >= 2) {
            binding.viewFlash.visibility = View.VISIBLE
            val flashAlpha = if (stage == 2) 20 else 34
            binding.viewFlash.setBackgroundColor(
                withAlpha(ContextCompat.getColor(this, R.color.alert_red), flashAlpha)
            )
            if (binding.viewFlash.animation == null) {
                val flashAnim = AnimationUtils.loadAnimation(this, R.anim.flash_bg)
                binding.viewFlash.startAnimation(flashAnim)
            }
        } else {
            binding.viewFlash.clearAnimation()
            binding.viewFlash.visibility = View.GONE
        }
    }

    private fun stageColor(stage: Int): Int = when (stage) {
        0 -> R.color.status_safe
        1 -> R.color.stage_1
        2 -> R.color.stage_2
        else -> R.color.stage_3
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun startPhysicalAlert(stage: Int) {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, alarmUri)?.apply {
                isLooping = true
                start()
            }
        }

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = when (stage) {
            1 -> longArrayOf(0, 250, 500)
            2 -> longArrayOf(0, 400, 250, 600, 350)
            else -> longArrayOf(0, 700, 200, 900, 200, 1100)
        }
        vibrator.cancel()
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    private fun requestEmergencySms() {
        if (ContactLocalStore.load(this).isEmpty()) {
            Toast.makeText(this, "등록된 긴급 연락처가 없어 SMS를 발송하지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            sendEmergencySms()
        } else {
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
    }

    private fun sendEmergencySms() {
        runCatching {
            val manager = SmsManager.getDefault()
            ContactLocalStore.load(this).forEach { contact ->
                manager.sendTextMessage(contact.phone, null, contact.message, null, null)
            }
        }.onFailure {
            Toast.makeText(this, "긴급 SMS를 발송하지 못했습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPhysicalAlert() {
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.cancel()
    }

    // ─────────────────────────────────────────
    // 확인 버튼
    // ─────────────────────────────────────────
    private fun setupDismissButton() {
        binding.btnDismiss.visibility = View.VISIBLE
        if (audioMuted) binding.btnDismiss.text = "중지 확인 재시도"

        binding.btnDismiss.setOnClickListener {
            stopAndAcknowledgeAlarm()
        }
    }

    private fun stopAndAcknowledgeAlarm() {
        audioMuted = true
        stopPhysicalAlert()
        DetectionForegroundService.markAlarmStopped(alertSessionId)
        DetectionForegroundService.cancelAlertNotifications(this)
        if (currentStage !in 2..3 || alertSessionId <= 0 ||
            SessionManager(this).getActiveSessionId() != alertSessionId) {
            dismissAlert()
            return
        }
        if (acknowledgementPending) return
        acknowledgementPending = true
        acknowledgementFailure = null
        val stoppedRiskVersion = riskVersion
        binding.btnDismiss.text = "중지 확인 전달 중…"
        binding.tvAlertDescription.text = "경보음은 중지되었습니다.\n서버에 중지 확인을 전달하고 있습니다."
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService(this@AlertOverlayActivity).acknowledgeAlarm(
                    RetrofitClient.authHeader(SessionManager(this@AlertOverlayActivity).getToken().orEmpty()),
                    DetectionRequest(alertSessionId)
                )
                check(response.status == "accepted") { "서버에서 중지 확인을 접수하지 못했습니다." }
                if (audioMuted && riskVersion == stoppedRiskVersion) dismissAlert()
            } catch (error: HttpException) {
                // The server may have already recovered or stopped between the button and request.
                if (error.code() in setOf(404, 409) && audioMuted && riskVersion == stoppedRiskVersion) {
                    Toast.makeText(this@AlertOverlayActivity, "현재 서버 상태에서는 중지 확인이 필요하지 않습니다.", Toast.LENGTH_LONG).show()
                    dismissAlert()
                } else showAcknowledgementFailure(ApiErrorMessage.fromHttpException(error))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                showAcknowledgementFailure(ApiErrorMessage.fromThrowable(error, "경보 중지 확인"))
            } finally {
                acknowledgementPending = false
                binding.btnDismiss.text = if (audioMuted && !isFinishing) "중지 확인 재시도" else getString(R.string.alert_dismiss)
            }
        }
    }

    private fun showAcknowledgementFailure(message: String) {
        acknowledgementFailure = message
        binding.tvAlertDescription.text = if (audioMuted) {
            "경보음은 중지되었습니다.\n$message\n서버의 회복 확인을 위해 재시도해주세요. 뒤로가기로 화면을 닫을 수도 있습니다."
        } else {
            "새로운 위험 경보가 울리고 있습니다.\n$message\n경보를 중지한 뒤 다시 확인해주세요."
        }
    }

    private fun dismissAlert() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_STAGE, currentStage))
        handler.removeCallbacksAndMessages(null)
        binding.ivRotatingRing.clearAnimation()
        binding.viewFlash.clearAnimation()
        stopPhysicalAlert()
        finish()
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(detectionReceiver)
        activeAlertCount--
        handler.removeCallbacksAndMessages(null)
        stopPhysicalAlert()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (audioMuted && !acknowledgementPending) dismissAlert()
    }
}
