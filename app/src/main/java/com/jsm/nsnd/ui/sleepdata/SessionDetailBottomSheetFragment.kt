package com.jsm.nsnd.ui.sleepdata

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jsm.nsnd.R
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.databinding.BottomSheetSessionDetailBinding
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.network.model.ReportSummaryResponse
import com.jsm.nsnd.ui.common.ApiErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SessionDetailBottomSheetFragment : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_SESSION_ID = "arg_session_id"

        fun newInstance(sessionId: Int): SessionDetailBottomSheetFragment {
            return SessionDetailBottomSheetFragment().apply {
                arguments = Bundle().apply { putInt(ARG_SESSION_ID, sessionId) }
            }
        }
    }

    private var _binding: BottomSheetSessionDetailBinding? = null
    private val binding get() = _binding!!
    private var sessionId: Int = -1
    private val sessionManager by lazy { SessionManager(requireContext()) }
    private val token: String get() = sessionManager.getToken().orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = arguments?.getInt(ARG_SESSION_ID, -1) ?: -1
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSessionDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvDetailTitle.text = "세션 #$sessionId 상세 리포트"
        binding.btnClose.setOnClickListener { dismiss() }
        loadSessionDetail()
    }

    private fun loadSessionDetail() {
        if (sessionId <= 0) {
            dismiss()
            return
        }
        binding.detailProgressBar.visibility = View.VISIBLE
        binding.layoutDetailContent.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val detail = RetrofitClient.apiService(requireContext()).getReportDetail(
                    RetrofitClient.authHeader(token), sessionId
                )
                bindDetailData(detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SessionDetail", "loadSessionDetail error", e)
                Toast.makeText(
                    requireContext(), ApiErrorMessage.fromThrowable(e, "세션 상세 조회"),
                    Toast.LENGTH_SHORT
                ).show()
                _binding?.tvDetailSubtitle?.text = "리포트를 불러오지 못했습니다."
            } finally {
                _binding?.detailProgressBar?.visibility = View.GONE
            }
        }
    }

    private fun bindDetailData(data: ReportSummaryResponse) {
        val b = _binding ?: return
        b.layoutDetailContent.visibility = View.VISIBLE
        val dateText = com.jsm.nsnd.ui.common.ServerTime.display(data.started_at)
        val durationText = "${data.duration_minutes ?: 0}분 주행"
        val distanceText = String.format(java.util.Locale.US, "%.2f km", data.distance_meters / 1000.0)
        b.tvDetailSubtitle.text = "서버 통계 · $dateText · $durationText · $distanceText"

        // Keep the drive and its recorded counts, but omit an unsupported grade
        // and the explanatory insufficient-data block when no valid time exists.
        val hasObservation = data.valid_observation_seconds > 0
        b.cardDetailScore.visibility = if (hasObservation) View.VISIBLE else View.GONE
        b.tvObservationStats.visibility = if (hasObservation) View.VISIBLE else View.GONE
        if (hasObservation) {
            bindSafetyScore(data)
            b.tvObservationStats.text = ObservationPresentation.describe(
                data.independent_event_count, data.valid_observation_seconds,
                data.unavailable_observation_seconds, data.risk_observation_seconds,
                data.observation_coverage, data.independent_events_per_hour,
                data.episode_peak_level
            )
        }

        b.tvDetailLevel1.text = "${data.level1_count}회"
        b.tvDetailLevel2.text = "${data.level2_count}회"
        b.tvDetailLevel3.text = "${data.level3_count}회"
    }

    private fun bindSafetyScore(data: ReportSummaryResponse) {
        val b = _binding ?: return
        b.tvDetailScore.text = data.safety_score.toString()
        b.detailSafetyRing.setScore(data.safety_score, data.grade)
        val gradeText = when (data.grade) {
            "safe" -> "안전"
            "caution" -> "주의"
            "danger" -> "위험"
            else -> data.grade
        }
        b.tvDetailGrade.text = "$gradeText · 참고"
        val (badge, color) = when (data.grade) {
            "danger" -> R.drawable.bg_badge_danger to R.color.status_danger
            "caution" -> R.drawable.bg_badge_warn to R.color.status_warn
            else -> R.drawable.bg_badge_safe to R.color.status_safe
        }
        b.tvDetailGrade.setBackgroundResource(badge)
        b.tvDetailGrade.setTextColor(ContextCompat.getColor(requireContext(), color))
        b.tvDetailSummary.text = "단계 상승 기록으로 계산한 참고 점수입니다. 유효 관측 시간과 함께 확인하세요."
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
