package com.jsm.nsnd.ui.auth

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jsm.nsnd.data.api.ApiClient
import com.jsm.nsnd.data.api.RegisterRequest
import com.jsm.nsnd.data.api.UserResponse
import com.jsm.nsnd.databinding.ActivitySignupBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSignup.setOnClickListener {
            signup()
        }
    }

    private fun signup() {
        val name = binding.etSignupName.text.toString().trim()
        val username = binding.etSignupId.text.toString().trim()
        val password = binding.etSignupPassword.text.toString().trim()
        val passwordConfirm = binding.etSignupPasswordConfirm.text.toString().trim()

        if (name.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "모든 정보를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }

        if (password != passwordConfirm) {
            Toast.makeText(this, "비밀번호가 일치하지 않습니다", Toast.LENGTH_SHORT).show()
            return
        }

        ApiClient.authApi(this).register(RegisterRequest(username, name, password))
            .enqueue(object : Callback<UserResponse> {
                override fun onResponse(
                    call: Call<UserResponse>,
                    response: Response<UserResponse>
                ) {
                    if (response.isSuccessful) {
                        Toast.makeText(
                            this@SignUpActivity,
                            "회원가입이 완료되었습니다",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    } else {
                        Toast.makeText(
                            this@SignUpActivity,
                            "회원가입에 실패했습니다",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                    Toast.makeText(
                        this@SignUpActivity,
                        "서버 연결에 실패했습니다",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }
}