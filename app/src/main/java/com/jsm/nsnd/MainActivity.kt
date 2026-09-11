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
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.jsm.nsnd.R
import com.jsm.nsnd.databinding.ActivityMainBinding
import android.content.Intent
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import com.jsm.nsnd.data.api.ApiClient
import com.jsm.nsnd.data.api.UserResponse
import com.jsm.nsnd.data.session.ServerConfig
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.ui.auth.LoginActivity
import com.jsm.nsnd.ui.common.ApiErrorMessage
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
        authHandler.removeCallbacks(authCheckRunnable)
        authHandler.post(authCheckRunnable)
    }

    override fun onStop() {
        authHandler.removeCallbacks(authCheckRunnable)
        super.onStop()
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
                else -> getString(R.string.app_name)
            }
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
            ServerConfig.showEditDialog(this)
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
                                    sessionManager.clear()
                                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                                    finish()
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
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(binding.sidebarLayout)) {
            binding.drawerLayout.closeDrawer(binding.sidebarLayout)
        } else {
            super.onBackPressed()
        }
    }

    // 테마 모드 열거형
    private enum class ThemeMode { SYSTEM, LIGHT, DARK }
}
