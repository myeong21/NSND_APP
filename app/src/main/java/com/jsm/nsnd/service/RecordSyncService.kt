package com.jsm.nsnd.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.jsm.nsnd.data.local.LocalDetectionStore
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import retrofit2.HttpException
import java.time.Instant

/** 앱 재실행·네트워크 복구 후에도 현재 인증 계정의 저장된 자료만 전송한다. */
class RecordSyncService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    private val syncMutex = Mutex()

    override fun onStartJob(params: JobParameters): Boolean {
        runningJobs.add(params.jobId)
        val task = scope.launch { syncMutex.withLock {
            val manager = SessionManager(this@RecordSyncService)
            val account = manager.getDetectionAccountKey()
            var retry = false
            var completed = false
            try {
                if (account == null) return@launch
                val header = manager.getAuthHeader()
                val api = RetrofitClient.apiService(this@RecordSyncService)
                val store = LocalDetectionStore.get(this@RecordSyncService)
                val userId = manager.getUserId() ?: return@launch
                // Catch-up and live uploads intentionally have independent paths. A restored
                // event is never published to DetectionForegroundService or the SMS sender.
                try {
                    var pages = 0
                    do {
                        val cursor = store.recoveryCursor(account)
                        val page = api.recoverEvents(header,cursor)
                        if (manager.getDetectionAccountKey() != account) return@launch
                        check(store.importRecoveryPage(account,userId,cursor,page)) { "기록 조회 위치가 변경되었습니다" }
                        pages++
                        if (page.has_more) check(page.next_cursor > cursor) { "서버 조회 위치가 진행되지 않습니다" }
                        if (page.has_more && pages >= 20) retry = true
                    } while (page.has_more && pages < 20)
                } catch (error: CancellationException) { throw error
                } catch (error: Exception) {
                    if (error is HttpException && error.code() == 401) {
                        store.recoveryFailure(account,"로그인이 만료되었습니다. 다시 로그인하면 동기화합니다.")
                        return@launch
                    }
                    store.recoveryFailure(account,"서버 기록 조회 대기: " + (if (error is HttpException) "응답 ${error.code()}" else "연결 확인 필요"))
                    retry = true
                }
                for (record in store.pendingLocations(account)) {
                    if (manager.getDetectionAccountKey() != account) return@launch
                    try {
                        api.updateEventLocation(header, record.serverEventId!!, EventLocationRequest(
                            record.sessionId, Instant.ofEpochMilli(record.receivedAtMs).toString(),
                            record.latitude, record.longitude,
                            record.locationMeasuredAtMs?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                            record.accuracyMeters?.takeIf { it.isFinite() && it >= 0 }, record.locationStatus,
                        ))
                        store.markLocationSync(account, record.id, "synced")
                    } catch (error: CancellationException) { throw error
                    } catch (error: Exception) {
                        if (error is HttpException && error.code() == 401) return@launch
                        store.syncFailure(account,record.id,false,(error as? HttpException)?.code())
                    }
                }
                for (distance in store.pendingDistances(account)) {
                    if (manager.getDetectionAccountKey() != account) return@launch
                    try {
                        val saved = api.updateSessionDistance(header, distance.sessionId, SessionDistanceRequest(distance.meters))
                        check(saved.distance_meters.isFinite() && saved.distance_meters >= distance.meters)
                        store.markDistanceSynced(account, distance.sessionId, saved.distance_meters)
                    } catch (error: CancellationException) { throw error
                    } catch (error: Exception) {
                        if (error is HttpException && error.code() == 401) return@launch
                        store.syncFailure(account,distance.sessionId.toString(),true,(error as? HttpException)?.code())
                    }
                }
                retry = retry || store.hasPendingSync(account)
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: HttpException) {
                retry = error.code() != 401
            } catch (error: Exception) {
                retry = true
                android.util.Log.w("RecordSync", "기록 전송 대기", error)
            } finally {
                if (currentCoroutineContext().isActive) {
                    jobFinished(params, retry)
                    if (manager.getDetectionAccountKey() != account) schedule(this@RecordSyncService)
                    // A writer may have seen this job still running just before jobFinished.
                    if (completed && !retry) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        schedule(applicationContext)
                    }, 1_000L)
                }
            }
        } }
        work[params.jobId] = task
        task.invokeOnCompletion { work.remove(params.jobId, task); runningJobs.remove(params.jobId) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { work.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val JOB_ID = 7301
        private const val RECOVERY_JOB_ID = 7302
        private val runningJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        fun schedule(context: Context, recover: Boolean = false, immediately: Boolean = false) {
            val account = SessionManager(context).getDetectionAccountKey() ?: return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(RECOVERY_JOB_ID) == null) {
                scheduler.schedule(JobInfo.Builder(RECOVERY_JOB_ID, ComponentName(context, RecordSyncService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setPeriodic(15 * 60_000L).build())
            }
            if (!recover && !LocalDetectionStore.get(context).hasPendingSync(account)) return
            if (immediately && JOB_ID !in runningJobs) scheduler.cancel(JOB_ID)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, RecordSyncService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setMinimumLatency(1_000L)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build())
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.cancel(JOB_ID); scheduler.cancel(RECOVERY_JOB_ID)
        }
    }
}

data class EventLocationRequest(
    val session_id: Int, val received_at: String, val latitude: Double?, val longitude: Double?,
    val measured_at: String?, val accuracy_meters: Float?, val location_status: String,
)
data class SessionDistanceRequest(val distance_meters: Double)
