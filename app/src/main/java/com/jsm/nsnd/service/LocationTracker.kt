package com.jsm.nsnd.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.location.LocationManager
import com.jsm.nsnd.data.local.DetectionPolicy
import com.jsm.nsnd.data.local.LocationSnapshot
import com.jsm.nsnd.data.local.LocalDetectionStore
import com.jsm.nsnd.data.session.SessionManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
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
    private val owners = mutableSetOf<String>()
    @Volatile private var latestFix: Location? = null
    private var distanceOwner: Pair<String, Int>? = null

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
    fun startTracking(context: Context, owner: String = "driving") {
        restoreSession(context)
        owners.add(owner)
        if (isTracking) return
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "위치 권한이 없어 위치 추적을 시작할 수 없습니다.")
            return
        }

        lastLocation = null
        latestFix = null
        currentLatitude = null
        currentLongitude = null
        isTracking = true

        val appContext = context.applicationContext
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            3000L // 3초 간격 갱신
        ).setMinUpdateIntervalMillis(1500L)
            .setMinUpdateDistanceMeters(0f) // 정지 중에도 위치 시각 갱신
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                restoreSession(appContext)
                for (location in result.locations) {
                    if (latestFix != null && location.elapsedRealtimeNanos < latestFix!!.elapsedRealtimeNanos) continue
                    latestFix = Location(location)
                    currentLatitude = location.latitude
                    currentLongitude = location.longitude

                    val accuracy = if (location.hasAccuracy()) location.accuracy else null
                    val prev = lastLocation
                    if (prev != null) {
                        val distance = prev.distanceTo(location)
                        val elapsedMs = (location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000L
                        if (distanceOwner != null && DetectionPolicy.usableDistance(distance, elapsedMs,
                                if (prev.hasAccuracy()) prev.accuracy else null, accuracy)) {
                            totalDistanceMeters += distance
                            distanceOwner?.let { (account, session) ->
                                LocalDetectionStore.get(appContext).saveDistance(account, session, totalDistanceMeters.toDouble())
                                RecordSyncService.schedule(appContext)
                            }
                            onDistanceChangedListener?.invoke(getDistanceKm())
                        }
                    }
                    // Bad-accuracy fixes must not become the next distance baseline.
                    if (accuracy != null && accuracy in 0f..40f) lastLocation = location
                }
            }
        }

        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )?.addOnFailureListener {
                isTracking = false
                latestFix = null
                Log.w(TAG, "위치 갱신 등록 실패", it)
            }
            Log.i(TAG, "GPS 위치 추적 시작")
        } catch (e: SecurityException) {
            isTracking = false
            latestFix = null
            Log.e(TAG, "위치 권한 오류", e)
        }
    }

    /**
     * 주행 세션 종료 시 위치 추적 중지 및 최종 주행 거리(km) 반환
     */
    fun stopTracking(owner: String = "driving"): Double {
        owners.remove(owner)
        if (owners.isNotEmpty()) return getDistanceKm()
        if (!isTracking) return getDistanceKm()
        isTracking = false

        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
        locationCallback = null
        latestFix = null
        currentLatitude = null
        currentLongitude = null
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

    /** 같은 계정·세션의 저장 거리만 복원하고 재시작 전 좌표와 새 좌표를 이어 붙이지 않는다. */
    fun restoreSession(context: Context, serverMeters: Double = 0.0) {
        val manager = SessionManager(context)
        val session = manager.getActiveSessionId()
        val key = manager.getDetectionAccountKey()?.takeIf { session > 0 }?.let { it to session }
        if (key != distanceOwner) {
            distanceOwner = key
            lastLocation = null
            totalDistanceMeters = key?.let { LocalDetectionStore.get(context).distanceMeters(it.first, it.second).toFloat() } ?: 0f
        }
        key?.let {
            totalDistanceMeters = maxOf(totalDistanceMeters, LocalDetectionStore.get(context).distanceMeters(it.first,it.second).toFloat())
        }
        if (key != null && serverMeters.isFinite() && serverMeters > totalDistanceMeters) {
            totalDistanceMeters = serverMeters.toFloat()
            LocalDetectionStore.get(context).saveDistance(key.first, key.second, serverMeters)
            LocalDetectionStore.get(context).markDistanceSynced(key.first, key.second, serverMeters)
        }
        onDistanceChangedListener?.invoke(getDistanceKm())
    }

    fun isTrackingActive(): Boolean = isTracking

    fun stopAllTracking(): Double {
        owners.clear()
        return stopTracking()
    }

    fun snapshot(context: Context, receivedAtMs: Long = System.currentTimeMillis()): LocationSnapshot {
        if (!hasLocationPermission(context)) return LocationSnapshot(null, null, null, null, "permission_denied")
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager != null && !LocationManagerCompat.isLocationEnabled(manager)) return LocationSnapshot(null, null, null, null, "services_disabled")
        return snapshotOf(latestFix, receivedAtMs)
    }

    fun freshLocation(context: Context): Location? =
        if (snapshot(context).latitude != null) latestFix?.let { Location(it) } else null

    fun snapshotOf(location: Location?, receivedAtMs: Long = System.currentTimeMillis()): LocationSnapshot {
        if (location == null) return LocationSnapshot(null, null, null, null, "no_fix")
        if (location.time <= 0 || location.elapsedRealtimeNanos <= 0) return LocationSnapshot(null, null, null, null, "no_fix")
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        val wallAge = receivedAtMs - location.time
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val valid = wallAge in 0..DetectionPolicy.MAX_LOCATION_AGE_MS &&
            DetectionPolicy.usableFix(location.latitude, location.longitude, accuracy, age)
        val status = when {
            age !in 0..DetectionPolicy.MAX_LOCATION_AGE_MS || wallAge !in 0..DetectionPolicy.MAX_LOCATION_AGE_MS -> "stale"
            !valid -> "inaccurate"
            else -> "available"
        }
        return LocationSnapshot(if (valid) location.latitude else null, if (valid) location.longitude else null,
            location.time, accuracy, status)
    }
}
