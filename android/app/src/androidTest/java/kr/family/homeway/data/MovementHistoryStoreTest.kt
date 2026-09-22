package kr.family.homeway.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.UUID

/** Isolated databases: these tests never open or clear the family's real homeway.db. */
@RunWith(AndroidJUnit4::class)
class MovementHistoryStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String
    private var opened: LocalStore? = null
    private val utc = ZoneId.of("UTC")
    @Before fun prepare() { databaseName = "movement-test-${UUID.randomUUID()}.db" }
    @After fun cleanUp() { opened?.close(); context.deleteDatabase(databaseName) }
    private fun store(): LocalStore = opened ?: LocalStore(context, databaseName).also { opened = it }
    private fun location(at: String = "2026-09-21T10:00:00Z", delivery: String = "relayed") = FamilyEvent(
        UUID.randomUUID().toString(), "location", JSONObject().put("latitude", 37.56).put("longitude", 126.97)
            .put("accuracy", 12).put("source", "automatic").put("capturedAt", at), "child", at, delivery)
    private fun state(vararg events: FamilyEvent) = TelegramLedger.emptyState().put("events", JSONArray(events.map { it.json() }))

    @Test fun upgradeFromV2BackfillsCacheLatestLocationAndOutboxWithoutClearingFamilyState() {
        val retained = location("2026-09-20T10:00:00Z")
        val latest = location("2026-09-21T10:00:00Z")
        val queued = FamilyEvent(UUID.randomUUID().toString(), "vertical", JSONObject().put("phase", "ascent_finished")
            .put("confidence", "estimated").put("relativeMeters", 6.0).put("measuredAt", "2026-09-21T11:00:00Z"),
            "child", "2026-09-21T11:00:00Z", "queued")
        val cached = state(retained).put("latestLocation", latest.json())
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE outbox (id TEXT PRIMARY KEY,event TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending',error TEXT)")
            db.execSQL("CREATE TABLE cache (id INTEGER PRIMARY KEY CHECK(id=1),state TEXT NOT NULL)")
            db.execSQL("CREATE TABLE telegram_receipts (id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE telegram_meta (name TEXT PRIMARY KEY,value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO cache(id,state) VALUES(1,?)", arrayOf(cached.toString()))
            db.execSQL("INSERT INTO outbox(id,event) VALUES(?,?)", arrayOf(queued.id, queued.json().toString()))
            db.execSQL("INSERT INTO telegram_meta(name,value) VALUES('offset',1234)")
            db.execSQL("INSERT INTO telegram_receipts(id) VALUES(?)", arrayOf(retained.id))
            db.version = 2
        }
        val store = store()
        val newest = store.movementHistory("2026-09-21", zone = utc)
        assertEquals(listOf(queued.id, latest.id), newest.events.map { it.id })
        assertEquals(listOf("2026-09-21", "2026-09-20"), newest.days)
        assertEquals(listOf(retained.id), store.movementHistory("2026-09-20", zone = utc).events.map { it.id })
        assertEquals(1234L, store.meta("offset"))
        assertEquals(listOf(retained.id), store.receipts())
        assertEquals(listOf(queued.id), store.pending().map { it.id })
        assertEquals(cached.toString(), store.cached()!!.toString())
        assertEquals(6, store.readableDatabase.version)
    }

    @Test fun timelineTrimmingRetainsArchiveAndDeliveryUpdatesDeduplicateWithoutDowngrading() {
        val event = location(delivery = "pending")
        val store = store()
        store.cache(state(event))
        store.cache(state(event.copy(delivery = "relayed")))
        store.cache(state().put("latestLocation", event.json())) // Stale pending latestLocation after ACK.
        store.cache(state()) // Simulate eviction by newer heartbeat/chat events.
        val page = store.movementHistory("2026-09-21", zone = utc)
        assertEquals(1, page.events.size)
        assertEquals(event.id, page.events.single().id)
        assertEquals("relayed", page.events.single().delivery)
        assertTrue(FamilySnapshot.parse(store.cached()!!).events.isEmpty())
        store.clear()
        assertTrue(store.movementHistory(zone = utc).days.isEmpty())
    }

    @Test fun archiveAndCacheRollbackTogetherWhenOuterReceiveTransactionFails() {
        val original = location()
        val newer = location("2026-09-22T10:00:00Z")
        val store = store()
        store.cache(state(original))
        assertThrows(IllegalStateException::class.java) {
            store.transaction { store.cache(state(newer)); error("simulated failure before receipt commit") }
        }
        assertEquals(listOf("2026-09-21"), store.movementHistory(zone = utc).days)
        assertTrue(store.movementHistory("2026-09-22", zone = utc).events.isEmpty())
        assertEquals(original.id, FamilySnapshot.parse(store.cached()!!).events.single().id)
    }

    @Test fun datePagesUseStableTimestampAndIdCursorWithoutDuplicatesOrAdjacentDayLeakage() {
        val entries = List(305) { location() }
        val otherDay = location("2026-09-20T23:59:59Z")
        val store = store()
        store.cache(state(*(entries + otherDay).toTypedArray()))
        val first = store.movementHistory("2026-09-21", zone = utc)
        val second = store.movementHistory(first.day, first.next, zone = utc)
        assertEquals(300, first.events.size)
        assertEquals(5, second.events.size)
        assertNotNull(first.next)
        assertNull(second.next)
        assertEquals(entries.map { it.id }.sortedDescending(), (first.events + second.events).map { it.id })
        assertEquals(listOf("2026-09-21", "2026-09-20"), first.days)
    }

    @Test fun timezoneChangesRebucketExistingRowsAcrossMidnightWithoutLosingEvents() {
        val event = location("2026-09-21T15:01:00Z")
        val store = store()
        store.cache(state(event))
        assertEquals(listOf("2026-09-22"), store.movementHistory(zone = ZoneId.of("Asia/Seoul")).days)
        assertEquals(event.id, store.movementHistory("2026-09-22", zone = ZoneId.of("Asia/Seoul")).events.single().id)
        assertEquals(listOf("2026-09-21"), store.movementHistory(zone = utc).days)
        assertTrue(store.movementHistory("2026-09-22", zone = utc).events.isEmpty())
        assertEquals(event.id, store.movementHistory("2026-09-21", zone = utc).events.single().id)
    }
}
