package com.jsm.nsnd.data.session

import android.content.Context
import android.util.Base64
import org.json.JSONObject

class SessionManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("nsnd_prefs", Context.MODE_PRIVATE)

    fun saveToken(token: String) {
        prefs.edit().putString("access_token", token).apply()
        com.jsm.nsnd.service.RecordSyncService.schedule(context, recover = true)
    }

    fun getToken(): String? {
        return prefs.getString("access_token", null)
    }

    fun getAuthHeader(): String {
        return "Bearer ${getToken().orEmpty()}"
    }

    fun clear() {
        com.jsm.nsnd.service.RecordSyncService.cancel(context)
        context.applicationContext.stopService(android.content.Intent(context, com.jsm.nsnd.service.DetectionForegroundService::class.java))
        com.jsm.nsnd.service.LocationTracker.stopAllTracking()
        prefs.edit()
            .remove("access_token")
            .remove("active_session_id")
            .apply()
    }

    fun saveActiveSessionId(sessionId: Int) {
        prefs.edit().putInt("active_session_id", sessionId).apply()
        com.jsm.nsnd.service.LocationTracker.restoreSession(context)
    }

    fun getActiveSessionId(): Int = prefs.getInt("active_session_id", -1)

    fun clearActiveSessionId() {
        prefs.edit().remove("active_session_id").apply()
        context.applicationContext.stopService(android.content.Intent(context, com.jsm.nsnd.service.DetectionForegroundService::class.java))
        com.jsm.nsnd.service.LocationTracker.stopAllTracking()
        com.jsm.nsnd.service.RecordSyncService.schedule(context, recover = true)
    }

    fun isLoggedIn(): Boolean {
        return !getToken().isNullOrBlank()
    }

    /** The backend issues a numeric user ID as JWT sub. Never store events as guest. */
    fun getUserId(): Long? = runCatching {
        val payload = getToken()?.split(".")?.getOrNull(1) ?: return@runCatching null
        val padding = "=".repeat((4 - payload.length % 4) % 4)
        val decoded = String(Base64.decode(payload + padding, Base64.URL_SAFE), Charsets.UTF_8)
        JSONObject(decoded).optString("sub").toLongOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    fun getDetectionAccountKey(): String? = getUserId()?.let { getAccountStorageKey() }

    private fun storageIdentity(): String? = runCatching {
        val payload = getToken()?.split(".")?.getOrNull(1) ?: return@runCatching null
        val padding = "=".repeat((4 - payload.length % 4) % 4)
        val json = JSONObject(String(Base64.decode(payload + padding, Base64.URL_SAFE), Charsets.UTF_8))
        json.optString("account_key").takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }.getOrNull()

    suspend fun clearAfterWithdrawal() {
        val account = getDetectionAccountKey()
        account?.let { com.jsm.nsnd.data.local.AlarmStateStore.clearAccount(context,it) }
        com.jsm.nsnd.ui.contact.SmsSettingsStore.disableLocally(context)
        com.jsm.nsnd.ui.contact.ContactLocalStore.clearAccount(context)
        clear()
        account?.let { com.jsm.nsnd.data.local.LocalDetectionStore.get(context).deleteAccount(it) }
    }

    /** New claims are stable; old tokens are isolated instead of trusting reusable numeric IDs. */
    fun getAccountStorageKey(): String {
        storageIdentity()?.let { return "account:$it" }
        val token = getToken().orEmpty()
        if (token.isBlank()) return "guest"

        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "legacy:${ServerConfig.getHttpBaseUrl(context)}:$digest"
    }
}
