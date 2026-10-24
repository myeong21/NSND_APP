package com.jsm.nsnd.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import androidx.core.content.ContextCompat
import com.jsm.nsnd.data.local.AutomaticSmsPolicy
import com.jsm.nsnd.data.local.AutomaticVideoCallPolicy
import com.jsm.nsnd.data.local.DetectionSample
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.ui.contact.ContactLocalStore

object EmergencyVideoCaller {
    fun missingPermissions(context: Context): List<String> =
        listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    @Synchronized fun send(context: Context, sample: DetectionSample, account: String, sessionId: Int) {
        if (!AutomaticSmsPolicy.requiresDispatch(sample)) return
        val manager = SessionManager(context)
        if (manager.getDetectionAccountKey() != account || manager.getActiveSessionId() != sessionId) return
        val prefs = context.getSharedPreferences("nsnd_video_calls", Context.MODE_PRIVATE)
        val key = "$account:$sessionId:${sample.episodeId}"
        if (prefs.contains(key)) return
        try {
            AutomaticVideoCallPolicy.dispatch(sample, ContactLocalStore.load(context).map { it.phone },
                claim = { _, _ -> prefs.edit().putBoolean(key, true).commit() },
                isBusy = {
                    val telecom = context.getSystemService(TelecomManager::class.java)
                    missingPermissions(context).isNotEmpty() || telecom == null || telecom.isInCall
                },
                placeCall = { phone ->
                    val telecom = context.getSystemService(TelecomManager::class.java)
                    if (manager.getDetectionAccountKey() == account && manager.getActiveSessionId() == sessionId &&
                        !telecom.isInCall) {
                        telecom.placeCall(
                            Uri.fromParts("tel", phone, null), Bundle().apply {
                                putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
                            })
                    }
                })
        } catch (error: SecurityException) {
            Log.w("EmergencyVideoCaller", "전화 권한 또는 기기 정책으로 영상통화를 요청할 수 없습니다", error)
        } catch (error: Exception) {
            // Never interrupt the existing alarm/SMS path or retry another recipient on failure.
            Log.w("EmergencyVideoCaller", "자동 영상통화 요청 실패", error)
        }
    }
}
