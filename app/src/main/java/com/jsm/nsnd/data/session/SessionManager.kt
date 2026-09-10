package com.jsm.nsnd.data.session

import android.content.Context
import android.util.Base64
import org.json.JSONObject

class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("nsnd_prefs", Context.MODE_PRIVATE)

    fun saveToken(token: String) {
        prefs.edit().putString("access_token", token).apply()
    }

    fun getToken(): String? {
        return prefs.getString("access_token", null)
    }

    fun getAuthHeader(): String {
        return "Bearer ${getToken().orEmpty()}"
    }

    fun clear() {
        prefs.edit().remove("access_token").apply()
    }

    fun isLoggedIn(): Boolean {
        return !getToken().isNullOrBlank()
    }

    /** 계정마다 로컬 데이터를 분리하기 위한 안정적인 JWT subject 값입니다. */
    fun getAccountStorageKey(): String {
        val token = getToken().orEmpty()
        if (token.isBlank()) return "guest"

        return runCatching {
            val payload = token.split(".").getOrNull(1) ?: return@runCatching token.hashCode().toString()
            val padding = "=".repeat((4 - payload.length % 4) % 4)
            val decoded = String(Base64.decode(payload + padding, Base64.URL_SAFE))
            JSONObject(decoded).optString("sub").ifBlank { token.hashCode().toString() }
        }.getOrElse { token.hashCode().toString() }
    }
}
