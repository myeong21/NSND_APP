package com.jsm.nsnd.ui.sleepdata

import android.app.DatePickerDialog
import android.content.Intent
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
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.databinding.FragmentSleepDataBinding
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.network.model.ReportByDateResponse
import com.jsm.nsnd.ui.auth.LoginActivity
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SleepDataFragment : Fragment() {

    private var _binding: FragmentSleepDataBinding? = null
    private val binding get() = _binding!!

    private var selectedCalendar = Calendar.getInstance()

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
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dateStr = sdf.format(selectedCalendar.time)

        lifecycleScope.launch {
            try {
                showLoading(true)
                val response = RetrofitClient.apiService(requireContext()).getReportByDate(
                    RetrofitClient.authHeader(token),
                    dateStr
                )
                bindReportData(response)
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    handleSessionExpired()
                } else {
                    showError()
                }
            } catch (e: Exception) {
                showError()
            } finally {
                showLoading(false)
            }
        }
    }

    // ─────────────────────────────────────────
// 세션 만료 처리: 로그아웃 + 로그인 화면 이동
// ─────────────────────────────────────────
    private fun handleSessionExpired() {
        if (_binding == null) return
        sessionManager.clear()
        val intent = Intent(requireContext(), LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        requireActivity().finish()
    }

    // ─────────────────────────────────────────
    // 데이터 바인딩
    // ─────────────────────────────────────────
    private fun bindReportData(data: ReportByDateResponse) {
        binding.cardSafetyScore.visibility = View.VISIBLE
        binding.cardChart.visibility = View.VISIBLE

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
    private fun setupChart(data: ReportByDateResponse) {
        val entries = data.chart_data.map { Entry(it.hour.toFloat(), it.max_level.toFloat()) }

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
        binding.tvSleepEmpty.text = "해당 날짜의 운전 기록이 없습니다."
        binding.rvSleepEvents.visibility = View.GONE
        binding.cardSafetyScore.visibility = View.GONE
        binding.cardChart.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}