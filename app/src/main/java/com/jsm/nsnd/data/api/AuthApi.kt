package com.jsm.nsnd.data.api

import retrofit2.Call
import retrofit2.http.*

interface AuthApi {
    @POST("auth/register")
    fun register(@Body body: RegisterRequest): Call<UserResponse>

    @POST("auth/login")
    fun login(@Body body: LoginRequest): Call<TokenResponse>

    @GET("auth/me")
    fun me(@Header("Authorization") token: String): Call<UserResponse>

    @DELETE("auth/me")
    fun deleteMe(@Header("Authorization") token: String): Call<Void>
}