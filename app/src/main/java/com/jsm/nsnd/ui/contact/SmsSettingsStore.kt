package com.jsm.nsnd.ui.contact

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.network.RetrofitClient
import com.jsm.nsnd.network.model.SmsSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The legacy endpoint name is retained; SMS has no saved on/off choice. */
object SmsSettingsStore {
    private val requests = Mutex()
    private val revision = java.util.concurrent.atomic.AtomicLong()

    fun contactsChanged() { revision.incrementAndGet() }

    @Synchronized fun save(context: Context, account: String, settings: SmsSettings, expectedRevision: Long? = null): Boolean {
        if (SessionManager(context).getDetectionAccountKey() != account) return false
        if (expectedRevision != null && expectedRevision != revision.get()) return false
        val serverContacts = settings.contacts.map { ContactItem(it.id,it.name,it.phone,it.message) }
        // Preserve pending legacy contacts. The old enabled field is deliberately ignored.
        if (ContactLocalStore.isServerMigrationComplete(context) || ContactLocalStore.load(context).isEmpty())
            ContactLocalStore.save(context,serverContacts)
        revision.incrementAndGet()
        return true
    }

    suspend fun refresh(context: Context): SmsSettings = requests.withLock {
        val manager = SessionManager(context)
        val account = manager.getDetectionAccountKey() ?: error("로그인이 필요합니다")
        val requestedRevision = revision.get()
        RetrofitClient.apiService(context).getSmsSettings(manager.getAuthHeader()).also {
            check(save(context,account,it,requestedRevision)) { "계정 또는 연락처가 변경되었습니다" }
        }
    }

    @Synchronized fun clearAccount(context: Context) {
        revision.incrementAndGet()
        val account = SessionManager(context).getDetectionAccountKey() ?: return
        context.getSharedPreferences("nsnd_prefs",Context.MODE_PRIVATE).edit().remove("auto_sms:$account").apply()
    }

    fun readiness(context: Context): String = when {
        ContactLocalStore.load(context).isEmpty() -> "발송 준비 필요 · 연락처 등록"
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) -> "SMS 미지원 기기"
        ContextCompat.checkSelfPermission(context,Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ->
            "발송 불가 · SMS 권한 필요"
        else -> "SMS 발송 준비됨 · 결과는 발송 이력에서 확인"
    }
}
