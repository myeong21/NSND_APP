package com.jsm.nsnd.ui.contact

import android.content.Context
import com.jsm.nsnd.data.session.SessionManager
import org.json.JSONArray
import org.json.JSONObject

/** 계정별 연락처 오프라인 캐시. 서버 장애 중에도 긴급 SMS에 사용합니다. */
object ContactLocalStore {
    private const val PREFS_NAME = "nsnd_prefs"
    private const val LEGACY_GLOBAL_KEY = "contact_list"

    private fun accountSuffix(context: Context) = SessionManager(context).getAccountStorageKey()
    private fun contactsKey(context: Context) = "contact_list_${accountSuffix(context)}"
    private fun migrationKey(context: Context) = "contacts_server_migrated_${accountSuffix(context)}"

    fun load(context: Context): List<ContactItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = contactsKey(context)
        val accountValue = prefs.getString(key, null)
        val raw = accountValue ?: prefs.getString(LEGACY_GLOBAL_KEY, null).orEmpty()
        if (accountValue == null && raw.isNotBlank()) {
            prefs.edit().putString(key, raw).remove(LEGACY_GLOBAL_KEY).apply()
        }
        if (raw.isBlank()) return emptyList()
        return if (raw.trimStart().startsWith("[")) parseJson(raw) else parseLegacy(raw)
    }

    fun save(context: Context, contacts: List<ContactItem>) {
        val array = JSONArray()
        contacts.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("phone", item.phone)
                put("message", item.message)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(contactsKey(context), array.toString()).apply()
    }

    fun isServerMigrationComplete(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(migrationKey(context), false)

    fun markServerMigrationComplete(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(migrationKey(context), true).apply()
    }

    private fun parseJson(raw: String): List<ContactItem> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(ContactItem(item.optInt("id"), item.optString("name"), item.optString("phone"), item.optString("message")))
            }
        }
    }.getOrDefault(emptyList())

    private fun parseLegacy(raw: String): List<ContactItem> = raw.split("||").mapNotNull { entry ->
        val parts = entry.split("::")
        if (parts.size != 4) null
        else ContactItem(parts[0].toIntOrNull() ?: 0, parts[1], parts[2], parts[3])
    }
}
