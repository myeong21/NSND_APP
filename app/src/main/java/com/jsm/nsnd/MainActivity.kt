package com.jsm.nsnd.ui.main

import android.content.res.Configuration
import android.os.Bundle
import android.view.MenuItem
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        setupSidebar()
        setupThemeButtons()
        setupSidebarActions()

        // TODO: 로그인 연동 후 실제 사용자 정보로 교체
        binding.tvSidebarName.text = "사용자"
        binding.tvSidebarId.text = "user@nsnd"
        binding.tvAvatarInitial.text = "U"
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
                    // TODO: 로그인 연동 후 로그아웃 처리 및 LoginActivity로 이동
                    binding.drawerLayout.closeDrawer(binding.sidebarLayout)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        binding.btnWithdraw.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.sidebar_withdraw))
                .setMessage(getString(R.string.sidebar_withdraw_confirm))
                .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                    // TODO: 로그인 연동 후 회원탈퇴 처리 및 LoginActivity로 이동
                    binding.drawerLayout.closeDrawer(binding.sidebarLayout)
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