package com.jsm.nsnd.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 11 이하에서 시작 창이 사라진 뒤에는 정상 앱 테마를 사용합니다.
        setTheme(R.style.Theme_NSND)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        if (sessionManager.isLoggedIn()) {
            goMain()
            return
        }

        binding.btnLogin.setOnClickListener {
            login()
        }

        binding.btnGoSignup.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }

        binding.btnServerConfig.setOnClickListener {
            ServerConfig.showEditDialog(this)
        }
    }

    private fun login() {
        val username = binding.etLoginId.text.toString().trim()
        val password = binding.etLoginPassword.text.toString().trim()

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "아이디와 비밀번호를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }

        ApiClient.authApi(this).login(LoginRequest(username, password))
            .enqueue(object : Callback<TokenResponse> {
                override fun onResponse(call: Call<TokenResponse>, response: Response<TokenResponse>) {
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        sessionManager.saveToken(body.access_token)
                        goMain()
                    } else {
                        Toast.makeText(this@LoginActivity, ApiErrorMessage.fromResponse(response), Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<TokenResponse>, t: Throwable) {
                    Toast.makeText(this@LoginActivity, ApiErrorMessage.fromThrowable(t, "로그인"), Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
