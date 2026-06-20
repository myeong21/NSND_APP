package com.jsm.nsnd.ui.overlay

import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jsm.nsnd.R
import com.jsm.nsnd.databinding.ActivityAlertOverlayBinding
import android.os.Handler
import android.os.Looper

class AlertOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertOverlayBinding

    companion object {
        const val EXTRA_STAGE = "extra_stage"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // TODO: 젯슨 나노에서 수신한 실제 수면 단계로 교체
        val stage = intent.getIntExtra(EXTRA_STAGE, 1)

        setupStageDisplay(stage)
        setupStageIndicator(stage)
        startAnimations(stage)
        setupDismissButton()
    }

    // ─────────────────────────────────────────
    // 단계별 화면 설정
    // ─────────────────────────────────────────
    private fun setupStageDisplay(stage: Int) {
        when (stage) {
            1 -> {
                binding.tvAlertStage.text = getString(R.string.alert_stage_1)
                binding.tvAlertDescription.text = "경보음이 울리고 있습니다\n잠시 차를 세우고 휴식을 취하세요"
            }
            2 -> {
                binding.tvAlertStage.text = getString(R.string.alert_stage_2)
                binding.tvAlertDescription.text = "경보음과 LED 경고가 작동 중입니다\n즉시 안전한 곳에 정차하세요"
            }
            3 -> {
                binding.tvAlertStage.text = getString(R.string.alert_stage_3)
                binding.tvAlertDescription.text = "긴급 연락처로 SMS를 발송했습니다\n즉시 차를 세우세요"
            }
        }
    }

    // ─────────────────────────────────────────
    // 하단 단계 인디케이터 점 생성
    // ─────────────────────────────────────────
    private fun setupStageIndicator(stage: Int) {
        binding.layoutStageIndicator.removeAllViews()
        val dotSize = resources.getDimensionPixelSize(R.dimen.step_dot_size)
        val dotMargin = resources.getDimensionPixelSize(R.dimen.step_dot_margin)

        for (i in 1..3) {
            val dot = View(this)
            val params = android.widget.LinearLayout.LayoutParams(dotSize * 2, dotSize * 2)
            params.marginStart = dotMargin * 2
            params.marginEnd = dotMargin * 2
            dot.layoutParams = params
            dot.background = if (i <= stage) {
                getDrawable(R.drawable.bg_dot_white_active)
            } else {
                getDrawable(R.drawable.bg_dot_white_inactive)
            }
            binding.layoutStageIndicator.addView(dot)
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
            val flashAnim = AnimationUtils.loadAnimation(this, R.anim.flash_bg)
            binding.viewFlash.startAnimation(flashAnim)
        }
    }

    // ─────────────────────────────────────────
    // 확인 버튼
    // ─────────────────────────────────────────
    private fun setupDismissButton() {
        binding.btnDismiss.visibility = View.INVISIBLE

        Handler(Looper.getMainLooper()).postDelayed({
            binding.btnDismiss.visibility = View.VISIBLE
        }, 3000)

        binding.btnDismiss.setOnClickListener {
            binding.ivRotatingRing.clearAnimation()
            binding.viewFlash.clearAnimation()
            finish()
        }
    }

    override fun onBackPressed() {
        // 뒤로가기로 경보 화면이 닫히지 않도록 막음
        // 반드시 확인 버튼으로만 닫을 수 있음
    }
}