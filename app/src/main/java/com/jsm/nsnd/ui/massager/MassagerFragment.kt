package com.jsm.nsnd.ui.massager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.jsm.nsnd.R
import com.jsm.nsnd.databinding.FragmentMassagerBinding

class MassagerFragment : Fragment() {

    private var _binding: FragmentMassagerBinding? = null
    private val binding get() = _binding!!

    // 단계 설정 (최소 1, 최대 5)
    // TODO: 실제 기기 스펙 확정 후 최대 단계 조정
    private val minStage = 1
    private val maxStage = 5
    private var currentStage = 3

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMassagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupControls()
        updateStageDisplay()
    }

    // ─────────────────────────────────────────
    // 버튼 설정
    // ─────────────────────────────────────────
    private fun setupControls() {
        // 단계 내리기
        binding.btnDecrease.setOnClickListener {
            if (currentStage > minStage) {
                currentStage--
                updateStageDisplay()
            }
        }

        // 단계 올리기
        binding.btnIncrease.setOnClickListener {
            if (currentStage < maxStage) {
                currentStage++
                updateStageDisplay()
            }
        }

        // 저장 버튼
        binding.btnSaveMassager.setOnClickListener {
            saveStage()
        }
    }

    // ─────────────────────────────────────────
    // 단계 화면 업데이트
    // ─────────────────────────────────────────
    private fun updateStageDisplay() {
        // 숫자 표시
        binding.tvMassageStage.text = currentStage.toString()

        // 범위 표시
        binding.tvStageRange.text = "$currentStage / $maxStage"

        // 진행 바 업데이트
        updateProgressBar()

        // 단계 점 업데이트
        updateStepDots()

        // 버튼 활성화/비활성화
        binding.btnDecrease.alpha = if (currentStage <= minStage) 0.3f else 1.0f
        binding.btnDecrease.isEnabled = currentStage > minStage
        binding.btnIncrease.alpha = if (currentStage >= maxStage) 0.3f else 1.0f
        binding.btnIncrease.isEnabled = currentStage < maxStage

        // 단계별 숫자 색상 변경
        val stageColor = when {
            currentStage <= 2 -> requireContext().getColor(R.color.accent_primary)
            currentStage == 3 -> requireContext().getColor(R.color.stage_1)
            currentStage == 4 -> requireContext().getColor(R.color.stage_2)
            else              -> requireContext().getColor(R.color.stage_3)
        }
        binding.tvMassageStage.setTextColor(stageColor)
    }

    // ─────────────────────────────────────────
    // 진행 바 너비 업데이트
    // ─────────────────────────────────────────
    private fun updateProgressBar() {
        binding.progressTrack.post {
            val trackWidth = binding.progressTrack.width
            val fillWidth = (trackWidth * currentStage / maxStage.toFloat()).toInt()
            val params = binding.progressFill.layoutParams
            params.width = fillWidth
            binding.progressFill.layoutParams = params
        }
    }

    // ─────────────────────────────────────────
    // 단계 점 표시 업데이트
    // ─────────────────────────────────────────
    private fun updateStepDots() {
        binding.layoutStepDots.removeAllViews()
        val dotSize = resources.getDimensionPixelSize(R.dimen.step_dot_size)
        val dotMargin = resources.getDimensionPixelSize(R.dimen.step_dot_margin)

        for (i in 1..maxStage) {
            val dot = View(requireContext())
            val params = ViewGroup.MarginLayoutParams(dotSize, dotSize)
            params.marginStart = dotMargin
            params.marginEnd = dotMargin
            dot.layoutParams = params
            dot.background = if (i <= currentStage) {
                requireContext().getDrawable(R.drawable.bg_dot_active)
            } else {
                requireContext().getDrawable(R.drawable.bg_dot_inactive)
            }
            binding.layoutStepDots.addView(dot)
        }
    }

    // ─────────────────────────────────────────
    // 단계 저장
    // ─────────────────────────────────────────
    private fun saveStage() {
        // TODO: 젯슨 나노 서버에 마사지기 단계 설정 전송으로 교체
        binding.btnSaveMassager.isEnabled = false
        binding.btnSaveMassager.text = getString(R.string.massager_saved)
        binding.btnSaveMassager.backgroundTintList =
            requireContext().getColorStateList(R.color.status_safe)

        binding.btnSaveMassager.postDelayed({
            binding.btnSaveMassager.isEnabled = true
            binding.btnSaveMassager.text = getString(R.string.massager_save)
            binding.btnSaveMassager.backgroundTintList =
                requireContext().getColorStateList(R.color.accent_primary)
        }, 1500)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}