package com.jsm.nsnd.data.api

data class RegisterRequest(
    val username: String,
    val name: String,
    val password: String,
    val emergency_contact: String? = null
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class TokenResponse(
    val access_token: String,
    val token_type: String
)

data class UserResponse(
    val id: Int,
    val username: String,
    val name: String,
    val emergency_contact: String?
)