package com.jsm.nsnd.ui.main

import android.content.res.Configuration
import android.os.Bundle
import android.view.MenuItem
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.OnBackPressedCallback
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.jsm.nsnd.R
import com.jsm.nsnd.databinding.ActivityMainBinding
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import com.jsm.nsnd.data.api.ApiClient
import com.jsm.nsnd.data.api.UserResponse
import com.jsm.nsnd.data.session.ServerConfig
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.ui.auth.LoginActivity
import com.jsm.nsnd.ui.common.ApiErrorMessage
import com.jsm.nsnd.ui.common.AppPermissions
import com.jsm.nsnd.service.DetectionForegroundService
import com.jsm.nsnd.data.local.DetectionMessageParser
import com.jsm.nsnd.ui.overlay.AlertOverlayActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    private lateinit var sessionManager: SessionManager
    private val authHandler = Handler(Looper.getMainLooper())
    private var authCheckInFlight = false
    private var loggingOut = false
    private val detectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val sessionId = intent?.getIntExtra(DetectionForegroundService.EXTRA_SESSION_ID, -1) ?: return
            if (sessionId != sessionManager.getActiveSessionId() ||
                !intent.getBooleanExtra(DetectionForegroundService.EXTRA_ALERT_REQUIRED, false)) return
            val sample = intent.getStringExtra(DetectionForegroundService.EXTRA_PAYLOAD)
                ?.let(DetectionMessageParser::parse) ?: return
            showDetectionAlert(sessionId, sample.stage)
        }
    }

    private fun showDetectionAlert(sessionId: Int, stage: Int) {
        if (loggingOut || !sessionManager.isLoggedIn() || stage !in 1..3 || AlertOverlayActivity.isOpen) return
        startActivity(Intent(this, AlertOverlayActivity::class.java).apply {
            putExtra(AlertOverlayActivity.EXTRA_STAGE, stage)
            putExtra(DetectionForegroundService.EXTRA_SESSION_ID, sessionId)
        })
    }
    private val authCheckRunnable = object : Runnable {
        override fun run() {
            validateActiveLogin()
            authHandler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 이상의 강제 edge-to-edge에서도 하단 탭을 시스템 영역 밖에 배치한다.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sessionManager = SessionManager(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(binding.sidebarLayout)) {
                    binding.drawerLayout.closeDrawer(binding.sidebarLayout)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setupBottomNavigationInsets()
        setupNavigation()
        setupSidebar()
        setupThemeButtons()
        setupSidebarActions()
        loadUserInfo()
    }

    /** 제스처/버튼 내비게이션 영역에 가려지지 않도록 하단 바 높이를 보정합니다. */
    private fun setupBottomNavigationInsets() {
        val baseHeight = (80 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { view, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updateLayoutParams { height = baseHeight + bottomInset }
            view.updatePadding(bottom = bottomInset)
            insets
        }
        ViewCompat.requestApplyInsets(binding.bottomNavigation)
    }

    // ─────────────────────────────────────────
    // 사이드바 사용자 정보 로드
    // ─────────────────────────────────────────
    private fun loadUserInfo() {
        ApiClient.authApi(this).me(sessionManager.getAuthHeader())
            .enqueue(object : Callback<UserResponse> {
                override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                    val user = response.body()
                    if (response.isSuccessful && user != null) {
                        binding.tvSidebarName.text = user.name
                        binding.tvSidebarId.text = user.username
                    } else if (response.code() == 401) {
                        forceLogout("다른 기기에서 로그인했거나 로그인 시간이 만료되었습니다.")
                    } else {
                        Toast.makeText(this@MainActivity, ApiErrorMessage.fromResponse(response), Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                    Toast.makeText(
                        this@MainActivity,
                        ApiErrorMessage.fromThrowable(t, "사용자 정보 확인"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, detectionReceiver,
            IntentFilter(DetectionForegroundService.ACTION_DETECTION_EVENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        DetectionForegroundService.latestDetection?.takeIf {
            it.account == sessionManager.getDetectionAccountKey() &&
                it.sessionId == sessionManager.getActiveSessionId() && it.alertRequired
        }?.let { showDetectionAlert(it.sessionId, it.stage) }
        authHandler.removeCallbacks(authCheckRunnable)
        authHandler.post(authCheckRunnable)
    }

    override fun onStop() {
        unregisterReceiver(detectionReceiver)
        authHandler.removeCallbacks(authCheckRunnable)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (AppPermissions.needsReview(this)) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
    }

    private fun validateActiveLogin() {
        if (authCheckInFlight || loggingOut || !sessionManager.isLoggedIn()) return
        authCheckInFlight = true
        ApiClient.authApi(this).me(sessionManager.getAuthHeader())
            .enqueue(object : Callback<UserResponse> {
                override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                    authCheckInFlight = false
                    if (response.code() == 401) {
                        forceLogout("다른 기기에서 로그인하여 이 기기의 로그인이 종료되었습니다.")
                    }
                }

                override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                    authCheckInFlight = false
                    // 일시적인 네트워크 단절은 자동 로그아웃 사유가 아닙니다.
                }
            })
    }

    private fun forceLogout(message: String) {
        if (loggingOut) return
        loggingOut = true
        sessionManager.clear()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // ─────────────────────────────────────────
    // 네비게이션 설정
    // ─────────────────────────────────────────
    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController

        // 하단 네비게이션 바와 NavController 연결
        binding.bottomNavigation.setupWithNavController(navController)

        // 현재 탭에 따라 툴바 타이틀 변경
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.tvToolbarTitle.text = when (destination.id) {
                R.id.homeFragment     -> getString(R.string.app_name)
                R.id.contactFragment  -> getString(R.string.nav_contact)
                R.id.massagerFragment -> getString(R.string.nav_massager)
                R.id.sleepDataFragment -> getString(R.string.nav_sleep)
                R.id.mapFragment       -> getString(R.string.nav_map)
                else -> getString(R.string.app_name)
            }
        }
    }

    /** Full-map exploration must use the same saved tab stacks as bottom navigation. */
    fun openMapTab(date: String? = null, serverRecords: Boolean = false) {
        binding.bottomNavigation.selectedItemId = R.id.mapFragment
        if (date != null) binding.bottomNavigation.post {
            if (navController.currentDestination?.id != R.id.mapFragment) return@post
            val host = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
            (host?.childFragmentManager?.primaryNavigationFragment as? com.jsm.nsnd.ui.map.MapFragment)
                ?.applyRecordSelection(date, serverRecords)
        }
    }

    // ─────────────────────────────────────────
    // 사이드바 설정
    // ─────────────────────────────────────────
    private fun setupSidebar() {
        // 사이드바는 스와이프로 열리지 않도록 잠금 (버튼으로만 열림)
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        // 우측 상단 계정 아이콘 클릭 시 사이드바 열기
        binding.btnAccount.setOnClickListener {
            binding.drawerLayout.openDrawer(binding.sidebarLayout)
        }
    }

    // ─────────────────────────────────────────
    // 테마 설정 버튼
    // ─────────────────────────────────────────
    private fun setupThemeButtons() {
        // 저장된 테마 설정 불러오기
        val prefs = getSharedPreferences("nsnd_prefs", MODE_PRIVATE)
        val savedMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)

        val currentMode = when (savedMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> ThemeMode.LIGHT
            AppCompatDelegate.MODE_NIGHT_YES -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
        highlightThemeButton(currentMode)

        binding.btnThemeSystem.setOnClickListener {
            prefs.edit().putInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM).apply()
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            highlightThemeButton(ThemeMode.SYSTEM)
        }
        binding.btnThemeLight.setOnClickListener {
            prefs.edit().putInt("theme_mode", AppCompatDelegate.MODE_NIGHT_NO).apply()
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            highlightThemeButton(ThemeMode.LIGHT)
        }
        binding.btnThemeDark.setOnClickListener {
            prefs.edit().putInt("theme_mode", AppCompatDelegate.MODE_NIGHT_YES).apply()
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            highlightThemeButton(ThemeMode.DARK)
        }
    }

    private fun highlightThemeButton(mode: ThemeMode) {
        val activeTextColor = getColor(R.color.accent_primary)
        val inactiveTextColor = getColor(R.color.text_secondary)
        val activeBg = getColor(R.color.accent_secondary)

        listOf(
            binding.btnThemeSystem to (mode == ThemeMode.SYSTEM),
            binding.btnThemeLight to (mode == ThemeMode.LIGHT),
            binding.btnThemeDark to (mode == ThemeMode.DARK)
        ).forEach { (btn, isActive) ->
            btn.setTextColor(if (isActive) activeTextColor else inactiveTextColor)
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (isActive) activeBg else android.graphics.Color.TRANSPARENT
            )
        }
    }

    // ─────────────────────────────────────────
    // 사이드바 액션 (로그아웃, 회원탈퇴)
    // ─────────────────────────────────────────
    private fun setupSidebarActions() {
        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.sidebar_logout))
                .setMessage(getString(R.string.sidebar_logout_confirm))
                .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                    ApiClient.authApi(this).logout(sessionManager.getAuthHeader())
                        .enqueue(object : Callback<Void> {
                            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                                if (response.isSuccessful || response.code() == 401) {
                                    forceLogout("로그아웃되었습니다.")
                                } else {
                                    Toast.makeText(
                                        this@MainActivity,
                                        ApiErrorMessage.fromResponse(response),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }

                            override fun onFailure(call: Call<Void>, t: Throwable) {
                                Toast.makeText(
                                    this@MainActivity,
                                    ApiErrorMessage.fromThrowable(t, "로그아웃"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        })
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        binding.btnServerConfig.setOnClickListener {
            ServerConfig.showEditDialog(this) {
                if (!sessionManager.isLoggedIn()) forceLogout("서버 주소가 변경되었습니다. 다시 로그인해주세요.")
            }
        }

        binding.btnWithdraw.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.sidebar_withdraw))
                .setMessage(getString(R.string.sidebar_withdraw_confirm))
                .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                    ApiClient.authApi(this).deleteMe(sessionManager.getAuthHeader())
                        .enqueue(object : Callback<Void> {
                            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                                if (response.isSuccessful) {
                                    loggingOut = true // Finish local cleanup before an in-flight auth check navigates away.
                                    lifecycleScope.launch {
                                        try {
                                            sessionManager.clearAfterWithdrawal()
                                        } catch (error: Exception) {
                                            sessionManager.clear()
                                            Toast.makeText(this@MainActivity, "회원탈퇴는 완료됐지만 로컬 기록 정리에 실패했습니다.", Toast.LENGTH_LONG).show()
                                        }
                                        startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                                        finish()
                                    }
                                } else {
                                    Toast.makeText(
                                        this@MainActivity,
                                        ApiErrorMessage.fromResponse(response),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }

                            override fun onFailure(call: Call<Void>, t: Throwable) {
                                Toast.makeText(
                                    this@MainActivity,
                                    ApiErrorMessage.fromThrowable(t, "회원탈퇴"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        })
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    // ─────────────────────────────────────────
    // 뒤로가기 시 사이드바 닫기
    // ─────────────────────────────────────────
    // 테마 모드 열거형
    private enum class ThemeMode { SYSTEM, LIGHT, DARK }
}
