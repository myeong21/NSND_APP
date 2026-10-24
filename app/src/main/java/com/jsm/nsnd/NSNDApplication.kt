package com.jsm.nsnd

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.jsm.nsnd.ui.common.AppPermissions
import com.kakao.vectormap.KakaoMapSdk

class NSNDApplication : Application() {
    companion object {
        const val KAKAO_APP_KEY = "46ea916f3c042dd8b867bf20f0e6f8e7"
        private const val TAG = "NSNDApplication"
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities == 0 && !activity.isChangingConfigurations) AppPermissions.resetReview()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        com.jsm.nsnd.service.RecordSyncService.schedule(this, recover = true)
        try {
            // 카카오맵 SDK v2 정식 초기화
            KakaoMapSdk.init(this, KAKAO_APP_KEY)
            Log.i(TAG, "카카오맵 SDK 초기화 성공")
        } catch (e: Exception) {
            Log.e(TAG, "카카오맵 SDK 초기화 실패", e)
        }
    }
}
