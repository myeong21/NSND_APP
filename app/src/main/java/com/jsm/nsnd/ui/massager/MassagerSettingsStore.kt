package com.jsm.nsnd.ui.massager

import android.content.Context
import com.jsm.nsnd.data.session.SessionManager

/** 이 휴대폰에 계정별로 저장하는 설정. 장치에 제어 명령을 보내지 않는다. */
object MassagerSettingsStore {
    const val MIN_STAGE = 1
    const val MAX_STAGE = 5
    const val DEFAULT_STAGE = 3
    private const val PREFS = "nsnd_massager_settings"

    fun load(context: Context, account: String): Int? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("stage_$account", 0).takeIf { it in MIN_STAGE..MAX_STAGE }
    }.getOrNull()

    /** 디스크 저장 결과를 확인하므로 IO 스레드에서 호출한다. */
    fun save(context: Context, account: String, stage: Int): Boolean {
        if (stage !in MIN_STAGE..MAX_STAGE) return false
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("stage_$account", stage).commit()
        }.getOrDefault(false)
    }

    fun clearAccount(context: Context) {
        val account = SessionManager(context).getAccountStorageKey()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("stage_$account").apply()
    }
}
