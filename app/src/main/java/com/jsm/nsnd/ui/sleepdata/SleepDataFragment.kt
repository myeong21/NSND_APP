package com.jsm.nsnd.ui.sleepdata

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.tabs.TabLayout
import com.jsm.nsnd.R
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.databinding.FragmentSleepDataBinding
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.network.model.DrowsyEvent
import com.jsm.nsnd.network.model.ReportByDateResponse
import com.jsm.nsnd.network.model.ReportHistoryResponse
import com.jsm.nsnd.ui.auth.LoginActivity
import com.jsm.nsnd.ui.common.ApiErrorMessage
import com.jsm.nsnd.service.LocationTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SleepDataFragment : Fragment() {

    companion object {
        private const val TAG = "SleepDataFragment"
        private const val KAKAO_JS_KEY = "b560c7f8d8f2d3c455c837c3f33df7e2"
    }

    private var _binding: FragmentSleepDataBinding? = null
    private val binding get() = _binding!!

    private var selectedCalendar = Calendar.getInstance()
    private var isMapReady = false
    private var dateLocationEvents: List<DrowsyEvent> = emptyList()

    private val sessionManager by lazy { SessionManager(requireContext()) }
    private val token: String get() = sessionManager.getToken().orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSleepDataBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()
        setupDateDisplay()
        setupCalendarButton()
        setupMapWebView()

        binding.btnRetrySleep.setOnClickListener { loadReportData() }
        binding.btnRefreshHistory.setOnClickListener { loadHistoryData() }
        binding.btnOpenFullMap.setOnClickListener {
            try {
                findNavController().navigate(R.id.mapFragment)
            } catch (e: Exception) {
                Log.e(TAG, "Navigation to mapFragment failed", e)
            }
        }

        // 초기 데이터 로드
        loadReportData()
    }

    // ─────────────────────────────────────────
    // 상단 탭 전환 리스너 (날짜별 / 운전 기록 히스토리)
    // ─────────────────────────────────────────
    private fun setupTabs() {
        binding.tabLayoutReport.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.layoutDateReportContainer.visibility = View.VISIBLE
                        binding.layoutHistoryContainer.visibility = View.GONE
                    }
                    1 -> {
                        binding.layoutDateReportContainer.visibility = View.GONE
                        binding.layoutHistoryContainer.visibility = View.VISIBLE
                        loadHistoryData()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // ─────────────────────────────────────────
    // 날짜 표시 및 달력 선택
    // ─────────────────────────────────────────
    private fun setupDateDisplay() {
        updateDateText()
    }

    private fun updateDateText() {
        val today = Calendar.getInstance()
        val isToday = selectedCalendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                selectedCalendar.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)

        val sdf = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREAN)
        val dateStr = sdf.format(selectedCalendar.time)
        binding.tvDate.text = if (isToday) "$dateStr (오늘)" else dateStr
    }

    private fun setupCalendarButton() {
        binding.btnCalendar.setOnClickListener {
            val year = selectedCalendar.get(Calendar.YEAR)
            val month = selectedCalendar.get(Calendar.MONTH)
            val day = selectedCalendar.get(Calendar.DAY_OF_MONTH)

            DatePickerDialog(requireContext(), { _, y, m, d ->
                selectedCalendar.set(y, m, d)
                updateDateText()
                loadReportData()
            }, year, month, day).show()
        }
    }

    // ─────────────────────────────────────────
    // [15~16차] 카카오맵 JavaScript API (WebView)
    // ─────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupMapWebView() {
        val webView = binding.mapWebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.addJavascriptInterface(MapBridge(), "AndroidBridge")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d(TAG, "[WebView JS] ${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "카카오맵 WebView 로드 완료")
            }
        }
        // assets에서 HTML 로드 (JavaScript 키를 동적으로 치환)
        val htmlContent = requireContext().assets.open("kakao_map.html")
            .bufferedReader().readText()
            .replace("KAKAO_JS_KEY", KAKAO_JS_KEY)
        webView.loadDataWithBaseURL(
            "https://localhost:5173",
            htmlContent,
            "text/html",
            "UTF-8",
            null
        )
    }

    /** JavaScript → Android 브릿지 */
    inner class MapBridge {
        @JavascriptInterface
        fun onMapReady() {
            activity?.runOnUiThread {
                isMapReady = true
                Log.i(TAG, "카카오맵 JavaScript API 준비 완료")
                _binding?.tvMapEmpty?.visibility = View.GONE
                // 맵 준비되면 대기 중이던 마커 표시
                if (dateLocationEvents.isNotEmpty()) {
                    displayMapMarkers(dateLocationEvents)
                } else {
                    val defaultLat = LocationTracker.currentLatitude ?: 37.402056
                    val defaultLng = LocationTracker.currentLongitude ?: 127.108212
                    _binding?.mapWebView?.evaluateJavascript(
                        "moveCamera($defaultLat, $defaultLng, 5)", null
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // 지도에 졸음 발생 위치 마커 표시 (WebView JS 호출)
    // ─────────────────────────────────────────
    private fun displayMapMarkers(events: List<DrowsyEvent>) {
        val webView = _binding?.mapWebView ?: return
        if (!isMapReady) return

        webView.evaluateJavascript("clearMarkers()", null)

        events.forEach { event ->
            val lat = event.latitude ?: return@forEach
            val lng = event.longitude ?: return@forEach
            val time = event.timestamp.substringAfter("T").take(5)
            webView.evaluateJavascript(
                "addMarker($lat, $lng, ${event.drowsy_level}, '$time')", null
            )
        }

        // 마커들이 모두 보이도록 지도 영역 자동 조정
        webView.evaluateJavascript("fitBounds()", null)
    }

    // ─────────────────────────────────────────
    // 탭 1: 날짜별 리포트 API 로드
    // ─────────────────────────────────────────
    private fun loadReportData() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dateStr = sdf.format(selectedCalendar.time)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                showLoading(true)
                val response = RetrofitClient.apiService(requireContext()).getReportByDate(
                    RetrofitClient.authHeader(token),
                    dateStr
                )
                bindReportData(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    handleSessionExpired()
                } else if (e.code() == 404) {
                    // 해당 날짜에 기록이 없는 경우: 에러가 아니라 0건 정상 데이터로 지도와 카드를 표시
                    bindEmptyDateData(dateStr)
                } else {
                    showError("서버 오류 (${e.code()})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadReportData error", e)
                // 네트워크 등 예외 발생 시에도 지도는 항상 유지
                bindEmptyDateData(dateStr)
            } finally {
                if (_binding != null) showLoading(false)
            }
        }
    }

    /** 해당 날짜에 서버 기록이 없을 때 지도와 점수를 온전히 보여주는 기본 바인딩 */
    private fun bindEmptyDateData(dateStr: String) {
        val emptyData = ReportByDateResponse(
            date = dateStr,
            session_count = 0,
            safety_score = 100,
            grade = "safe",
            total_drowsy_count = 0,
            level1_count = 0,
            level2_count = 0,
            level3_count = 0,
            most_dangerous_time = null,
            chart_data = emptyList(),
            events = emptyList()
        )
        bindReportData(emptyData)
    }

    private fun bindReportData(data: ReportByDateResponse) {
        val b = _binding ?: return
        b.layoutSleepEmpty.visibility = View.GONE
        b.cardSafetyScore.visibility = View.VISIBLE
        b.cardChart.visibility = View.VISIBLE

        // 1. 안전 점수 & 등급
        b.tvSafetyScore.text = data.safety_score.toString()
        b.safetyScoreRing.setScore(data.safety_score, data.grade)

        val gradeText = when (data.grade) {
            "safe" -> "안전"
            "caution" -> "주의"
            "danger" -> "위험"
            else -> data.grade
        }
        b.tvSafetyGrade.text = gradeText
        when (data.grade) {
            "danger" -> {
                b.tvSafetyGrade.setBackgroundResource(R.drawable.bg_badge_danger)
                b.tvSafetyGrade.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_danger))
            }
            "caution" -> {
                b.tvSafetyGrade.setBackgroundResource(R.drawable.bg_badge_warn)
                b.tvSafetyGrade.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_warn))
            }
            else -> {
                b.tvSafetyGrade.setBackgroundResource(R.drawable.bg_badge_safe)
                b.tvSafetyGrade.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_safe))
            }
        }

        // 2. 통계 횟수
        b.tvTotalDrowsy.text = "${data.total_drowsy_count}회"
        b.tvLevel1Count.text = "${data.level1_count}회"
        b.tvLevel2Count.text = "${data.level2_count}회"
        b.tvLevel3Count.text = "${data.level3_count}회"
        b.tvDangerousTime.text = data.most_dangerous_time ?: "-"

        // 3. 차트 설정
        setupChart(data)

        // 4. [15~16차] 카카오맵 마커 표시 (WebView)
        dateLocationEvents = data.events.filter { it.latitude != null && it.longitude != null }
        b.tvMapEventCount.text = if (dateLocationEvents.isEmpty()) "위치 기록 없음" else "위치 ${dateLocationEvents.size}건"
        b.mapWebView.visibility = View.VISIBLE

        if (dateLocationEvents.isEmpty()) {
            b.tvMapEmpty.visibility = View.GONE
        } else {
            b.tvMapEmpty.visibility = View.GONE
            displayMapMarkers(dateLocationEvents)
        }

        // 5. 상세 이벤트 리스트
        val events = data.events.map { event ->
            val timePart = event.timestamp.substringAfter("T").take(5)
            val location = if (event.latitude != null && event.longitude != null)
                "${String.format(Locale.US, "%.4f", event.latitude)}, ${String.format(Locale.US, "%.4f", event.longitude)}"
            else "-"
            SleepEventItem(timePart, location, event.drowsy_level)
        }
        setupRecyclerView(events)
    }

    private fun setupChart(data: ReportByDateResponse) {
        val b = _binding ?: return
        val entries = data.chart_data.map { Entry(it.hour.toFloat(), it.max_level.toFloat()) }

        if (entries.isEmpty()) {
            b.lineChart.clear()
            b.lineChart.setNoDataText("표시할 시간별 데이터가 없습니다.")
            b.lineChart.invalidate()
            return
        }

        val dataSet = LineDataSet(entries, "졸음 감지").apply {
            color = ContextCompat.getColor(requireContext(), R.color.accent_primary)
            setCircleColors(data.chart_data.map {
                ContextCompat.getColor(requireContext(), when (it.max_level) {
                    3 -> R.color.stage_3
                    2 -> R.color.stage_2
                    1 -> R.color.stage_1
                    else -> R.color.status_safe
                })
            })
            lineWidth = 2f
            circleRadius = 7f
            setDrawValues(false)
            mode = LineDataSet.Mode.STEPPED
            fillColor = ContextCompat.getColor(requireContext(), R.color.accent_secondary)
            fillAlpha = 70
            setDrawFilled(true)
        }

        b.lineChart.apply {
            this.data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            setTouchEnabled(false)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = ContextCompat.getColor(requireContext(), R.color.text_hint)
                textSize = 10f
                gridColor = ContextCompat.getColor(requireContext(), R.color.divider)
                axisLineColor = ContextCompat.getColor(requireContext(), R.color.divider)
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${value.toInt()}시"
                }
            }

            axisLeft.apply {
                textColor = ContextCompat.getColor(requireContext(), R.color.text_hint)
                textSize = 10f
                gridColor = ContextCompat.getColor(requireContext(), R.color.divider)
                axisLineColor = ContextCompat.getColor(requireContext(), R.color.divider)
                axisMinimum = 0f
                axisMaximum = 3f
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = when (value.toInt()) {
                        1 -> "1단계"
                        2 -> "2단계"
                        3 -> "3단계"
                        else -> ""
                    }
                }
            }
            axisRight.isEnabled = false
            invalidate()
        }
    }

    private fun setupRecyclerView(events: List<SleepEventItem>) {
        val b = _binding ?: return
        if (events.isEmpty()) {
            b.layoutSleepEmpty.visibility = View.VISIBLE
            b.rvSleepEvents.visibility = View.GONE
        } else {
            b.layoutSleepEmpty.visibility = View.GONE
            b.rvSleepEvents.visibility = View.VISIBLE
            b.rvSleepEvents.apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = SleepEventAdapter(events)
                isNestedScrollingEnabled = false
            }
        }
    }

    // ─────────────────────────────────────────
    // 탭 2: [14차] 운전 세션 히스토리 로드 (GET /report/history)
    // ─────────────────────────────────────────
    private fun loadHistoryData() {
        val b = _binding ?: return
        b.historyProgressBar.visibility = View.VISIBLE
        b.layoutHistoryEmpty.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = RetrofitClient.apiService(requireContext())
                val response = api.getReportHistory(RetrofitClient.authHeader(token))
                bindHistoryData(response)
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    handleSessionExpired()
                } else if (e.code() == 404) {
                    bindHistoryData(ReportHistoryResponse(total_sessions = 0, sessions = emptyList()))
                } else {
                    b.layoutHistoryEmpty.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), ApiErrorMessage.fromHttpException(e), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadHistoryData error", e)
                bindHistoryData(ReportHistoryResponse(total_sessions = 0, sessions = emptyList()))
            } finally {
                _binding?.historyProgressBar?.visibility = View.GONE
            }
        }
    }

    private fun bindHistoryData(response: ReportHistoryResponse) {
        val b = _binding ?: return
        val sessions = response.sessions

        b.tvHistoryHeader.text = "전체 운전 세션 기록 (${response.total_sessions}건)"

        if (sessions.isEmpty()) {
            b.layoutHistoryEmpty.visibility = View.VISIBLE
            b.rvSessionHistory.visibility = View.GONE
        } else {
            b.layoutHistoryEmpty.visibility = View.GONE
            b.rvSessionHistory.visibility = View.VISIBLE
            b.rvSessionHistory.apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = SessionHistoryAdapter(sessions) { item ->
                    // 항목 클릭 시 상세 리포트 바텀시트 표출
                    SessionDetailBottomSheetFragment.newInstance(item.session_id)
                        .show(parentFragmentManager, "SessionDetail")
                }
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        _binding?.progressBar?.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (isLoading) _binding?.layoutSleepEmpty?.visibility = View.GONE
    }

    private fun showError(message: String? = null) {
        val b = _binding ?: return
        b.layoutSleepEmpty.visibility = View.VISIBLE
        b.tvSleepEmpty.text = message ?: "수면 데이터를 불러오지 못했습니다."
        b.btnRetrySleep.visibility = View.VISIBLE
        b.rvSleepEvents.visibility = View.GONE
        b.cardSafetyScore.visibility = View.GONE
        b.cardChart.visibility = View.GONE
        // 지도는 에러가 발생해도 항상 화면에 유지
        b.cardMapContainer.visibility = View.VISIBLE
    }

    private fun handleSessionExpired() {
        if (_binding == null) return
        sessionManager.clear()
        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        requireActivity().finish()
    }

    override fun onDestroyView() {
        _binding?.mapWebView?.destroy()
        _binding = null
        super.onDestroyView()
    }
}
