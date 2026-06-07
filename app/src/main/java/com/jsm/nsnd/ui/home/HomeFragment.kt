package com.jsm.nsnd.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.jsm.nsnd.R
import com.jsm.nsnd.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // 연결 상태 (임시 상태값 - 추후 ViewModel로 이동)
    private var isConnected = false

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
        updateConnectionState()
    }

    // ─────────────────────────────────────────
    // CO₂ 표시 설정
    // ─────────────────────────────────────────
    private fun setupCo2Display() {
        // TODO: 젯슨 나노 소켓에서 실시간 CO₂ 데이터 수신으로 교체
        val dummyCo2Value = 1240
        val dummyTime = "09:41"

        binding.tvCo2Value.text = String.format("%,d", dummyCo2Value)
        binding.tvCo2Time.text = "측정시각 $dummyTime"

        // CO₂ 수치에 따라 환기 상태 배지 변경
        updateCo2Status(dummyCo2Value)
    }

    private fun updateCo2Status(ppm: Int) {
        // CO₂ 농도 기준
        // ~ 1000 ppm : 양호
        // 1000 ~ 2000 ppm : 환기 권장
        // 2000 ppm ~ : 즉시 환기
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
    // 이벤트 RecyclerView 설정
    // ─────────────────────────────────────────
    private fun setupEventRecyclerView() {
        // TODO: 젯슨 나노에서 실시간 수면 감지 이벤트 수신으로 교체
        val dummyEvents = listOf(
            EventItem("09:38", "서울 강남구 · 1단계"),
            EventItem("09:21", "서울 서초구 · 2단계"),
            EventItem("08:55", "경기 성남시 · 3단계")
        )

        binding.rvEvents.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = EventAdapter(dummyEvents)
        }
    }

    // ─────────────────────────────────────────
    // 작동 시작 버튼
    // ─────────────────────────────────────────
    private fun setupStartButton() {
        binding.btnStart.setOnClickListener {
            // TODO: 젯슨 나노 소켓 연결 로직으로 교체
            isConnected = !isConnected
            updateConnectionState()
        }
    }

    // ─────────────────────────────────────────
    // 연결 상태에 따라 화면 전환
    // ─────────────────────────────────────────
    private fun updateConnectionState() {
        if (isConnected) {
            binding.layoutDisconnected.visibility = View.GONE
            binding.layoutConnected.visibility = View.VISIBLE
            updateSleepStage(0)

            // TODO: 개발 완료 후 아래 임시 버튼 코드 제거
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
        // TODO: 젯슨 나노에서 실시간 수면 단계 수신으로 교체
        binding.tvStageNumber.text = stage.toString()

        when (stage) {
            0 -> {
                binding.tvStageNumber.setTextColor(
                    requireContext().getColor(R.color.accent_primary)
                )
                binding.tvStageStatus.text = getString(R.string.home_sleep_normal)
                binding.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_safe)
                binding.tvStageStatus.setTextColor(
                    requireContext().getColor(R.color.status_safe)
                )
            }
            1 -> {
                binding.tvStageNumber.setTextColor(
                    requireContext().getColor(R.color.stage_1)
                )
                binding.tvStageStatus.text = "1단계"
                binding.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_stage1)
                binding.tvStageStatus.setTextColor(
                    requireContext().getColor(R.color.stage_1)
                )
            }
            2 -> {
                binding.tvStageNumber.setTextColor(
                    requireContext().getColor(R.color.stage_2)
                )
                binding.tvStageStatus.text = "2단계"
                binding.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_stage2)
                binding.tvStageStatus.setTextColor(
                    requireContext().getColor(R.color.stage_2)
                )
            }
            3 -> {
                binding.tvStageNumber.setTextColor(
                    requireContext().getColor(R.color.stage_3)
                )
                binding.tvStageStatus.text = "3단계"
                binding.tvStageStatus.setBackgroundResource(R.drawable.bg_badge_stage3)
                binding.tvStageStatus.setTextColor(
                    requireContext().getColor(R.color.stage_3)
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}