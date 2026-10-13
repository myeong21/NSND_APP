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
import android.os.SystemClock
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.jsm.nsnd.data.local.DetectionMessageParser
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.service.DetectionForegroundService
import com.jsm.nsnd.ui.contact.ContactLocalStore

class AlertOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertOverlayBinding
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentStage = 0
    private var alertSessionId = -1
    private var smsHandled = false
    private var dismissAvailableAt = 0L
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
                    updateStage(sample.stage)
                }
                DetectionForegroundService.ACTION_CONNECTION_STATE -> {
                    if (intent.getIntExtra(DetectionForegroundService.EXTRA_SESSION_ID, -1) != alertSessionId) return
                    when (intent.getStringExtra(DetectionForegroundService.EXTRA_STATE)) {
                        DetectionForegroundService.STATE_AUTH_EXPIRED,
                        DetectionForegroundService.STATE_SESSION_ENDED,
                        DetectionForegroundService.STATE_STOPPED -> dismissAlert()
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
        dismissAvailableAt = savedInstanceState?.getLong("dismiss_available_at")
            ?: (SystemClock.elapsedRealtime() + 3000)
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
                updateStage(latest.stage)
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
        if (stage == 0) {
            currentStage = 0
            dismissAlert()
            return
        }
        if (stage == currentStage) return
        currentStage = stage
        setupStageDisplay(stage)
        setupStageIndicator(stage)
        startAnimations(stage)
        startPhysicalAlert(stage)
        if (stage == 3 && !smsHandled) {
            smsHandled = true
            requestEmergencySms()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("stage", currentStage)
        outState.putBoolean("sms_handled", smsHandled)
        outState.putLong("dismiss_available_at", dismissAvailableAt)
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
                1 -> R.drawable.bg_badge_stage1
                2 -> R.drawable.bg_badge_stage2
                else -> R.drawable.bg_badge_stage3
            }
        )

        // 같은 빨강을 투명도만 높여 위험도가 즉시 읽히도록 한다.
        val dangerRed = ContextCompat.getColor(this, R.color.alert_red)
        val tintAlpha = when (stage) {
            1 -> 18
            2 -> 34
            else -> 54
        }
        binding.viewStageTint.setBackgroundColor(withAlpha(dangerRed, tintAlpha))

        when (stage) {
            1 -> {
                binding.tvAlertStage.text = getString(R.string.alert_stage_1)
                binding.tvAlertDescription.text = "경보음이 울리고 있습니다\n잠시 차를 세우고 휴식을 취하세요"
            }
            2 -> {
                binding.tvAlertStage.text = getString(R.string.alert_stage_2)
                binding.tvAlertDescription.text = "강한 경보음과 진동이 작동 중입니다\n즉시 안전한 곳에 정차하세요"
            }
            3 -> {
                binding.tvAlertStage.text = getString(R.string.alert_stage_3)
                binding.tvAlertDescription.text = "긴급 연락처로 SMS 발송을 시도했습니다\n즉시 차를 세우세요"
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
        val remaining = (dismissAvailableAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        binding.btnDismiss.visibility = if (remaining == 0L) View.VISIBLE else View.INVISIBLE
        if (remaining > 0) handler.postDelayed({ binding.btnDismiss.visibility = View.VISIBLE }, remaining)

        binding.btnDismiss.setOnClickListener {
            dismissAlert()
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
        // 뒤로가기로 경보 화면이 닫히지 않도록 막음
        // 반드시 확인 버튼으로만 닫을 수 있음
    }
}
