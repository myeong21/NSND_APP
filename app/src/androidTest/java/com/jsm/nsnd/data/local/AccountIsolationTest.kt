package com.jsm.nsnd.data.local

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.ui.contact.ContactItem
import com.jsm.nsnd.ui.contact.ContactLocalStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses unique database/preferences names; never deletes the application's own records. */
@RunWith(AndroidJUnit4::class)
class AccountIsolationTest {
    @Test fun jwtIdentitySeparatesReusedIdsAndPreservesReloginForTheSameAccount() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "identity-prefs-${UUID.randomUUID()}"
        val context = object : android.content.ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        val session = SessionManager(context)
        fun token(payload: String) = "fixture." + Base64.encodeToString(payload.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
        try {
            session.saveToken(token("""{"sub":"2","sid":"old"}"""))
            val oldKey = session.getAccountStorageKey()
            session.saveToken(token("""{"sub":"2","sid":"new"}"""))
            assertNotEquals(oldKey, session.getAccountStorageKey())
            context.getSharedPreferences("nsnd_prefs", 0).edit().putString("contact_list_2", "private legacy cache").commit()
            assertTrue(ContactLocalStore.load(context).isEmpty())
            val identity = "a".repeat(64)
            session.saveToken(token("""{"sub":"2","sid":"one","account_key":"$identity"}"""))
            val stableKey = session.getAccountStorageKey()
            ContactLocalStore.save(context, listOf(ContactItem(1, "Fixture", "01012345678", "No SMS")))
            session.saveToken(token("""{"sub":"2","sid":"two","account_key":"$identity"}"""))
            assertEquals(stableKey, session.getAccountStorageKey())
            assertEquals(1, ContactLocalStore.load(context).size)
            session.saveToken(token("""{"sub":"2","account_key":"${"b".repeat(64)}"}"""))
            assertTrue(ContactLocalStore.load(context).isEmpty())
        } finally { base.getSharedPreferences(name, 0).edit().clear().commit() }
    }

    @Test fun reusedIdsCannotReadOtherAccountRecordsAndWithdrawalRejectsQueuedWrites() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "account-isolation-${UUID.randomUUID()}.db"
        val store = LocalDetectionStore.isolated(context, name)
        val sample = DetectionSample(1, .2, .8)
        val fix = LocationSnapshot(null, null, null, null, "no_fix")
        try {
            store.recordTransition("account:A", 2, 1, sample, 1, fix)
            store.recordTransition("account:B", 2, 1, sample, 2, fix)
            assertEquals(1, store.query("account:A").size)
            assertEquals(1, store.query("account:B").size)
            store.deleteAccount("account:A")
            assertTrue(store.query("account:A").isEmpty())
            assertEquals(1, store.query("account:B").size)
            assertNull(store.recordTransition("account:A", 2, 2, sample, 3, fix))
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
