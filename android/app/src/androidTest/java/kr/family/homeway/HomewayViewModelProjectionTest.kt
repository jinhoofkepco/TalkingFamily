package kr.family.homeway

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kr.family.homeway.data.*
import kr.family.homeway.overlay.FloatingStarService
import kr.family.homeway.tracking.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Emulator-only DB fixtures. No Telegram method may reach the network. */
@RunWith(AndroidJUnit4::class)
class HomewayViewModelProjectionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("homeway_settings", Context.MODE_PRIVATE)
    private val store get() = LocalStore.get(context)
    private val models = ViewModelStore()
    private lateinit var repo: AppRepository
    private lateinit var model: HomewayViewModel
    private lateinit var room: FamilyChatRoom
    private var prepared = false
    private val reads = AtomicInteger()
    private val completedReads = AtomicInteger()
    private val readStartedAt = AtomicLong()
    private val lastReadDuration = AtomicLong()
    private val readOnMain = AtomicBoolean()
    private val nextGate = AtomicReference<ReadGate?>()
    private val lastProjection = AtomicReference("not read")
    private val gates = mutableListOf<ReadGate>()

    private class ReadGate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    @Before fun prepare() = runBlocking {
        assumeTrue("These account-reset tests must never run on a physical family phone",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic/") ||
                Build.FINGERPRINT.startsWith("generic_x86/"))
        context.stopService(Intent(context, TelegramReceiveService::class.java))
        context.stopService(Intent(context, FloatingStarService::class.java))
        TrackingService.stopAndAwait(context)
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        store.transaction { store.clear() }
        prefs.edit().clear().commit()
        TokenVault(context).clear()
        context.getSharedPreferences("homeway_receiver", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
        prepared = true
        repo = AppRepository(context) { token -> TelegramClient(token, object : TelegramHttpTransport {
            override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse =
                error("Projection tests must not send a Telegram request")
        }) }
        room = FamilyChatRoom.create("화면 응답 시험", listOf(
            FamilyChatMember(101, "@projection_parent_bot", "부모", "father"),
            FamilyChatMember(202, "@projection_first_bot", "첫째", "son"),
            FamilyChatMember(303, "@projection_second_bot", "둘째", "daughter")))
        TokenVault(context).put("101:${"synthetic_projection_credentials_".repeat(2)}")
        prefs.edit().putString("transport", "telegram_direct").putString("role", "guardian")
            .putString("botUsername", "@projection_parent_bot").putLong("ownBotId", 101).commit()
        store.transaction {
            store.familyChat.setActiveRoom(room)
            for ((child, balance) in listOf(202L to 2, 303L to 8)) {
                store.familyCare.saveState(FamilyCareState(room.id, child, UUID.randomUUID().toString(),
                    1, false, TelegramLedger.emptyState().put("stickerBalance", balance)))
            }
        }
    }

    @After fun cleanUp() {
        if (!prepared) return
        gates.forEach { it.release.countDown() }
        instrumentation.runOnMainSync { models.clear() }
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        store.transaction { store.clear() }
        prefs.edit().clear().commit()
        TokenVault(context).clear()
    }

    private fun createModel() {
        instrumentation.runOnMainSync {
            model = HomewayViewModel(context.applicationContext as Application, repo) {
                reads.incrementAndGet()
                val startedAt = SystemClock.elapsedRealtime()
                readStartedAt.set(startedAt)
                if (Looper.myLooper() == Looper.getMainLooper()) readOnMain.set(true)
                val projection = repo.readUiSnapshot()
                lastReadDuration.set(SystemClock.elapsedRealtime() - startedAt)
                completedReads.incrementAndGet()
                lastProjection.set("configured=${projection.configured}, care=${projection.careEnabled}, " +
                    "ready=${projection.careSnapshot != null}, child=${projection.selectedChildBotId}, " +
                    "balance=${projection.careSnapshot?.stickerBalance}")
                nextGate.getAndSet(null)?.let { gate ->
                    gate.started.countDown()
                    check(gate.release.await(15, TimeUnit.SECONDS)) { "Test did not release its projection read" }
                }
                projection
            }
            models.put("projection", model)
        }
    }

    private fun blockNextRead(): ReadGate = ReadGate().also { gates.add(it); nextGate.set(it) }

    private suspend fun awaitState(predicate: () -> Boolean) {
        val ready = withTimeoutOrNull(5_000) {
            while (!predicate()) delay(10)
            true
        }
        if (ready != true) {
            val current = model.state.value
            throw AssertionError("UI did not settle: loading=${current.loading}, configured=${current.configured}, " +
                "care=${current.careEnabled}, ready=${current.careReady}, child=${current.selectedChildBotId}, " +
                "balance=${current.stickerBalance}, error=${current.error}; reads=${reads.get()}, " +
                "completed=${completedReads.get()}, lastReadMs=${lastReadDuration.get()}, " +
                "sinceReadStartMs=${SystemClock.elapsedRealtime() - readStartedAt.get()}, last=${lastProjection.get()}")
        }
    }

    private suspend fun awaitReady() {
        awaitState { !model.state.value.loading && model.state.value.careReady && model.state.value.stickerBalance == 2 }
        // Let the one initial prepareCare notification finish before measuring later refresh bursts.
        var previous = reads.get()
        withTimeout(5_000) {
            while (true) {
                delay(150)
                val current = reads.get()
                if (current == previous) break
                previous = current
            }
        }
    }

    @Test fun constructingAndRefreshingTheViewModelNeverBlockMainOnACareProjection() = runBlocking {
        val gate = blockNextRead()
        createModel()
        try {
            assertTrue(gate.started.await(5, TimeUnit.SECONDS))
            val mainHandledInput = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { mainHandledInput.countDown() }
            assertTrue("Main must keep processing input while the care projection is blocked",
                mainHandledInput.await(2, TimeUnit.SECONDS))
            assertFalse("Repository projections must never run on main", readOnMain.get())
            assertTrue("Initial data should still be loading", model.state.value.loading)
        } finally { gate.release.countDown() }
        awaitReady()
    }

    @Test fun manyRefreshesDuringOneSlowReadCollapseIntoOneFollowUpRead() = runBlocking {
        createModel(); awaitReady()
        val before = reads.get()
        val gate = blockNextRead()
        instrumentation.runOnMainSync { model.refreshCached() }
        try {
            assertTrue(gate.started.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { repeat(100) { model.refreshCached() } }
            assertEquals("Only the in-flight read may run before its gate is released", before + 1, reads.get())
        } finally { gate.release.countDown() }
        awaitState { reads.get() == before + 2 }
        delay(200)
        assertEquals("The pending burst should require one more projection, not one per notification", before + 2, reads.get())
        assertFalse(readOnMain.get())
    }

    @Test fun pausedScreenDoesNotProjectRepositoryChangesAndResumesWithTheLatestAccount() = runBlocking {
        createModel(); awaitReady()
        instrumentation.runOnMainSync { model.setUiActive(false) }
        val before = reads.get()
        repeat(8) { repo.reset() }
        instrumentation.runOnMainSync { repeat(50) { model.refreshCached() } }
        delay(200)
        assertEquals("A stopped Activity must not keep parsing DB updates", before, reads.get())
        instrumentation.runOnMainSync { model.setUiActive(true) }
        awaitState { !model.state.value.configured && model.state.value.room == null }
        assertTrue("Only the latest stopped-state changes need to be projected", reads.get() <= before + 2)
    }

    @Test fun slowPreviousChildProjectionCannotPaintOverANewChildSelection() = runBlocking {
        val board = store.familyCare.state(room.id, 202)!!
        val at = "2026-09-30T10:00:00Z"
        val point = FamilyEvent(UUID.randomUUID().toString(), "location", org.json.JSONObject()
            .put("latitude", 37.0).put("longitude", 127.0).put("accuracy", 12.0)
            .put("capturedAt", at).put("source", "automatic"), "child", at, "relayed")
        store.familyCare.saveLatestLocationHead(FamilyCareLocationHead(room.id, 202, board.epoch, 80, point))
        createModel(); awaitReady()
        assertEquals(point.id, model.state.value.latestLocation!!.id)
        val gate = blockNextRead()
        val observed = Collections.synchronizedList(mutableListOf<Pair<Long?, Int>>())
        val collect = launch(Dispatchers.Default) {
            model.state.collect { observed.add(it.selectedChildBotId to it.stickerBalance) }
        }
        instrumentation.runOnMainSync { model.refreshCached() }
        try {
            assertTrue(gate.started.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { model.selectCareChild(303) }
            assertEquals(303L, model.state.value.selectedChildBotId)
            assertEquals("Old child content must be cleared immediately", 0, model.state.value.stickerBalance)
            assertNull("Old child's current location must be cleared with their board", model.state.value.latestLocation)
            awaitState { prefs.getLong("careChild_${room.id}", 0) == 303L }
        } finally { gate.release.countDown() }
        awaitState { model.state.value.selectedChildBotId == 303L && model.state.value.stickerBalance == 8 }
        assertFalse("First child's board must never appear under second child's name",
            synchronized(observed) { observed.any { it.first == 303L && it.second == 2 } })
        collect.cancel()
    }

    @Test fun latestLocationProjectsIndependentlyOfTheBoardRevisionAndHistoryPage() = runBlocking {
        val board = store.familyCare.state(room.id, 202)!!
        val at = "2026-09-30T10:00:00Z"
        val event = FamilyEvent(UUID.randomUUID().toString(), "location", org.json.JSONObject()
            .put("latitude", 37.0).put("longitude", 127.0).put("accuracy", 12.0)
            .put("capturedAt", at).put("source", "automatic"), "child", at, "relayed")
        assertTrue(store.familyCare.saveLatestLocationHead(FamilyCareLocationHead(room.id, 202, board.epoch, 80, event)))
        createModel()
        awaitState { !model.state.value.loading && model.state.value.latestLocation?.id == event.id }
        assertEquals(2, model.state.value.stickerBalance)
        assertEquals(1L, store.familyCare.stateMetadata(room.id, 202)!!.revision)
        assertFalse(readOnMain.get())
    }

    @Test fun historyRepairStatusUsesTheSelectedChildAndSurvivesInitialHistoryReset() = runBlocking {
        val progress = FamilyCareHistoryProgress(room.id, 202, UUID.randomUUID().toString(), null,
            0, 0, 0, false, System.currentTimeMillis())
        store.familyCare.saveHistoryProgress(progress)
        createModel(); awaitReady()
        assertTrue("An initial history-context reset must not hide the repair status", model.state.value.historySyncing)
        instrumentation.runOnMainSync { model.selectCareChild(303) }
        awaitState { model.state.value.selectedChildBotId == 303L && model.state.value.stickerBalance == 8 }
        assertFalse("Do not show the first child's repair status for the second child", model.state.value.historySyncing)
        store.familyCare.saveHistoryProgress(progress.copy(complete = true))
        instrumentation.runOnMainSync { model.selectCareChild(202) }
        awaitState { model.state.value.selectedChildBotId == 202L && model.state.value.stickerBalance == 2 }
        assertFalse(model.state.value.historySyncing)
    }

    @Test fun slowPreviousAccountProjectionCannotRestoreARoomAfterReset() = runBlocking {
        createModel(); awaitReady()
        val gate = blockNextRead()
        instrumentation.runOnMainSync { model.refreshCached() }
        try {
            assertTrue(gate.started.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { model.resetConfiguration() }
            awaitState { !model.state.value.configured && model.state.value.room == null }
        } finally { gate.release.countDown() }
        awaitState { reads.get() >= 2 && !model.state.value.loading }
        delay(200)
        assertFalse(model.state.value.configured)
        assertNull(model.state.value.room)
        assertTrue(model.state.value.events.isEmpty())
        assertEquals(0, model.state.value.stickerBalance)
    }
}
