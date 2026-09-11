package com.jsm.nsnd.ui.auth

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.jsm.nsnd.R
import com.jsm.nsnd.data.api.ApiClient
import com.jsm.nsnd.data.api.LoginRequest
import com.jsm.nsnd.data.api.TokenResponse
import com.jsm.nsnd.data.session.ServerConfig
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.databinding.ActivityLoginBinding
import com.jsm.nsnd.ui.main.MainActivity
import com.jsm.nsnd.ui.common.ApiErrorMessage
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: SessionManager
    private var startupContinued = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val missing = missingRuntimePermissions()
        if (missing.isEmpty()) {
            checkFullScreenAlertPermission()
        } else {
            showRuntimePermissionRequiredDialog(missing)
        }
    }

    private val appSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        startRequiredPermissionFlow()
    }

    private val fullScreenSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (canUseFullScreenAlert()) {
            continueStartup()
        } else {
            showFullScreenPermissionRequiredDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 11 이하에서 시작 창이 사라진 뒤에는 정상 앱 테마를 사용합니다.
        setTheme(R.style.Theme_NSND)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        binding.btnLogin.setOnClickListener {
            login()
        }

        binding.btnGoSignup.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }

        binding.btnServerConfig.setOnClickListener {
            ServerConfig.showEditDialog(this)
        }

        // 미승인 필수 권한을 먼저 확인한 뒤 자동 로그인을 진행합니다.
        startRequiredPermissionFlow()
    }

    private fun startRequiredPermissionFlow() {
        val missing = missingRuntimePermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            checkFullScreenAlertPermission()
        }
    }

    private fun missingRuntimePermissions(): List<String> = buildList {
        if (ContextCompat.checkSelfPermission(
                this@LoginActivity,
                Manifest.permission.SEND_SMS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            add(Manifest.permission.SEND_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this@LoginActivity,
                Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showRuntimePermissionRequiredDialog(missing: List<String>) {
        val names = buildList {
            if (Manifest.permission.SEND_SMS in missing) add("긴급 SMS 발송")
            if (Manifest.permission.POST_NOTIFICATIONS in missing) add("백그라운드 경보 알림")
        }.joinToString(", ")
        AlertDialog.Builder(this)
            .setTitle("필수 권한이 필요합니다")
            .setMessage("NSND의 안전 기능을 사용하려면 다음 권한을 허용해야 합니다.\n\n$names")
            .setCancelable(false)
            .setPositiveButton("앱 설정 열기") { _, _ ->
                appSettingsLauncher.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
            .setNegativeButton("앱 종료") { _, _ -> finishAffinity() }
            .show()
    }

    private fun checkFullScreenAlertPermission() {
        if (canUseFullScreenAlert()) {
            continueStartup()
        } else {
            showFullScreenPermissionRequiredDialog()
        }
    }

    private fun canUseFullScreenAlert(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }
    }

    private fun showFullScreenPermissionRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("전체 화면 경보 허용")
            .setMessage(
                "화면이 꺼져 있거나 다른 앱을 사용하는 동안 졸음 경보를 표시하려면 " +
                    "NSND의 전체 화면 알림을 허용해주세요."
            )
            .setCancelable(false)
            .setPositiveButton("권한 설정") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    fullScreenSettingsLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } else {
                    continueStartup()
                }
            }
            .setNegativeButton("앱 종료") { _, _ -> finishAffinity() }
            .show()
    }

    private fun continueStartup() {
        if (startupContinued) return
        startupContinued = true
        if (sessionManager.isLoggedIn()) {
            validateSavedLogin()
        }
    }

    private fun login() {
        val username = binding.etLoginId.text.toString().trim()
        val password = binding.etLoginPassword.text.toString().trim()

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "아이디와 비밀번호를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnLogin.isEnabled = false
        ApiClient.authApi(this).login(LoginRequest(username, password, deviceId()))
            .enqueue(object : Callback<TokenResponse> {
                override fun onResponse(call: Call<TokenResponse>, response: Response<TokenResponse>) {
                    binding.btnLogin.isEnabled = true
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        sessionManager.saveToken(body.access_token)
                        goMain()
                    } else {
                        Toast.makeText(this@LoginActivity, ApiErrorMessage.fromResponse(response), Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<TokenResponse>, t: Throwable) {
                    binding.btnLogin.isEnabled = true
                    Toast.makeText(this@LoginActivity, ApiErrorMessage.fromThrowable(t, "로그인"), Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun validateSavedLogin() {
        ApiClient.authApi(this).me(sessionManager.getAuthHeader())
            .enqueue(object : Callback<com.jsm.nsnd.data.api.UserResponse> {
                override fun onResponse(
                    call: Call<com.jsm.nsnd.data.api.UserResponse>,
                    response: Response<com.jsm.nsnd.data.api.UserResponse>
                ) {
                    if (response.isSuccessful) {
                        goMain()
                    } else {
                        sessionManager.clear()
                        Toast.makeText(
                            this@LoginActivity,
                            ApiErrorMessage.fromResponse(response),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onFailure(call: Call<com.jsm.nsnd.data.api.UserResponse>, t: Throwable) {
                    Toast.makeText(
                        this@LoginActivity,
                        ApiErrorMessage.fromThrowable(t, "로그인 상태 확인"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    private fun deviceId(): String = Settings.Secure.getString(
        contentResolver,
        Settings.Secure.ANDROID_ID
    ) ?: "android-unknown"

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
