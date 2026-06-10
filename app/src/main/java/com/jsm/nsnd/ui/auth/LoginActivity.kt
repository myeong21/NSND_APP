package com.jsm.nsnd.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jsm.nsnd.data.api.ApiClient
import com.jsm.nsnd.data.api.LoginRequest
import com.jsm.nsnd.data.api.TokenResponse
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.databinding.ActivityLoginBinding
import com.jsm.nsnd.ui.main.MainActivity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    private fun login() {
        val username = binding.etLoginId.text.toString().trim()
        val password = binding.etLoginPassword.text.toString().trim()

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "아이디와 비밀번호를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }

        ApiClient.authApi.login(LoginRequest(username, password))
            .enqueue(object : Callback<TokenResponse> {
                override fun onResponse(call: Call<TokenResponse>, response: Response<TokenResponse>) {
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        sessionManager.saveToken(body.access_token)
                        goMain()
                    } else {
                        Toast.makeText(this@LoginActivity, "로그인에 실패했습니다", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<TokenResponse>, t: Throwable) {
                    Toast.makeText(this@LoginActivity, "서버 연결에 실패했습니다", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}