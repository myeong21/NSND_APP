package com.jsm.nsnd

import android.app.Application
import android.util.Log
import com.kakao.vectormap.KakaoMapSdk

class NSNDApplication : Application() {
    companion object {
        const val KAKAO_APP_KEY = "46ea916f3c042dd8b867bf20f0e6f8e7"
        private const val TAG = "NSNDApplication"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            // 카카오맵 SDK v2 정식 초기화
            KakaoMapSdk.init(this, KAKAO_APP_KEY)
            Log.i(TAG, "카카오맵 SDK 초기화 성공")
        } catch (e: Exception) {
            Log.e(TAG, "카카오맵 SDK 초기화 실패", e)
        }
    }
}
