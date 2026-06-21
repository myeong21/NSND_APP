package com.jsm.nsnd.data.api

import android.content.Context
import com.jsm.nsnd.data.session.ServerConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private var cachedRetrofit: Retrofit? = null
    private var cachedBaseUrl: String? = null

    private fun retrofit(context: Context): Retrofit {
        val baseUrl = ServerConfig.getHttpBaseUrl(context)
        if (cachedRetrofit == null || cachedBaseUrl != baseUrl) {
            cachedBaseUrl = baseUrl
            cachedRetrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return cachedRetrofit!!
    }

    fun contactApi(context: Context): ContactApi = retrofit(context).create(ContactApi::class.java)

    fun authApi(context: Context): AuthApi = retrofit(context).create(AuthApi::class.java)
}