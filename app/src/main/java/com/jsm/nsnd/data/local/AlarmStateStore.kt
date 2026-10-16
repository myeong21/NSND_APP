package com.jsm.nsnd.data.local

import android.content.Context

data class AlarmUiState(val account: String? = null, val sessionId: Int = -1,
    val riskId: String? = null, val sounding: Boolean = false, val muted: Boolean = false,
    val ackPending: Boolean = false, val error: String? = null)

object AlarmStateStore {
    data class ActiveAlarm(val risk: String?, val stage: Int)
    private fun key(account: String, session: Int) = "alarm:$account:$session"
    fun activeAlarm(context: Context, account: String, session: Int): ActiveAlarm? {
        val prefs = context.getSharedPreferences("nsnd_prefs",Context.MODE_PRIVATE)
        val prefix = key(account,session)
        val stage = prefs.getInt("$prefix:active_stage",0)
        return if (stage in 1..3) ActiveAlarm(prefs.getString("$prefix:active_risk",null),stage) else null
    }
    fun rememberActive(context: Context, account: String, session: Int, risk: String?, stage: Int) {
        val prefix = key(account,session)
        context.getSharedPreferences("nsnd_prefs",Context.MODE_PRIVATE).edit()
            .putInt("$prefix:active_stage",stage).putString("$prefix:active_risk",risk).commit()
    }
    fun clearActive(context: Context, account: String, session: Int) {
        val prefix = key(account,session)
        context.getSharedPreferences("nsnd_prefs",Context.MODE_PRIVATE).edit()
            .remove("$prefix:active_stage").remove("$prefix:active_risk").commit()
    }
    fun mutedRisk(context: Context, account: String, session: Int): String? =
        context.getSharedPreferences("nsnd_prefs",Context.MODE_PRIVATE).getString(key(account,session),null)
    fun stop(context: Context, account: String, session: Int, risk: String?) {
        context.getSharedPreferences("nsnd_prefs",Context.MODE_PRIVATE).edit()
            .putString(key(account,session),risk)
            .remove("${key(account,session)}:active_stage").remove("${key(account,session)}:active_risk").commit()
    }
    fun clearAccount(context: Context, account: String) {
        val prefs = context.getSharedPreferences("nsnd_prefs",Context.MODE_PRIVATE)
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("alarm:$account:") }.forEach { remove(it) } }.apply()
    }
}
