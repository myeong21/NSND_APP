package com.jsm.nsnd.data.session

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast

object ServerConfig {
    private const val PREFS_NAME = "nsnd_prefs"
    private const val KEY_SERVER_IP = "server_ip"
    private const val DEFAULT_IP = "10.0.2.2"
    private const val PORT = "8000"

    fun getIp(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_IP, DEFAULT_IP) ?: DEFAULT_IP
    }

    fun setIp(context: Context, ip: String) {
        val host = ip.trim().lowercase(java.util.Locale.ROOT)
        require(host.matches(Regex("[a-z0-9.-]+"))) { "IP 또는 호스트 이름만 입력해주세요" }
        if (host == getIp(context)) return
        val session = SessionManager(context)
        check(session.getActiveSessionId() <= 0) { "운전 세션을 종료한 후 서버 주소를 변경해주세요" }
        session.clear() // Never send the previous server's token to a new host.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_IP, host).apply()
    }

    fun getHttpBaseUrl(context: Context): String = "http://${getIp(context)}:$PORT/"

    fun getWsBaseUrl(context: Context): String = "ws://${getIp(context)}:$PORT"

    /**
     * 서버 주소 입력 다이얼로그. 어디서든 호출 가능한 공용 함수.
     * onSaved: 저장 완료 후 콜백 (재시작/재연결 등 후처리용)
     */
    fun showEditDialog(context: Context, onSaved: (() -> Unit)? = null) {
        val input = EditText(context).apply {
            hint = "예: 192.168.1.23"
            setText(getIp(context))
        }

        AlertDialog.Builder(context)
            .setTitle("서버 주소 설정")
            .setMessage("노트북(서버)의 IP 주소를 입력하세요")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val newIp = input.text.toString().trim()
                if (newIp.isNotEmpty()) {
                    try {
                        setIp(context, newIp)
                    } catch (error: IllegalArgumentException) {
                        Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    } catch (error: IllegalStateException) {
                        Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    Toast.makeText(context, "서버 주소가 저장되었습니다: $newIp", Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                } else {
                    Toast.makeText(context, "주소를 입력해주세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
