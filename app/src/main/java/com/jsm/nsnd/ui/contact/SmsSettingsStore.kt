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
        ContactLocalStore.load(context).isEmpty() -> "3단계 위험 SMS는 필수 자동 발송입니다. 비상 연락처를 등록해주세요."
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) -> "필수 자동 발송 · 이 기기는 SMS 기능을 지원하지 않습니다."
        ContextCompat.checkSelfPermission(context,Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ->
            "필수 자동 발송 · SMS 권한이 없어 발송할 수 없습니다. 권한을 허용해주세요."
        else -> "필수 자동 발송 · 새 3단계 사건마다 등록한 모든 연락처로 1회씩 요청합니다. 결과는 발송 이력에서 확인하세요."
    }
}
