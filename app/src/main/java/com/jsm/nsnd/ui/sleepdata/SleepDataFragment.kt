package com.jsm.nsnd.ui.sleepdata

import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.jsm.nsnd.R
import com.jsm.nsnd.databinding.FragmentSleepDataBinding
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.network.model.ReportSummaryResponse
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SleepDataFragment : Fragment() {

    private var _binding: FragmentSleepDataBinding? = null
    private val binding get() = _binding!!

    private var selectedCalendar = Calendar.getInstance()

    // TODO: 로그인 연동 후 SharedPreferences에서 토큰 가져오기
    private val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIzIiwiZXhwIjoxNzgxNTg2MTY2fQ.jvKM8OaraA14jnuF4ykVyIYs6mMcpKXM-_uST0SDc6Y"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSleepDataBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDateDisplay()
        setupCalendarButton()
        loadReportData()
    }

    // ─────────────────────────────────────────
    // 날짜 표시
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

    // ─────────────────────────────────────────
    // 캘린더 버튼
    // ─────────────────────────────────────────
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
    // API 데이터 로드
    // ─────────────────────────────────────────
    private fun loadReportData() {
        lifecycleScope.launch {
            try {
                showLoading(true)
                val response = RetrofitClient.apiService.getReportSummary(
                    RetrofitClient.authHeader(token)
                )
                bindReportData(response)
            } catch (e: Exception) {
                showError()
            } finally {
                showLoading(false)
            }
        }
    }

    // ─────────────────────────────────────────
    // 데이터 바인딩
    // ─────────────────────────────────────────
    private fun bindReportData(data: ReportSummaryResponse) {
        // 안전 점수
        binding.tvSafetyScore.text = "${data.safety_score}점"
        binding.tvSafetyGrade.text = when (data.grade) {
            "safe" -> "안전"
            "caution" -> "주의"
            "danger" -> "위험"
            else -> data.grade
        }

        // 졸음 횟수
        binding.tvTotalDrowsy.text = "${data.total_drowsy_count}회"
        binding.tvLevel1Count.text = "${data.level1_count}회"
        binding.tvLevel2Count.text = "${data.level2_count}회"
        binding.tvLevel3Count.text = "${data.level3_count}회"

        // 위험 시간대
        binding.tvDangerousTime.text = data.most_dangerous_time ?: "-"

        // 차트
        setupChart(data)

        // 이벤트 리스트
        val events = data.events.map { event ->
            val timePart = event.timestamp.substringAfter("T").take(5)
            val location = if (event.latitude != null && event.longitude != null)
                "${event.latitude}, ${event.longitude}"
            else "-"
            SleepEventItem(timePart, location, event.drowsy_level)
        }
        setupRecyclerView(events)
    }

    // ─────────────────────────────────────────
    // 차트 설정
    // ─────────────────────────────────────────
    private fun setupChart(data: ReportSummaryResponse) {
        val entries = data.chart_data.map { Entry(it.hour.toFloat(), it.count.toFloat()) }

        if (entries.isEmpty()) return

        val dataSet = LineDataSet(entries, "졸음 감지").apply {
            color = requireContext().getColor(R.color.accent_primary)
            setCircleColor(requireContext().getColor(R.color.accent_light))
            lineWidth = 2f
            circleRadius = 8f
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
            fillColor = requireContext().getColor(R.color.accent_secondary)
            fillAlpha = 80
            setDrawFilled(true)
        }

        binding.lineChart.apply {
            this.data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            setTouchEnabled(false)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = requireContext().getColor(R.color.text_hint)
                textSize = 10f
                gridColor = requireContext().getColor(R.color.divider)
                axisLineColor = requireContext().getColor(R.color.divider)
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${value.toInt()}시"
                }
            }

            axisLeft.apply {
                textColor = requireContext().getColor(R.color.text_hint)
                textSize = 10f
                gridColor = requireContext().getColor(R.color.divider)
                axisLineColor = requireContext().getColor(R.color.divider)
                axisMinimum = 0f
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${value.toInt()}회"
                }
            }
            axisRight.isEnabled = false
            invalidate()
        }
    }

    // ─────────────────────────────────────────
    // RecyclerView 설정
    // ─────────────────────────────────────────
    private fun setupRecyclerView(events: List<SleepEventItem>) {
        if (events.isEmpty()) {
            binding.tvSleepEmpty.visibility = View.VISIBLE
            binding.rvSleepEvents.visibility = View.GONE
        } else {
            binding.tvSleepEmpty.visibility = View.GONE
            binding.rvSleepEvents.visibility = View.VISIBLE
            binding.rvSleepEvents.apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = SleepEventAdapter(events)
                isNestedScrollingEnabled = false
            }
        }
    }

    // ─────────────────────────────────────────
    // 로딩 / 에러 처리
    // ─────────────────────────────────────────
    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun showError() {
        binding.tvSleepEmpty.visibility = View.VISIBLE
        binding.tvSleepEmpty.text = "데이터를 불러오지 못했습니다."
        binding.rvSleepEvents.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}