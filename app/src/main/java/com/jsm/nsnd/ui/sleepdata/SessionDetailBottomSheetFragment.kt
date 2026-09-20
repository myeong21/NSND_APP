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
import com.jsm.nsnd.network.model.DrowsyEvent
import com.jsm.nsnd.network.model.ReportSummaryResponse
import com.jsm.nsnd.ui.common.ApiErrorMessage
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import kotlinx.coroutines.launch

class SessionDetailBottomSheetFragment : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_SESSION_ID = "arg_session_id"

        fun newInstance(sessionId: Int): SessionDetailBottomSheetFragment {
            return SessionDetailBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_SESSION_ID, sessionId)
                }
            }
        }
    }

    private var _binding: BottomSheetSessionDetailBinding? = null
    private val binding get() = _binding!!

    private var sessionId: Int = -1
    private var kakaoMapInstance: KakaoMap? = null
    private var locationEvents: List<DrowsyEvent> = emptyList()

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
                val api = RetrofitClient.apiService(requireContext())
                val detail = api.getReportDetail(
                    RetrofitClient.authHeader(token),
                    sessionId
                )
                bindDetailData(detail)
            } catch (e: Exception) {
                Log.e("SessionDetail", "loadSessionDetail error", e)
                Toast.makeText(
                    requireContext(),
                    ApiErrorMessage.fromThrowable(e, "세션 상세 조회"),
                    Toast.LENGTH_SHORT
                ).show()
                dismiss()
            } finally {
                _binding?.detailProgressBar?.visibility = View.GONE
            }
        }
    }

    private fun bindDetailData(data: ReportSummaryResponse) {
        val b = _binding ?: return
        b.layoutDetailContent.visibility = View.VISIBLE

        val dateText = data.started_at.replace("T", " ").take(16)
        val durationText = "${data.duration_minutes ?: 0}분 주행"
        b.tvDetailSubtitle.text = "$dateText · $durationText"

        // 안전 점수 링뷰 및 점수
        b.tvDetailScore.text = data.safety_score.toString()
        b.detailSafetyRing.setScore(data.safety_score, data.grade)

        val gradeText = when (data.grade) {
            "safe" -> "안전"
            "caution" -> "주의"
            "danger" -> "위험"
            else -> data.grade
        }
        b.tvDetailGrade.text = gradeText
        when (data.grade) {
            "danger" -> {
                b.tvDetailGrade.setBackgroundResource(R.drawable.bg_badge_danger)
                b.tvDetailGrade.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_danger))
            }
            "caution" -> {
                b.tvDetailGrade.setBackgroundResource(R.drawable.bg_badge_warn)
                b.tvDetailGrade.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_warn))
            }
            else -> {
                b.tvDetailGrade.setBackgroundResource(R.drawable.bg_badge_safe)
                b.tvDetailGrade.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_safe))
            }
        }

        b.tvDetailSummary.text = when (data.grade) {
            "safe" -> "전반적으로 안정적인 주행을 유지했습니다."
            "caution" -> "간헐적인 졸음 징후가 관측되었습니다. 충분한 휴식이 필요합니다."
            else -> "고위험 졸음이 감지되었습니다. 즉시 안전한 장소에서 휴식을 취하세요."
        }

        // 단계별 통계
        b.tvDetailLevel1.text = "${data.level1_count}회"
        b.tvDetailLevel2.text = "${data.level2_count}회"
        b.tvDetailLevel3.text = "${data.level3_count}회"

        // 위경도가 있는 이벤트 필터링
        locationEvents = data.events.filter { it.latitude != null && it.longitude != null }
        b.tvDetailMapDesc.text = "감지된 이벤트 ${locationEvents.size}건의 위치를 표시합니다."

        if (locationEvents.isEmpty()) {
            b.tvDetailNoLocation.visibility = View.VISIBLE
            b.detailMapView.visibility = View.GONE
        } else {
            b.tvDetailNoLocation.visibility = View.GONE
            b.detailMapView.visibility = View.VISIBLE
            setupKakaoMap()
        }
    }

    private fun setupKakaoMap() {
        val mapView = _binding?.detailMapView ?: return

        mapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() {
                    kakaoMapInstance = null
                }

                override fun onMapError(e: Exception) {
                    Log.e("SessionDetail", "카카오맵 초기화 오류", e)
                }
            },
            object : KakaoMapReadyCallback() {
                override fun onMapReady(kakaoMap: KakaoMap) {
                    kakaoMapInstance = kakaoMap
                    displayEventMarkers(kakaoMap)
                }
            }
        )
    }

    private val detailLabelInfoMap = mutableMapOf<com.kakao.vectormap.label.Label, String>()

    private fun displayEventMarkers(kakaoMap: KakaoMap) {
        if (locationEvents.isEmpty()) return

        val labelManager = kakaoMap.labelManager ?: return
        val layer = labelManager.layer ?: return
        detailLabelInfoMap.clear()

        var sumLat = 0.0
        var sumLng = 0.0

        locationEvents.forEach { event ->
            val lat = event.latitude ?: return@forEach
            val lng = event.longitude ?: return@forEach
            sumLat += lat
            sumLng += lng

            val markerRes = when (event.drowsy_level) {
                3 -> R.drawable.ic_marker_stage3
                2 -> R.drawable.ic_marker_stage2
                else -> R.drawable.ic_marker_stage1
            }

            val style = LabelStyles.from(LabelStyle.from(markerRes))
            val position = LatLng.from(lat, lng)

            val time = event.timestamp.substringAfter("T").take(5)
            val label = layer.addLabel(LabelOptions.from(position).setStyles(style))
            if (label != null) {
                detailLabelInfoMap[label] = "${event.drowsy_level}단계 졸음 ($time)"
            }
        }

        // 중심 좌표로 카메라 이동
        val centerLat = sumLat / locationEvents.size
        val centerLng = sumLng / locationEvents.size
        val cameraUpdate = CameraUpdateFactory.newCenterPosition(LatLng.from(centerLat, centerLng), 14)
        kakaoMap.moveCamera(cameraUpdate)

        // 마커 클릭 리스너
        kakaoMap.setOnLabelClickListener { _, _, label ->
            val info = detailLabelInfoMap[label] ?: "졸음 감지 지점"
            Toast.makeText(requireContext(), info, Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        _binding?.detailMapView?.resume()
    }

    override fun onPause() {
        _binding?.detailMapView?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        _binding?.detailMapView?.finish()
        _binding = null
        super.onDestroyView()
    }
}
