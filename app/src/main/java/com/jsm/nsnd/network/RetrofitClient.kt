package com.jsm.nsnd.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // TODO: Tailscale VPN IP로 교체
    private const val BASE_URL = "http://10.0.2.2:8000/"
    const val WS_BASE_URL = "ws://10.0.2.2:8000"

    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    // JWT 토큰 헤더 생성
    fun authHeader(token: String) = "Bearer $token"
}