package com.jsm.nsnd.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * 운전 세션 중 GPS 위치 추적 및 누적 주행 거리(km) 계산을 관리하는 싱글톤 객체
 */
object LocationTracker {
    private const val TAG = "LocationTracker"

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private var lastLocation: Location? = null
    private var totalDistanceMeters: Float = 0f
    private var isTracking = false

    // 현재 위도 / 경도 캐시
    var currentLatitude: Double? = null
        private set
    var currentLongitude: Double? = null
        private set

    // 거리 변경 리스너 (주행 거리 실시간 UI 갱신용)
    var onDistanceChangedListener: ((distanceKm: Double) -> Unit)? = null

    fun hasLocationPermission(context: Context): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineLocation || coarseLocation
    }

    /**
     * 주행 세션 시작 시 위치 추적 개시 및 거리 초기화
     */
    fun startTracking(context: Context) {
        if (isTracking) return
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "위치 권한이 없어 위치 추적을 시작할 수 없습니다.")
            return
        }

        totalDistanceMeters = 0f
        lastLocation = null
        isTracking = true

        val appContext = context.applicationContext
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            3000L // 3초 간격 갱신
        ).setMinUpdateIntervalMillis(1500L)
            .setMinUpdateDistanceMeters(2f) // 2m 이상 이동 시
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    currentLatitude = location.latitude
                    currentLongitude = location.longitude

                    val prev = lastLocation
                    if (prev != null) {
                        val distance = prev.distanceTo(location)
                        // 비정상적인 GPS 점프 필터링 (예: 50m 초과 순간이동 및 오차 100m 초과 배제)
                        if (distance > 0.5f && location.accuracy <= 40f) {
                            totalDistanceMeters += distance
                            onDistanceChangedListener?.invoke(getDistanceKm())
                        }
                    }
                    lastLocation = location
                }
            }
        }

        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            Log.i(TAG, "GPS 위치 추적 시작")
        } catch (e: SecurityException) {
            Log.e(TAG, "위치 권한 오류", e)
        }
    }

    /**
     * 주행 세션 종료 시 위치 추적 중지 및 최종 주행 거리(km) 반환
     */
    fun stopTracking(): Double {
        if (!isTracking) return getDistanceKm()
        isTracking = false

        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
        locationCallback = null
        val finalKm = getDistanceKm()
        Log.i(TAG, "GPS 위치 추적 종료 - 총 주행 거리: $finalKm km")
        return finalKm
    }

    /**
     * 누적 주행 거리 (km 단위, 소수점 2자리)
     */
    fun getDistanceKm(): Double {
        return Math.round((totalDistanceMeters / 1000.0) * 100.0) / 100.0
    }

    fun isTrackingActive(): Boolean = isTracking
}
