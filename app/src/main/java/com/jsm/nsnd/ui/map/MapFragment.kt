package com.jsm.nsnd.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationManager
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
import androidx.core.location.LocationManagerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.jsm.nsnd.R
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.databinding.FragmentMapBinding
import com.jsm.nsnd.ui.common.MapRecord
import com.jsm.nsnd.ui.common.MapRecordDialogs
import com.jsm.nsnd.ui.common.ServerMapRecords
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.ui.common.ApiErrorMessage
import retrofit2.HttpException
import android.widget.AdapterView
import com.jsm.nsnd.data.local.LocalDetectionStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import com.google.android.gms.location.CurrentLocationRequest
import com.jsm.nsnd.service.LocationTracker
import com.jsm.nsnd.ui.auth.LoginActivity
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

    private var myLocationLabel: Label? = null
    private var pendingUserLocation: LatLng? = null
    private var isFetchingLocation = false
    private var currentUserFix: Location? = null
    private var locationCancellation: CancellationTokenSource? = null
    private var loadJob: Job? = null
    private var allLocalRecords: List<MapRecord> = emptyList()
    private var showServerRecords = false

    // true: 전체 누적 졸음 기록 모드, false: 특정 날짜 필터 모드
    private var isAllMode = true

    private var selectedCalendar = Calendar.getInstance()
    private var locationEvents: List<MapRecord> = emptyList()
    private val markerEventMap = mutableMapOf<Label, MapRecord>()

    private val sessionManager by lazy { SessionManager(requireContext()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            selectedCalendar.timeInMillis = it.getLong("map_selected_date", selectedCalendar.timeInMillis)
            showServerRecords = it.getBoolean("map_server_records", false)
            isAllMode = it.getBoolean("map_all_records", true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("map_selected_date", selectedCalendar.timeInMillis)
        outState.putBoolean("map_server_records", showServerRecords)
        outState.putBoolean("map_all_records", isAllMode)
        super.onSaveInstanceState(outState)
    }

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

        setupFilterControls()
        setupFabControls()
        setupDetailCard()
        binding.spinnerRecordSource.setSelection(if (showServerRecords) 1 else 0)
        binding.spinnerRecordSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (showServerRecords != (position == 1)) {
                    showServerRecords = position == 1
                    bindLocalRecords(emptyList())
                    loadMapReportData()
                }
            }
        }

        // 로컬 수신 기록만 조회. 서버 통계와 임의로 합산하지 않습니다.
        loadMapReportData()

        // 2. 카카오 네이티브 맵 시도 (키 해시 오류 시 웹뷰로 자동 폴백)
        setupKakaoMap()
        binding.btnLocalRecords.setOnClickListener {
            MapRecordDialogs.list(requireContext(), allLocalRecords, serverRecords = showServerRecords) { selectLocalRecord(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { LocalDetectionStore.changes.collect { if (!showServerRecords) loadMapReportData() } }
                launch {
                    while (true) { refreshUserMarker(); delay(5_000L) }
                }
            }
        }
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
                                MapRecordDialogs.selectAt(requireContext(), event, locationEvents) { selectLocalRecord(it) }
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
                        refreshUserMarker()
                    }
                }
            }
        )
    }

    // ─────────────────────────────────────────
    // 2. 웹뷰 기반 카카오맵 폴백 (JavaScript 키와 등록 도메인이 필요)
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
                    // 지도 초기 중심이며 현재 위치를 의미하지 않습니다.
                    val defaultLat = 37.402056
                    val defaultLng = 127.108212
                    _binding?.mapWebView?.evaluateJavascript("moveCamera($defaultLat, $defaultLng, 5)", null)
                }

                refreshUserMarker()


            }
        }

        @JavascriptInterface
        fun onLocalMarkerClick(recordId: String) {
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                val record = locationEvents.find { it.id == recordId } ?: return@runOnUiThread
                MapRecordDialogs.selectAt(requireContext(), record, locationEvents) { selectLocalRecord(it) }
            }
        }
    }


    // ─────────────────────────────────────────
    // 3. 필터 토글 (전체 기록 vs 날짜 선택) 및 갱신 UI
    // ─────────────────────────────────────────
    private fun setupFilterControls() {
        updateFilterUI()

        // 1) 전체 기록 모드 클릭
        binding.btnFilterAll.setOnClickListener {
            if (!isAllMode) {
                isAllMode = true
                updateFilterUI()
                loadMapReportData()
            }
        }

        // 2) 날짜별 선택 모드 클릭 (달력 다이얼로그)
        binding.btnFilterDate.setOnClickListener {
            val year = selectedCalendar.get(Calendar.YEAR)
            val month = selectedCalendar.get(Calendar.MONTH)
            val day = selectedCalendar.get(Calendar.DAY_OF_MONTH)

            DatePickerDialog(requireContext(), { _, y, m, d ->
                selectedCalendar.set(y, m, d)
                isAllMode = false
                updateFilterUI()
                loadMapReportData()
            }, year, month, day).show()
        }

        // 3) 새로고침
        binding.btnRefreshMap.setOnClickListener {
            loadMapReportData()
        }
    }

    private fun updateFilterUI() {
        val b = _binding ?: return
        if (isAllMode) {
            b.btnFilterAll.setBackgroundResource(R.drawable.bg_filter_pill_active)
            b.btnFilterAll.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))

            b.btnFilterDate.setBackgroundResource(R.drawable.bg_filter_pill_inactive)
            b.ivCalendarIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
            b.tvMapDate.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            b.tvMapDate.text = "날짜별 선택"
        } else {
            b.btnFilterAll.setBackgroundResource(R.drawable.bg_filter_pill_inactive)
            b.btnFilterAll.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

            b.btnFilterDate.setBackgroundResource(R.drawable.bg_filter_pill_active)
            b.ivCalendarIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.white)
            )
            b.tvMapDate.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))

            val sdf = SimpleDateFormat("yyyy.MM.dd", Locale.KOREAN)
            b.tvMapDate.text = sdf.format(selectedCalendar.time)
        }
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
                focusAllMarkersOnWebView()
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
        if (isFetchingLocation) return
        val cached = LocationTracker.freshLocation(requireContext())
        if (cached != null) { showCurrentFix(cached); return }
        currentUserFix = null
        refreshUserMarker()
        val manager = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager != null && !LocationManagerCompat.isLocationEnabled(manager)) {
            Toast.makeText(requireContext(), "위치 서비스를 켜주세요. 기록은 위치 없이 저장됩니다.", Toast.LENGTH_LONG).show()
            return
        }
        isFetchingLocation = true
        val cancellation = CancellationTokenSource()
        locationCancellation = cancellation
        val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(30_000L).setDurationMillis(5_000L).build()
        try {
            LocationServices.getFusedLocationProviderClient(requireContext())
                .getCurrentLocation(request, cancellation.token)
                .addOnCompleteListener { task ->
                    if (_binding == null || !isAdded || locationCancellation !== cancellation) return@addOnCompleteListener
                    isFetchingLocation = false
                    locationCancellation = null
                    val location = if (task.isSuccessful) task.result else null
                    if (location != null && LocationTracker.snapshotOf(location).latitude != null && hasLocationPermission()) {
                        showCurrentFix(location)
                    } else {
                        Toast.makeText(requireContext(), "최신 위치를 확인할 수 없습니다. 기본 중심은 내 위치가 아닙니다.", Toast.LENGTH_LONG).show()
                    }
                }
            viewLifecycleOwner.lifecycleScope.launch {
                delay(6_000L)
                if (locationCancellation === cancellation) {
                    cancellation.cancel(); locationCancellation = null; isFetchingLocation = false
                    Toast.makeText(requireContext(), "위치 수신 시간이 초과되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: SecurityException) {
            cancellation.cancel(); locationCancellation = null; isFetchingLocation = false
            Toast.makeText(requireContext(), "위치 권한을 확인해주세요.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCurrentFix(location: Location) {
        currentUserFix = Location(location)
        val lat = location.latitude
        val lng = location.longitude
        if (lat !in 33.0..39.0 || lng !in 124.0..132.0) {
            Toast.makeText(requireContext(), "한국 밖 위치입니다. 카카오맵 배경 표시가 제한될 수 있으며 원본 좌표를 사용합니다.", Toast.LENGTH_LONG).show()
        }
        refreshUserMarker()
        if (isNativeMapReady) kakaoMapInstance?.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(lat, lng), 15))
        else if (isWebViewReady) binding.mapWebView.evaluateJavascript("setUserLocation($lat, $lng, true);", null)
        else pendingUserLocation = LatLng.from(lat, lng)
    }

    private fun refreshUserMarker() {
        val b = _binding ?: return
        val previous = currentUserFix
        val location = if (previous == null) null else LocationTracker.freshLocation(requireContext())
            ?.takeIf { it.elapsedRealtimeNanos >= previous.elapsedRealtimeNanos } ?: previous
        currentUserFix = location
        val manager = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val valid = location != null && hasLocationPermission() && (manager == null || LocationManagerCompat.isLocationEnabled(manager)) &&
            LocationTracker.snapshotOf(location).latitude != null
        val layer = kakaoMapInstance?.labelManager?.layer
        myLocationLabel?.let { layer?.remove(it) }
        myLocationLabel = null
        if (!valid) {
            currentUserFix = null; pendingUserLocation = null
            if (isWebViewReady) b.mapWebView.evaluateJavascript("clearUserLocation();", null)
            return
        }
        val fix = location!!
        if (isNativeMapReady && layer != null) {
            myLocationLabel = layer.addLabel(LabelOptions.from(LatLng.from(fix.latitude, fix.longitude))
                .setStyles(LabelStyles.from(LabelStyle.from(R.drawable.ic_marker_my_location))))
        } else if (isWebViewReady) {
            b.mapWebView.evaluateJavascript("setUserLocation(${fix.latitude}, ${fix.longitude}, false);", null)
        }
    }

    private fun selectLocalRecord(record: MapRecord) {
        if (_binding == null) return
        showEventDetail(record)
        if (record.hasCoordinates) {
            if (isNativeMapReady) kakaoMapInstance?.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(record.latitude!!, record.longitude!!), 16))
            else if (isWebViewReady) binding.mapWebView.evaluateJavascript("moveCamera(${record.latitude}, ${record.longitude}, 4);", null)
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

    private fun showEventDetail(event: MapRecord) {
        val b = _binding ?: return
        b.cardMarkerDetail.visibility = View.VISIBLE
        val color = when (event.stage) { 3 -> R.color.stage_3; 2 -> R.color.stage_2; else -> R.color.stage_1 }
        b.tvDetailStageBadge.text = "${event.stage}단계 감지"
        b.tvDetailStageBadge.setTextColor(ContextCompat.getColor(requireContext(), color))
        b.tvDetailTime.text = "${event.timeLabel()} ${event.receiptText()}"
        b.tvDetailCoords.text = event.coordinatesText()
        b.tvDetailStatusDesc.text = if (event.id.startsWith("DEMO-")) "시연용 가상 기록" else if (event.isLocal) "로컬 기록" else "서버 기록"
        b.tvDetailMetrics.text = if (event.isLocal) "EAR ${event.ear ?: "없음"} · 예측 점수 ${event.predictionScore ?: "없음"}"
            else "EAR·예측 점수: 서버 리포트에서 미제공"
    }

    private fun loadMapReportData() {
        val b = _binding ?: return
        loadJob?.cancel()
        val account = sessionManager.getDetectionAccountKey()
        val all = isAllMode
        val serverMode = showServerRecords
        val dateString = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selectedCalendar.time)
        val auth = sessionManager.getAuthHeader()
        val day = selectedCalendar.clone() as Calendar
        day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0); day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0)
        val from = day.timeInMillis
        day.add(Calendar.DAY_OF_MONTH, 1)
        val until = day.timeInMillis
        bindLocalRecords(emptyList())
        b.progressMapLoading.visibility = View.VISIBLE
        b.cardMarkerDetail.visibility = View.GONE
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val records = when {
                    account == null -> emptyList()
                    serverMode -> ServerMapRecords.load(RetrofitClient.apiService(requireContext()), auth,
                        if (all) null else dateString)
                    else -> LocalDetectionStore.get(requireContext())
                        .query(account, if (all) null else from, if (all) null else until).map(MapRecord::fromLocal)
                }
                if (_binding == null || sessionManager.getDetectionAccountKey() != account) return@launch
                bindLocalRecords(records)
            } catch (e: CancellationException) { throw e }
            catch (e: HttpException) {
                if (sessionManager.getDetectionAccountKey() != account) return@launch
                if (e.code() == 401) handleSessionExpired()
                else if (e.code() == 404) bindLocalRecords(emptyList())
                else Toast.makeText(context, ApiErrorMessage.fromHttpException(e), Toast.LENGTH_LONG).show()
            }
            catch (e: Exception) {
                Log.e(TAG, "로컬 기록 조회 실패", e)
                Toast.makeText(context, if (serverMode) "서버 기록을 읽지 못했습니다. 연결 상태를 확인하세요." else "로컬 기록을 읽지 못했습니다.", Toast.LENGTH_LONG).show()
            } finally { _binding?.progressMapLoading?.visibility = View.GONE }
        }
    }

    private fun bindLocalRecords(records: List<MapRecord>) {
        val b = _binding ?: return
        allLocalRecords = records
        locationEvents = records.filter { it.hasCoordinates }
        val source = if (showServerRecords) "서버" else "로컬"
        b.tvTotalCount.text = "$source ${records.size}건: "
        b.badgeStage1.text = "🟡 ${records.count { it.stage == 1 }}"
        b.badgeStage2.text = "🟠 ${records.count { it.stage == 2 }}"
        b.badgeStage3.text = "🔴 ${records.count { it.stage == 3 }}"
        val timeSource = if (showServerRecords) "서버 기록 시각" else "앱 수신 시각"
        b.btnLocalRecords.text = "$source 목록 ${records.size}건 · 위치 없음 ${records.size - locationEvents.size}건 · $timeSource"
        b.cardEmptyNotice.visibility = if (locationEvents.isEmpty()) View.VISIBLE else View.GONE
        if (isNativeMapReady && kakaoMapInstance != null) {
            displayMarkersOnNativeMap(kakaoMapInstance!!)
            focusAllMarkersOnNativeMap(kakaoMapInstance!!)
        } else if (isWebViewReady) displayMarkersOnWebView()
        refreshUserMarker()
    }

    /** Carries sleep-report date/source through the existing bottom-tab navigation. */
    fun applyRecordSelection(date: String, serverRecords: Boolean) {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) ?: return
        selectedCalendar.time = parsed
        isAllMode = false
        showServerRecords = serverRecords
        _binding?.spinnerRecordSource?.setSelection(if (serverRecords) 1 else 0)
        if (_binding != null) { updateFilterUI(); loadMapReportData() }
    }



    // ─────────────────────────────────────────
    // 네이티브 마커 처리
    // ─────────────────────────────────────────
    private fun clearNativeMarkers(map: KakaoMap) {
        val labelManager = map.labelManager ?: return
        val layer = labelManager.layer ?: return
        markerEventMap.keys.toList().forEach { layer.remove(it) }
        markerEventMap.clear()
    }

    private fun displayMarkersOnNativeMap(map: KakaoMap) {
        clearNativeMarkers(map)
        if (locationEvents.isEmpty()) return

        val labelManager = map.labelManager ?: return
        val layer = labelManager.layer ?: return

        locationEvents.forEach { event ->
            val lat = event.latitude ?: return@forEach
            val lng = event.longitude ?: return@forEach

            val markerRes = when (event.stage) {
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

        val points = locationEvents.map { LatLng.from(it.latitude!!, it.longitude!!) }.toTypedArray()
        val topControl = _binding?.cardTopControl?.bottom ?: 0
        val mapHeight = _binding?.mapView?.height ?: 0
        val padding = (topControl + 24).coerceAtMost(mapHeight / 3).coerceAtLeast(80)
        val cameraUpdate = CameraUpdateFactory.fitMapPoints(points, padding, 16)
        map.moveCamera(cameraUpdate)
    }

    // ─────────────────────────────────────────
    // 웹뷰 마커 처리
    // ─────────────────────────────────────────
    private fun displayMarkersOnWebView() {
        val b = _binding ?: return
        b.mapWebView.evaluateJavascript("clearMarkers();", null)
        locationEvents.forEach { b.mapWebView.evaluateJavascript(it.markerJavascript(), null) }
        if (locationEvents.isNotEmpty()) focusAllMarkersOnWebView()
        refreshUserMarker()
    }

    private fun focusAllMarkersOnWebView() {
        val b = _binding ?: return
        b.cardTopControl.post {
            if (_binding !== b || !isWebViewReady || locationEvents.isEmpty()) return@post
            val topPx = b.cardTopControl.bottom
            b.mapWebView.evaluateJavascript("fitBounds(Math.ceil($topPx / (window.devicePixelRatio || 1)) + 24, 76, 64, 24);", null)
        }
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
        locationCancellation?.cancel()
        locationCancellation = null
        isFetchingLocation = false
        loadJob?.cancel()
        isWebViewReady = false
        isNativeMapReady = false
        isUsingWebViewFallback = false
        kakaoMapInstance = null
        markerEventMap.clear()
        myLocationLabel = null
        allLocalRecords = emptyList()
        locationEvents = emptyList()


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
