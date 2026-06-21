package com.jsm.nsnd.network

import android.content.Context
import com.jsm.nsnd.data.session.ServerConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var cachedApiService: ApiService? = null
    private var cachedBaseUrl: String? = null

    fun apiService(context: Context): ApiService {
        val baseUrl = ServerConfig.getHttpBaseUrl(context)
        if (cachedApiService == null || cachedBaseUrl != baseUrl) {
            cachedBaseUrl = baseUrl
            cachedApiService = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
        return cachedApiService!!
    }

    fun wsBaseUrl(context: Context): String = ServerConfig.getWsBaseUrl(context)

    // JWT 토큰 헤더 생성
    fun authHeader(token: String) = "Bearer $token"
}