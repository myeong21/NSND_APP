package com.jsm.nsnd.data.local

import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jsm.nsnd.service.LocationTracker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDetectionStoreTest {
    private lateinit var context: Context
    private lateinit var store: LocalDetectionStore
    private val databaseName = "isolated_local_record_test.db"
    private val account = "test-server/user:11"
    private val noFix = LocationSnapshot(null, null, null, null, "permission_denied")
    private fun fix(lat: Double, lng: Double, time: Long) = LocationSnapshot(lat, lng, time, 5f, "available")

    @Before fun setup() {
        // The instrumentation runs as the target app UID; use a separate test-only DB name.
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        store = LocalDetectionStore.isolated(context, databaseName)
    }
    @After fun teardown() { store.close(); context.deleteDatabase(databaseName) }

    @Test fun positionsTransitionsDatesNullCoordinatesAndAccountIsolation() = runBlocking {
        val a = store.recordTransition(account, 11, 5, DetectionSample(1, .21, .6), 1000, fix(37.4, 127.1, 900))
        assertNotNull(a)
        assertNull(store.recordTransition(account, 11, 5, DetectionSample(1, .20, .7), 1100, fix(37.5, 127.2, 1000)))
        store.recordTransition(account, 11, 5, DetectionSample(0, .30, .1), 1200, noFix)
        val b = store.recordTransition(account, 11, 5, DetectionSample(1, .19, .8), 2000, fix(37.5, 127.2, 1900))
        val c = store.recordTransition(account, 11, 5, DetectionSample(2, .18, .9), 2100, fix(37.5, 127.2, 2000))
        store.recordTransition(account, 11, 5, DetectionSample(3, .17, .95), 3000, noFix)
        store.recordTransition("test-server/user:22", 22, 5, DetectionSample(1, .2, .7), 1000, fix(38.0, 128.0, 900))
        val records = store.query(account)
        assertEquals(4, records.size)
        assertEquals(3, records.count { it.hasCoordinates })
        assertEquals(setOf(a, b, c), records.filter { it.hasCoordinates }.map { it.id }.toSet())
        assertEquals(setOf(37.4, 37.5), records.mapNotNull { it.latitude }.toSet())
        assertEquals(2, records.count { it.latitude == 37.5 && it.longitude == 127.2 })
        assertNull(records.first().latitude)
        assertEquals("permission_denied", records.first().locationStatus)
        assertEquals(2, store.query(account, 2000, 3000).size) // inclusive start, exclusive end
        assertEquals(1, store.query("test-server/user:22").size)
        assertTrue(store.query("another-server/user:11").isEmpty())
        assertTrue(store.query(account, sessionId = 6).isEmpty())
        val first = records.last()
        assertEquals(.21, first.ear!!, .0001)
        assertEquals(.6, first.predictionScore!!, .0001)
        assertEquals(900L, first.locationMeasuredAtMs)
        assertEquals(5f, first.accuracyMeters!!, .001f)
        assertTrue(first.markerJavascript().contains(first.id))
    }

    @Test fun databaseReopenRetainsCheckpointAndRecords() = runBlocking {
        store.recordTransition(account, 11, 9, DetectionSample(2, .2, .8), 1000, noFix)
        store.close()
        store = LocalDetectionStore.isolated(context, databaseName)
        assertEquals(1, store.query(account).size)
        assertNull(store.recordTransition(account, 11, 9, DetectionSample(2, .2, .8), 2000, noFix))
        store.recordTransition(account, 11, 9, DetectionSample(0, .3, .1), 2100, noFix)
        assertNotNull(store.recordTransition(account, 11, 9, DetectionSample(2, .2, .8), 2200, noFix))
        assertEquals(2, store.query(account).size)
        assertNotNull(store.recordTransition(account, 11, 10, DetectionSample(2, .2, .8), 2300, noFix))
    }

    @Test fun protocolIgnoresPingErrorsAndMalformedResultsAndUsesEarValue() {
        assertNull(DetectionMessageParser.parse("{\"ping\":true}"))
        assertNull(DetectionMessageParser.parse("{\"error\":\"camera failure\"}"))
        assertNull(DetectionMessageParser.parse("{\"error\":\"failed\",\"drowsy_level\":0}"))
        assertNull(DetectionMessageParser.parse("{\"message\":\"connected\"}"))
        assertNull(DetectionMessageParser.parse("invalid json"))
        assertNull(DetectionMessageParser.parse("{\"drowsy_level\":1.5}"))
        assertNull(DetectionMessageParser.parse("{\"drowsy_level\":4}"))
        val sample = DetectionMessageParser.parse("{\"drowsy_level\":2,\"ear_value\":0.19,\"ear\":0.99,\"pred_score\":0.82}")!!
        assertEquals(.19, sample.ear!!, .0001)
        assertEquals(.82, sample.predictionScore!!, .0001)
        assertEquals(0, DetectionMessageParser.parse("{\"drowsy_level\":0}")!!.stage)
    }

    @Test fun locationAgeAccuracyAndOverseasCoordinates() {
        val location = Location("test").apply {
            latitude = 37.42; longitude = -122.08; accuracy = 5f
            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        assertEquals(-122.08, LocationTracker.snapshotOf(location).longitude!!, .00001)
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 31_000_000_000L
        assertEquals("stale", LocationTracker.snapshotOf(location).status)
        assertNull(LocationTracker.snapshotOf(location).latitude)
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        location.accuracy = 200f
        assertEquals("inaccurate", LocationTracker.snapshotOf(location).status)
        assertNull(LocationTracker.snapshotOf(null).latitude)
    }
}
