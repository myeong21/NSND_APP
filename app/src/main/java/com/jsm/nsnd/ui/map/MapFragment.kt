package com.jsm.nsnd.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.jsm.nsnd.R
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.databinding.FragmentMapBinding
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.network.model.DrowsyEvent
import com.jsm.nsnd.network.model.ReportByDateResponse
import com.jsm.nsnd.service.LocationTracker
import com.jsm.nsnd.ui.auth.LoginActivity
import com.jsm.nsnd.ui.common.ApiErrorMessage
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MapFragment : Fragment() {

    companion object {
        private const val TAG = "MapFragment"
        private const val KAKAO_JS_KEY = "b560c7f8d8f2d3c455c837c3f33df7e2"
    }

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private var kakaoMapInstance: KakaoMap? = null
    private var isNativeMapReady = false
    private var isWebViewReady = false
    private var isUsingWebViewFallback = false

    private var selectedCalendar = Calendar.getInstance()
    private var locationEvents: List<DrowsyEvent> = emptyList()
    private val markerEventMap = mutableMapOf<Label, DrowsyEvent>()

    private val sessionManager by lazy { SessionManager(requireContext()) }
    private val token: String get() = sessionManager.getToken().orEmpty()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            moveToCurrentLocation()
        } else {
            Toast.makeText(requireContext(), "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDateSelector()
        setupFabControls()
        setupDetailCard()

        // 1. 서버 데이터 먼저 조회 (지도 준비 여부와 상관없이 통계 및 마커 데이터 즉시 표시)
        loadMapReportData()

        // 2. 카카오 네이티브 맵 시도 (키 해시 오류 시 웹뷰로 자동 폴백)
        setupKakaoMap()
    }

    // ─────────────────────────────────────────
    // 1. 카카오맵 SDK (네이티브 v2)
    // ─────────────────────────────────────────
    private fun setupKakaoMap() {
        binding.mapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() {
                    kakaoMapInstance = null
                    isNativeMapReady = false
                    markerEventMap.clear()
                    Log.i(TAG, "KakaoMap Native Destroyed")
                }

                override fun onMapError(e: Exception) {
                    Log.w(TAG, "KakaoMap Native Init Error -> WebView Fallback: ${e.message}")
                    isNativeMapReady = false
                    kakaoMapInstance = null
                    activity?.runOnUiThread {
                        if (_binding != null) {
                            activateWebViewFallback()
                        }
                    }
                }
            },
            object : KakaoMapReadyCallback() {
                override fun onMapReady(kakaoMap: KakaoMap) {
                    kakaoMapInstance = kakaoMap
                    isNativeMapReady = true
                    Log.i(TAG, "KakaoMap Native Ready")

                    activity?.runOnUiThread {
                        val b = _binding ?: return@runOnUiThread
                        if (!isUsingWebViewFallback) {
                            b.mapView.visibility = View.VISIBLE
                            b.mapWebView.visibility = View.GONE
                        }

                        // 맵 클릭 시 상세 카드 닫기
                        kakaoMap.setOnMapClickListener { _, _, _, _ ->
                            _binding?.cardMarkerDetail?.visibility = View.GONE
                        }

                        // 마커 클릭 리스너 연결
                        kakaoMap.setOnLabelClickListener { _, _, label ->
                            val event = markerEventMap[label]
                            if (event != null && _binding != null) {
                                showEventDetail(event)
                                val lat = event.latitude
                                val lng = event.longitude
                                if (lat != null && lng != null) {
                                    kakaoMap.moveCamera(
                                        CameraUpdateFactory.newCenterPosition(LatLng.from(lat, lng), 16)
                                    )
                                }
                            }
                            true
                        }

                        if (locationEvents.isNotEmpty() && !isUsingWebViewFallback) {
                            displayMarkersOnNativeMap(kakaoMap)
                            focusAllMarkersOnNativeMap(kakaoMap)
                        }
                    }
                }
            }
        )
    }

    // ─────────────────────────────────────────
    // 2. 웹뷰 기반 카카오맵 폴백 (키 해시 미등록 환경에서도 100% 정상 작동)
    // ─────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun activateWebViewFallback() {
        val b = _binding ?: return
        if (isUsingWebViewFallback && b.mapWebView.visibility == View.VISIBLE) return
        isUsingWebViewFallback = true

        b.mapView.visibility = View.GONE
        b.mapWebView.visibility = View.VISIBLE

        val webView = b.mapWebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.addJavascriptInterface(WebViewMapBridge(), "AndroidBridge")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                Log.d(TAG, "[WebView JS] ${consoleMessage?.message()}")
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "KakaoMap WebView fallback page loaded")
            }
        }

        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load kakao_map.html", e)
        }
    }

    inner class WebViewMapBridge {
        @JavascriptInterface
        fun onMapReady() {
            activity?.runOnUiThread {
                isWebViewReady = true
                Log.i(TAG, "KakaoMap WebView API ready")
                if (locationEvents.isNotEmpty()) {
                    displayMarkersOnWebView()
                } else {
                    val defaultLat = LocationTracker.currentLatitude ?: 37.402056
                    val defaultLng = LocationTracker.currentLongitude ?: 127.108212
                    _binding?.mapWebView?.evaluateJavascript("moveCamera($defaultLat, $defaultLng, 5)", null)
                }
            }
        }

        @JavascriptInterface
        fun onMarkerClick(level: Int, time: String, lat: Double, lng: Double) {
            activity?.runOnUiThread {
                val matching = locationEvents.find {
                    it.latitude != null && Math.abs(it.latitude - lat) < 0.0001 &&
                            it.longitude != null && Math.abs(it.longitude - lng) < 0.0001
                } ?: DrowsyEvent(
                    timestamp = if (time.contains("T")) time else "2026-09-20T$time:00",
                    drowsy_level = level,
                    latitude = lat,
                    longitude = lng
                )
                showEventDetail(matching)
            }
        }
    }

    // ─────────────────────────────────────────
    // 3. 날짜 선택 및 갱신 UI
    // ─────────────────────────────────────────
    private fun setupDateSelector() {
        updateDateDisplay()

        binding.btnDateSelector.setOnClickListener {
            val year = selectedCalendar.get(Calendar.YEAR)
            val month = selectedCalendar.get(Calendar.MONTH)
            val day = selectedCalendar.get(Calendar.DAY_OF_MONTH)

            DatePickerDialog(requireContext(), { _, y, m, d ->
                selectedCalendar.set(y, m, d)
                updateDateDisplay()
                loadMapReportData()
            }, year, month, day).show()
        }

        binding.btnRefreshMap.setOnClickListener {
            loadMapReportData()
        }
    }

    private fun updateDateDisplay() {
        val today = Calendar.getInstance()
        val isToday = selectedCalendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                selectedCalendar.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)

        val sdf = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREAN)
        val dateStr = sdf.format(selectedCalendar.time)
        binding.tvMapDate.text = if (isToday) "$dateStr (오늘)" else dateStr
    }

    // ─────────────────────────────────────────
    // 4. 플로팅 액션 버튼 (FAB) 이벤트
    // ─────────────────────────────────────────
    private fun setupFabControls() {
        // 모든 마커 한눈에 보기 (카메라 재정렬)
        binding.btnRecenter.setOnClickListener {
            if (locationEvents.isEmpty()) {
                Toast.makeText(requireContext(), "표시할 위치 기록이 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isNativeMapReady && kakaoMapInstance != null) {
                focusAllMarkersOnNativeMap(kakaoMapInstance!!)
            } else if (isWebViewReady) {
                binding.mapWebView.evaluateJavascript("fitBounds();", null)
            }
        }

        // 내 위치로 이동
        binding.btnMyLocation.setOnClickListener {
            if (hasLocationPermission()) {
                moveToCurrentLocation()
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun moveToCurrentLocation() {
        // 1) LocationTracker에 캐시된 위치 우선 확인
        val cachedLat = LocationTracker.currentLatitude
        val cachedLng = LocationTracker.currentLongitude
        if (cachedLat != null && cachedLng != null) {
            applyLocationToMap(cachedLat, cachedLng)
            return
        }

        // 2) FusedLocationProviderClient에서 마지막 위치 조회
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(requireContext())
            fusedClient.lastLocation.addOnSuccessListener { location ->
                if (location != null && _binding != null) {
                    applyLocationToMap(location.latitude, location.longitude)
                } else {
                    Toast.makeText(requireContext(), "현재 위치를 확인할 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener {
                Toast.makeText(requireContext(), "위치 정보를 가져오는 데 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "위치 권한 오류", e)
        }
    }

    private fun applyLocationToMap(lat: Double, lng: Double) {
        if (isNativeMapReady && kakaoMapInstance != null) {
            kakaoMapInstance?.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(lat, lng), 16))
        } else if (isWebViewReady) {
            binding.mapWebView.evaluateJavascript("moveCamera($lat, $lng, 4);", null)
        }
    }

    // ─────────────────────────────────────────
    // 5. 하단 미니 상세 카드
    // ─────────────────────────────────────────
    private fun setupDetailCard() {
        binding.btnCloseDetail.setOnClickListener {
            binding.cardMarkerDetail.visibility = View.GONE
        }
    }

    private fun showEventDetail(event: DrowsyEvent) {
        val b = _binding ?: return
        b.cardMarkerDetail.visibility = View.VISIBLE

        val (badgeBg, stageColor, stageTitle, statusDesc, descColor) = when (event.drowsy_level) {
            3 -> Tuple5(
                R.drawable.bg_badge_stage3,
                R.color.stage_3,
                "3단계 위험 감지",
                "고위험 졸음 발생",
                R.color.status_danger
            )
            2 -> Tuple5(
                R.drawable.bg_badge_stage2,
                R.color.stage_2,
                "2단계 경고 감지",
                "주의 필요 상태",
                R.color.status_warn
            )
            else -> Tuple5(
                R.drawable.bg_badge_stage1,
                R.color.stage_1,
                "1단계 주의 감지",
                "가벼운 피로 징후",
                R.color.status_warn
            )
        }

        b.tvDetailStageBadge.setBackgroundResource(badgeBg)
        b.tvDetailStageBadge.setTextColor(ContextCompat.getColor(requireContext(), stageColor))
        b.tvDetailStageBadge.text = stageTitle

        val formattedTime = event.timestamp.replace("T", " ").take(19)
        b.tvDetailTime.text = formattedTime

        val lat = event.latitude ?: 0.0
        val lng = event.longitude ?: 0.0
        b.tvDetailCoords.text = String.format(Locale.US, "위도: %.4f · 경도: %.4f", lat, lng)

        b.tvDetailStatusDesc.text = statusDesc
        b.tvDetailStatusDesc.setTextColor(ContextCompat.getColor(requireContext(), descColor))
    }

    // ─────────────────────────────────────────
    // 6. 서버 리포트 데이터 조회 및 마커 표출
    // ─────────────────────────────────────────
    private fun loadMapReportData() {
        val b = _binding ?: return
        val apiDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selectedCalendar.time)

        b.progressMapLoading.visibility = View.VISIBLE
        b.cardMarkerDetail.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = RetrofitClient.apiService(requireContext())
                val response = api.getReportByDate(
                    RetrofitClient.authHeader(token),
                    apiDateStr
                )
                bindReportData(response)
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    handleSessionExpired()
                } else if (e.code() == 404) {
                    bindEmptyData()
                } else {
                    Log.e(TAG, "HTTP 오류", e)
                    bindEmptyData()
                    Toast.makeText(requireContext(), ApiErrorMessage.fromHttpException(e), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "데이터 조회 오류", e)
                bindEmptyData()
            } finally {
                _binding?.progressMapLoading?.visibility = View.GONE
            }
        }
    }

    private fun bindEmptyData() {
        val empty = ReportByDateResponse(
            date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selectedCalendar.time),
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
        bindReportData(empty)
    }

    private fun bindReportData(data: ReportByDateResponse) {
        val b = _binding ?: return

        // 1) 상단 통계 요약 갱신
        b.tvTotalCount.text = "총 ${data.total_drowsy_count}건 감지: "
        b.badgeStage1.text = "🟡 ${data.level1_count}"
        b.badgeStage2.text = "🟠 ${data.level2_count}"
        b.badgeStage3.text = "🔴 ${data.level3_count}"

        // 2) 유효 위경도 이벤트 필터링
        locationEvents = data.events.filter { it.latitude != null && it.longitude != null }

        if (locationEvents.isEmpty()) {
            b.cardEmptyNotice.visibility = View.VISIBLE
            if (isNativeMapReady && kakaoMapInstance != null) {
                clearNativeMarkers(kakaoMapInstance!!)
                val defaultLat = LocationTracker.currentLatitude ?: 37.402056
                val defaultLng = LocationTracker.currentLongitude ?: 127.108212
                kakaoMapInstance?.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(defaultLat, defaultLng), 14))
            } else if (isWebViewReady) {
                b.mapWebView.evaluateJavascript("clearMarkers();", null)
            }
        } else {
            b.cardEmptyNotice.visibility = View.GONE
            if (isNativeMapReady && kakaoMapInstance != null) {
                displayMarkersOnNativeMap(kakaoMapInstance!!)
                focusAllMarkersOnNativeMap(kakaoMapInstance!!)
            } else if (isWebViewReady) {
                displayMarkersOnWebView()
            }
        }
    }

    // ─────────────────────────────────────────
    // 네이티브 마커 처리
    // ─────────────────────────────────────────
    private fun clearNativeMarkers(map: KakaoMap) {
        markerEventMap.clear()
        val labelManager = map.labelManager ?: return
        val layer = labelManager.layer ?: return
        layer.removeAll()
    }

    private fun displayMarkersOnNativeMap(map: KakaoMap) {
        clearNativeMarkers(map)
        if (locationEvents.isEmpty()) return

        val labelManager = map.labelManager ?: return
        val layer = labelManager.layer ?: return

        locationEvents.forEach { event ->
            val lat = event.latitude ?: return@forEach
            val lng = event.longitude ?: return@forEach

            val markerRes = when (event.drowsy_level) {
                3 -> R.drawable.ic_marker_stage3
                2 -> R.drawable.ic_marker_stage2
                else -> R.drawable.ic_marker_stage1
            }

            val style = LabelStyles.from(LabelStyle.from(markerRes))
            val position = LatLng.from(lat, lng)

            val label = layer.addLabel(LabelOptions.from(position).setStyles(style))
            if (label != null) {
                markerEventMap[label] = event
            }
        }
    }

    private fun focusAllMarkersOnNativeMap(map: KakaoMap) {
        if (locationEvents.isEmpty()) return

        var sumLat = 0.0
        var sumLng = 0.0
        locationEvents.forEach {
            sumLat += (it.latitude ?: 0.0)
            sumLng += (it.longitude ?: 0.0)
        }

        val centerLat = sumLat / locationEvents.size
        val centerLng = sumLng / locationEvents.size

        val cameraUpdate = CameraUpdateFactory.newCenterPosition(
            LatLng.from(centerLat, centerLng),
            14
        )
        map.moveCamera(cameraUpdate)
    }

    // ─────────────────────────────────────────
    // 웹뷰 마커 처리
    // ─────────────────────────────────────────
    private fun displayMarkersOnWebView() {
        val b = _binding ?: return
        b.mapWebView.evaluateJavascript("clearMarkers();", null)

        locationEvents.forEach { event ->
            val lat = event.latitude ?: return@forEach
            val lng = event.longitude ?: return@forEach
            val time = event.timestamp.substringAfter("T").take(5)
            val js = "addMarker($lat, $lng, ${event.drowsy_level}, '$time');"
            b.mapWebView.evaluateJavascript(js, null)
        }

        b.mapWebView.evaluateJavascript("fitBounds();", null)
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

    // ─────────────────────────────────────────
    // 7. Fragment 수명주기와 MapView/WebView 동기화
    // ─────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        _binding?.mapView?.resume()
        _binding?.mapWebView?.onResume()
    }

    override fun onPause() {
        _binding?.mapView?.pause()
        _binding?.mapWebView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        _binding?.mapView?.finish()
        _binding?.mapWebView?.destroy()
        _binding = null
        super.onDestroyView()
    }

    private data class Tuple5<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
}
