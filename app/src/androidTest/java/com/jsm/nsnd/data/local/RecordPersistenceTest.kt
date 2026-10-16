package com.jsm.nsnd.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import com.jsm.nsnd.network.model.RecoveredEvent
import com.jsm.nsnd.network.model.RecoveryPage

@RunWith(AndroidJUnit4::class)
class RecordPersistenceTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var store: LocalDetectionStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        name = "record-persistence-${UUID.randomUUID()}.db"
        store = LocalDetectionStore.isolated(context,name)
    }
    @After fun cleanup() { store.close(); context.deleteDatabase(name) }

    private fun recovered(cursor: Long, id: String = UUID.randomUUID().toString()) =
        RecoveredEvent(cursor,id,3,"2026-10-16T00:00:00Z",3,.15,.8,null,null,null,null,null,"not_recorded")

    @Test fun recoveryCursorAndRowsCommitTogetherWithoutTouchingLiveTransitionOrGps() = runBlocking {
        val first = recovered(2)
        assertTrue(store.importRecoveryPage("A",1,0,RecoveryPage(2,false,listOf(first))))
        assertEquals(2L,store.recoveryCursor("A"))
        assertEquals(0L,store.recoveryCursor("B"))
        val record = store.query("A").single()
        assertEquals("recovered",record.source)
        assertNull(record.latitude)
        assertTrue(store.pendingLocations("A").isEmpty())
        // Recovery must not suppress the next legacy live transition at the same level.
        assertNotNull(store.recordTransition("A",1,3,DetectionSample(3,.15,.8),3000,
            LocationSnapshot(null,null,null,null,"no_fix")))
        val valid = recovered(3)
        val invalid = recovered(4).copy(detected_at="invalid timestamp")
        try {
            store.importRecoveryPage("A",1,2,RecoveryPage(4,false,listOf(valid,invalid)))
            fail("A broken page must roll back both row insertion and cursor")
        } catch (_: java.time.format.DateTimeParseException) { }
        assertEquals(2L,store.recoveryCursor("A"))
        assertFalse(store.query("A").any { it.serverEventId == valid.event_id })
        store.close(); store = LocalDetectionStore.isolated(context,name)
        assertEquals(2L,store.recoveryCursor("A"))
        assertFalse(store.importRecoveryPage("A",1,0,RecoveryPage(2,false,listOf(first))))
    }

    @Test fun liveReceiptWinsRecoveryRaceWithoutDuplicateOrOverwritingFirstReceipt() = runBlocking {
        val event = recovered(1)
        store.importRecoveryPage("A",1,0,RecoveryPage(1,false,listOf(event)))
        val sample = DetectionSample(3,.15,.8,serverEventId=event.event_id)
        val location = LocationSnapshot(37.5,127.0,2000,5f,"available")
        store.recordTransition("A",1,3,sample,3000,location)
        store.recordTransition("A",1,3,sample,4000,location.copy(latitude=38.0))
        val record = store.query("A").single()
        assertEquals("live",record.source)
        assertEquals(3000L,record.receivedAtMs)
        assertEquals(37.5,record.latitude!!,0.0)
        assertEquals(1,store.pendingLocations("A").size)
    }

    @Test fun failedItemsDoNotStarveNewItemsAndServerDistanceCanIncreaseLocalTotal() = runBlocking {
        val noLocation = LocationSnapshot(null,null,null,null,"no_fix")
        val bad = store.recordTransition("A",1,3,DetectionSample(1,.2,.4,serverEventId=UUID.randomUUID().toString()),1000,noLocation)!!
        store.syncFailure("A",bad,false,422)
        val good = store.recordTransition("A",1,3,DetectionSample(2,.2,.4,serverEventId=UUID.randomUUID().toString()),2000,noLocation)!!
        assertEquals(listOf(good),store.pendingLocations("A").map { it.id })
        store.retryFailedSync("A")
        assertEquals(2,store.pendingLocations("A").size)
        store.saveDistance("A",3,100.0)
        store.markDistanceSynced("A",3,250.0)
        assertEquals(250.0,store.distanceMeters("A",3),0.0)
        assertTrue(store.pendingDistances("A").isEmpty())
    }

    @Test fun smsClaimsSurviveRestartDeduplicatePerEpisodeAndRespectAccountAndPhone() {
        val field = LocalDetectionStore::class.java.getDeclaredField("instance").apply { isAccessible=true }
        val previous = field.get(null)
        try {
            field.set(null,store)
            assertNotNull(SmsHistoryStore.create(context,"A","episode:1","fixture","01011112222","fixture",1,"test"))
            assertNull(SmsHistoryStore.create(context,"A","episode:1","fixture","01011112222","fixture",1,"test"))
            assertNull(SmsHistoryStore.create(context,"A","episode:1","fixture","010-1111-2222","fixture",1,"test"))
            store.close(); store=LocalDetectionStore.isolated(context,name); field.set(null,store)
            assertNull(SmsHistoryStore.create(context,"A","episode:1","fixture","01011112222","fixture",1,"test"))
            assertNotNull(SmsHistoryStore.create(context,"A","episode:2","fixture","01011112222","fixture",1,"test"))
            assertNotNull(SmsHistoryStore.create(context,"B","episode:1","fixture","01011112222","fixture",1,"test"))
            assertNotNull(SmsHistoryStore.create(context,"A","episode:1","fixture","01033334444","fixture",1,"test"))
        } finally { field.set(null,previous) }
    }

    @Test fun serverIdsPreventDuplicateUploadAndSeparateAccounts() = runBlocking {
        val event = UUID.randomUUID().toString()
        val sample = DetectionSample(1,.15,.2,serverEventId=event,serverDetectedAtMs=1000)
        val location = LocationSnapshot(null,null,null,null,"no_fix")
        val id = store.recordTransition("A",1,3,sample,2000,location)!!
        assertNull(store.recordTransition("A",1,3,sample,3000,location))
        assertEquals(1,store.pendingLocations("A").size)
        assertTrue(store.pendingLocations("B").isEmpty())
        store.markLocationSync("A",id,"synced")
        assertTrue(store.pendingLocations("A").isEmpty())
        assertEquals(1000L,store.query("A").single().serverDetectedAtMs)
    }

    @Test fun distanceSurvivesReopenAndOldAcknowledgementDoesNotLoseNewDistance() = runBlocking {
        store.saveDistance("A",3,1200.0)
        store.saveDistance("A",3,800.0)
        store.close(); store = LocalDetectionStore.isolated(context,name)
        assertEquals(1200.0,store.distanceMeters("A",3),0.0)
        store.saveDistance("A",3,1300.0)
        store.markDistanceSynced("A",3,1200.0)
        assertEquals(1300.0,store.pendingDistances("A").single().meters,0.0)
        store.deleteAccount("A")
        assertEquals(0.0,store.distanceMeters("A",3),0.0)
    }

    @Test fun oldDatabaseUpgradesWithoutInventingServerIdsOrRemovingEvents() = runBlocking {
        context.openOrCreateDatabase(name,0,null).use { db ->
            db.execSQL("""CREATE TABLE local_events (id TEXT PRIMARY KEY,account_key TEXT,user_id INTEGER,
                session_id INTEGER,received_at_ms INTEGER,stage INTEGER,ear REAL,prediction_score REAL,
                latitude REAL,longitude REAL,location_measured_at_ms INTEGER,accuracy_meters REAL,location_status TEXT)""")
            db.execSQL("CREATE TABLE transition_state(account_key TEXT,session_id INTEGER,stage INTEGER,PRIMARY KEY(account_key,session_id))")
            db.execSQL("INSERT INTO local_events VALUES ('old','A',1,3,1000,1,0.15,0.2,NULL,NULL,NULL,NULL,'no_fix')")
            db.version = 1
        }
        val record = store.query("A").single()
        assertEquals("old",record.id)
        assertNull(record.serverEventId)
        assertEquals("local_only",record.syncState)
    }
}
