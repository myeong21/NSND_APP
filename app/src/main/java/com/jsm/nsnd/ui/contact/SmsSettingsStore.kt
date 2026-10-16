package com.jsm.nsnd.ui.contact

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.network.model.SmsSettings
import com.jsm.nsnd.network.model.SmsSettingsUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SmsSettingsStore {
    private val requests = Mutex()
    private val revision = java.util.concurrent.atomic.AtomicLong()
    private fun key(account: String) = "auto_sms:$account"
    fun enabled(context: Context): Boolean {
        val account = SessionManager(context).getDetectionAccountKey() ?: return false
        return context.getSharedPreferences("nsnd_prefs",Context.MODE_PRIVATE).getBoolean(key(account),false)
    }
    @Synchronized fun save(context: Context, account: String, settings: SmsSettings, expectedRevision: Long? = null): Boolean {
        if (SessionManager(context).getDetectionAccountKey() != account) return false
        if (expectedRevision != null && expectedRevision != revision.get()) return false
        val serverContacts = settings.contacts.map { ContactItem(it.id,it.name,it.phone,it.message) }
        // Do not erase legacy contacts that still await explicit server migration.
        if (ContactLocalStore.isServerMigrationComplete(context) || ContactLocalStore.load(context).isEmpty())
            ContactLocalStore.save(context,serverContacts)
        context.getSharedPreferences("nsnd_prefs",Context.MODE_PRIVATE).edit()
            .putBoolean(key(account),settings.enabled && settings.contact_count > 0).apply()
        revision.incrementAndGet()
        return true
    }
    suspend fun refresh(context: Context): SmsSettings = requests.withLock {
        val manager = SessionManager(context)
        val account = manager.getDetectionAccountKey() ?: error("로그인이 필요합니다")
        val requestedRevision = revision.get()
        RetrofitClient.apiService(context).getSmsSettings(manager.getAuthHeader()).also {
            check(save(context,account,it,requestedRevision)) { "계정 또는 설정이 변경되었습니다" }
        }
    }
    suspend fun update(context: Context, enabled: Boolean): SmsSettings = requests.withLock {
        val manager = SessionManager(context)
        val account = manager.getDetectionAccountKey() ?: error("로그인이 필요합니다")
        val requestedRevision = revision.get()
        RetrofitClient.apiService(context).updateSmsSettings(manager.getAuthHeader(),SmsSettingsUpdate(enabled)).also {
            check(save(context,account,it,requestedRevision)) { "계정 또는 설정이 변경되었습니다" }
        }
    }
    @Synchronized fun disableLocally(context: Context) {
        revision.incrementAndGet() // A response already in flight cannot re-enable a removed contact.
        val account = SessionManager(context).getDetectionAccountKey() ?: return
        context.getSharedPreferences("nsnd_prefs",Context.MODE_PRIVATE).edit().putBoolean(key(account),false).apply()
    }
    fun readiness(context: Context): String = when {
        !enabled(context) -> "자동 SMS 꺼짐 · 졸음 감지와 경고음은 사용할 수 있습니다."
        ContactLocalStore.load(context).isEmpty() -> "연락처 없음 · 자동 SMS를 사용할 수 없습니다."
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) -> "이 기기는 SMS 기능을 지원하지 않습니다."
        ContextCompat.checkSelfPermission(context,Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ->
            "SMS 권한 없음 · 권한을 허용해야 자동 발송할 수 있습니다."
        else -> "자동 SMS 켜짐 · 새 3단계 사건마다 연락처별 1회 요청합니다. SIM·통신망 상태에 따라 실패할 수 있습니다."
    }
}
