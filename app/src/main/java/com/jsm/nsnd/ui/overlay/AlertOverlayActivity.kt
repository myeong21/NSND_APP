package com.jsm.nsnd.ui.overlay

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

class AlertOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertOverlayBinding
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val EXTRA_STAGE = "extra_stage"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // TODO: 젯슨 나노에서 수신한 실제 수면 단계로 교체
        val stage = intent.getIntExtra(EXTRA_STAGE, 1).coerceIn(1, 3)

        setupStageDisplay(stage)
        setupStageIndicator(stage)
        startAnimations(stage)
        startPhysicalAlert(stage)
        setupDismissButton()
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
        // 링 회전 애니메이션
        val rotateAnim = AnimationUtils.loadAnimation(this, R.anim.rotate_ring)
        binding.ivRotatingRing.startAnimation(rotateAnim)

        // 2단계 이상이면 배경 깜빡임 추가
        if (stage >= 2) {
            binding.viewFlash.visibility = View.VISIBLE
            val flashAlpha = if (stage == 2) 20 else 34
            binding.viewFlash.setBackgroundColor(
                withAlpha(ContextCompat.getColor(this, R.color.alert_red), flashAlpha)
            )
            val flashAnim = AnimationUtils.loadAnimation(this, R.anim.flash_bg)
            binding.viewFlash.startAnimation(flashAnim)
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
        mediaPlayer = MediaPlayer.create(this, alarmUri)?.apply {
            isLooping = true
            start()
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
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
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
        binding.btnDismiss.visibility = View.INVISIBLE

        handler.postDelayed({
            binding.btnDismiss.visibility = View.VISIBLE
        }, 3000)

        binding.btnDismiss.setOnClickListener {
            binding.ivRotatingRing.clearAnimation()
            binding.viewFlash.clearAnimation()
            stopPhysicalAlert()
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPhysicalAlert()
        super.onDestroy()
    }

    override fun onBackPressed() {
        // 뒤로가기로 경보 화면이 닫히지 않도록 막음
        // 반드시 확인 버튼으로만 닫을 수 있음
    }
}
