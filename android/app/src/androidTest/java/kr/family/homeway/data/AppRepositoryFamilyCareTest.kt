package kr.family.homeway.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kr.family.homeway.overlay.FloatingStarService
import kr.family.homeway.tracking.TrackingService
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Emulator-only repository wiring tests. Credentials, identities, and positions are synthetic. */
@RunWith(AndroidJUnit4::class)
class AppRepositoryFamilyCareTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("homeway_settings", Context.MODE_PRIVATE)
    private val vault get() = TokenVault(context)
    private val store get() = LocalStore.get(context)
    private lateinit var fake: IdentityTelegram
    private lateinit var repo: AppRepository
    private var prepared = false

    private val members = listOf(
        FamilyChatMember(101, "@care_mother_bot", "엄마", "mother"),
        FamilyChatMember(102, "@care_father_bot", "아빠", "father"),
        FamilyChatMember(103, "@care_son_bot", "아들", "son"),
        FamilyChatMember(104, "@care_daughter_bot", "딸", "daughter"),
        FamilyChatMember(105, "@care_relative_bot", "가족", "family"),
    )
    private val activeRoom = FamilyChatRoom.create("다섯 가족", members,
        "75d07827-9652-40fb-97b2-00067b0f3a87")
    private val secondRoom = FamilyChatRoom.create("새 가족방", members,
        "cc96ee03-b011-42c0-8377-f7e02776e371")
    private val measuredAt = "2026-09-23T12:00:00Z"

    @Before fun prepare() = runBlocking {
        assumeTrue("Repository reset tests must never run on a physical family phone",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic/") ||
                Build.FINGERPRINT.startsWith("generic_x86/"))
        context.stopService(Intent(context, TelegramReceiveService::class.java))
        context.stopService(Intent(context, FloatingStarService::class.java))
        TrackingService.stopAndAwait(context)
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        clearAccount()
        context.getSharedPreferences("homeway_receiver", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", false).commit()
        // The production enqueue path uses KEEP. Reserve that name so no default, non-injected
        // OutboxWorker can start while these tests inspect durable queues with synthetic credentials.
        manager.enqueueUniqueWork("homeway_outbox", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OutboxWorker>().setInitialDelay(365, TimeUnit.DAYS).build())
            .result.get(10, TimeUnit.SECONDS)
        fake = IdentityTelegram(members)
        repo = newRepository()
        prepared = true
    }

    @After fun cleanUp() {
        if (!prepared) return
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        clearAccount()
    }

    private fun clearAccount() {
        store.transaction { store.clear() }
        prefs.edit().clear().commit()
        vault.clear()
    }

    private fun newRepository() = AppRepository(context) { token -> TelegramClient(token, fake) }
    private fun token(id: Long) = "$id:${"synthetic_test_credentials_".repeat(2)}"
    private fun member(id: Long) = members.first { it.botId == id }

    private fun savedPair(ownId: Long, legacyRole: String, peerId: Long, sharing: Boolean = false) {
        vault.put(token(ownId))
        prefs.edit().putString("transport", "telegram_direct").putString("role", legacyRole)
            .putString("botUsername", member(ownId).username).putLong("ownBotId", ownId)
            .putString("peerBotUsername", member(peerId).username).putLong("peerBotId", peerId)
            .putBoolean("sharingEnabled", sharing).commit()
    }

    @Test fun preparingCareUsesPersistedNegotiationAndKnownVersionWithoutNetworkCalls() = runBlocking {
        savedPair(101, "guardian", 103)
        val known = FamilyCareState(activeRoom.id, 103, UUID.randomUUID().toString(), 8, false,
            FamilyCareSnapshots.projection(TelegramLedger.emptyState()))
        store.transaction {
            store.familyChat.setActiveRoom(activeRoom)
            store.familyCare.saveState(known)
            val incoming = JSONObject().put("message", JSONObject()
                .put("from", JSONObject().put("id", 103).put("is_bot", true))
                .put("chat", JSONObject().put("id", 103).put("type", "private"))
                .put("text", FamilyTransportProtocol.careCapability(42, false)))
            val transport = FamilyTransport(TelegramClient(token(101), fake), store, activeRoom, 101)
            assertTrue(transport.incoming(incoming)!!.isEmpty())
        }
        // Recreate the repository: the decision must come from durable authenticated metadata.
        newRepository().prepareCare()
        val packets = store.familyCare.pendingPackets(activeRoom.id).map { JSONObject(it.text) }
        val son = packets.single { it.getLong("childId") == 103L }
        assertEquals("care_sync", son.getString("type"))
        assertEquals(known.epoch, son.getJSONObject("body").getString("epoch"))
        assertEquals(known.revision, son.getJSONObject("body").getLong("revision"))
        assertEquals(FamilyCareValidation.digest(FamilyCareSnapshots.projection(known.state)),
            son.getJSONObject("body").getString("digest"))
        assertEquals("sync_request", packets.single { it.getLong("childId") == 104L }.getString("type"))
        assertTrue(fake.methods.isEmpty())
        assertFalse(repo.sharingEnabled)
    }

    @Test fun preparingCareKeepsOriginalRequestForPeerWithoutDeltaNegotiation() = runBlocking {
        savedPair(101, "guardian", 103)
        store.transaction {
            store.familyChat.setActiveRoom(activeRoom)
            store.familyCare.saveState(FamilyCareState(activeRoom.id, 103, UUID.randomUUID().toString(), 8, false,
                FamilyCareSnapshots.projection(TelegramLedger.emptyState())))
        }
        repo.prepareCare()
        val packets = store.familyCare.pendingPackets(activeRoom.id).map { JSONObject(it.text) }
        assertTrue(packets.all { it.getString("type") == "sync_request" && it.getJSONObject("body").length() == 0 })
        assertTrue(fake.methods.isEmpty())
    }

    @Test fun roomOnlyParentsAndChildrenUseTheirRosterRoleAndFamilyMemberHasNoCareAccess() = runBlocking {
        members.forEach { own ->
            clearAccount()
            repo.joinFamilyRoom(token(own.botId), activeRoom.toCode())
            assertTrue(repo.configured)
            assertFalse(repo.paired)
            val parent = own.relationship in setOf("mother", "father")
            val child = own.relationship in setOf("son", "daughter")
            assertEquals(parent || child, repo.careEnabled)
            assertEquals(if (parent) "guardian" else "child", repo.effectiveRole)
            assertEquals(child, repo.canShareLocation)
            assertFalse(repo.sharingEnabled)
            assertEquals(if (child) own.botId else if (parent) 103L else null, repo.selectedCareChildId)
        }
        assertTrue(fake.methods.all { it in setOf("getMe", "getWebhookInfo", "sendMessage") })
        assertEquals(members.size * (members.size - 1), fake.greetings.size)
    }

    @Test fun careRoleCanDifferFromPreservedLegacyRoleWithoutGrantingParentLocationCollection() = runBlocking {
        savedPair(101, "child", 102)
        repo.joinFamilyRoom("", activeRoom.toCode())
        assertEquals("child", repo.role)
        assertEquals("guardian", repo.effectiveRole)
        assertTrue(repo.paired)
        assertFalse(repo.isChild)
        assertFalse(repo.canShareLocation)

        clearAccount()
        savedPair(103, "guardian", 104)
        repo.joinFamilyRoom("", activeRoom.toCode())
        assertEquals("guardian", repo.role)
        assertEquals("child", repo.effectiveRole)
        assertTrue(repo.isChild)
        assertTrue(repo.canShareLocation)
        assertEquals(103L, repo.selectedCareChildId)
        assertTrue(runCatching { repo.selectCareChild(104) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun selectedChildSurvivesRepositoryRestartAndIsStoredSeparatelyPerRoom() = runBlocking {
        repo.joinFamilyRoom(token(101), activeRoom.toCode())
        assertEquals(103L, repo.selectedCareChildId)
        repo.selectCareChild(104)
        assertEquals(104L, newRepository().selectedCareChildId)
        listOf(101L, 102L, 105L, 999L).forEach { invalid ->
            assertTrue(runCatching { repo.selectCareChild(invalid) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(104L, repo.selectedCareChildId)
        }
        repo.leaveFamilyRoom()
        repo.joinFamilyRoom("", secondRoom.toCode())
        assertEquals(103L, repo.selectedCareChildId)
        repo.leaveFamilyRoom()
        repo.joinFamilyRoom("", activeRoom.toCode())
        assertEquals(104L, repo.selectedCareChildId)
    }

    @Test fun childTelemetryQueuesOnlyForBothParentsNeverSiblingOrChatOnlyFamily() = runBlocking {
        repo.joinFamilyRoom(token(103), activeRoom.toCode())
        fake.methods.clear()
        val ids = mutableSetOf<String>()
        val telemetry = listOf(
            "location" to locationPayload(),
            "vertical" to JSONObject().put("phase", "ascent_started").put("confidence", "estimated")
                .put("relativeMeters", 2.5).put("measuredAt", measuredAt),
            "heartbeat" to JSONObject().put("recordedAt", measuredAt).put("batteryPercent", 76),
            "sharing_status" to JSONObject().put("enabled", false),
        )
        telemetry.forEach { (kind, payload) ->
            val id = UUID.randomUUID().toString().also(ids::add)
            repo.enqueueEventOnly(kind, payload, id)
        }
        val packets = store.familyCare.pendingPackets(activeRoom.id)
        assertEquals(setOf(101L, 102L), packets.map { it.peerId }.toSet())
        val events = packets.filter { JSONObject(it.text).optString("type") == "child_event" }
        assertEquals(telemetry.size * 2, events.size)
        assertEquals(ids, events.map { JSONObject(it.text).getJSONObject("body").getJSONObject("event").getString("id") }.toSet())
        events.groupBy { JSONObject(it.text).getJSONObject("body").getJSONObject("event").getString("id") }.forEach { (_, recipients) ->
            assertEquals(setOf(101L, 102L), recipients.map { it.peerId }.toSet())
            assertTrue(recipients.all { it.childId == 103L && it.roomId == activeRoom.id })
        }
        assertTrue(store.pending().isEmpty())
        assertTrue(store.familyChat.pendingChatDeliveries(activeRoom.id).isEmpty())
        assertFalse(repo.sharingEnabled)
        assertFalse(fake.methods.contains("sendMessage"))
    }

    @Test fun joiningAndPreparingCarePreservesOffSharingAndDoesNotSeedFromAParentsLegacyCache() = runBlocking {
        savedPair(103, "guardian", 104, sharing = false)
        val legacyLocation = locationEvent()
        val legacy = TelegramLedger.apply(TelegramLedger.emptyState(), legacyLocation)
            .put("stickerBalance", 42)
            .put("rewards", JSONArray().put(JSONObject().put("id", UUID.randomUUID().toString())
                .put("name", "다른 자녀의 약속").put("cost", 5)))
        store.cache(legacy)
        repo.joinFamilyRoom("", activeRoom.toCode())
        repo.prepareCare()
        val authority = checkNotNull(store.familyCare.state(activeRoom.id, 103))
        assertTrue(authority.authoritative)
        assertEquals(0, authority.snapshot.stickerBalance)
        assertTrue(authority.snapshot.rewards.isEmpty())
        assertTrue(authority.snapshot.events.none { it.id == legacyLocation.id })
        assertTrue(repo.movementHistory().events.isEmpty())
        assertEquals(42, repo.cached().stickerBalance)
        assertEquals("guardian", repo.role)
        assertEquals("child", repo.effectiveRole)
        assertFalse(repo.sharingEnabled)
        assertFalse(newRepository().sharingEnabled)
    }

    @Test fun parentLegacyMovementIsImportedOnlyForItsPinnedChildAndNeverAsAnAuthoritativeBoard() = runBlocking {
        savedPair(101, "guardian", 103)
        val retained = locationEvent()
        store.cache(TelegramLedger.apply(TelegramLedger.emptyState().put("stickerBalance", 9), retained))
        repo.joinFamilyRoom("", activeRoom.toCode())
        repo.prepareCare()
        assertNull(repo.careSnapshot(103))
        assertNull(repo.careSnapshot(104))
        assertEquals(listOf(retained.id), repo.movementHistory().events.map { it.id })
        repo.selectCareChild(104)
        assertTrue(repo.movementHistory().events.isEmpty())
        assertEquals(9, repo.cached().stickerBalance)
    }

    @Test fun movementQueriesSeparateSameEventIdentityByBothRoomAndSelectedChild() = runBlocking {
        repo.joinFamilyRoom(token(101), activeRoom.toCode())
        val sharedId = UUID.randomUUID().toString()
        val son = locationEvent(sharedId, 10.0)
        val daughter = locationEvent(sharedId, 20.0)
        val otherRoom = locationEvent(sharedId, 30.0)
        store.transaction {
            store.familyCare.archiveEvent(activeRoom.id, 103, son)
            store.familyCare.archiveEvent(activeRoom.id, 104, daughter)
            store.familyCare.archiveEvent(secondRoom.id, 103, otherRoom)
        }
        assertEquals(10.0, repo.movementHistory().events.single().payload.getDouble("latitude"), 0.0)
        repo.selectCareChild(104)
        assertEquals(20.0, repo.movementHistory().events.single().payload.getDouble("latitude"), 0.0)
        repo.leaveFamilyRoom()
        repo.joinFamilyRoom("", secondRoom.toCode())
        assertEquals(103L, repo.selectedCareChildId)
        assertEquals(30.0, repo.movementHistory().events.single().payload.getDouble("latitude"), 0.0)
        assertTrue(repo.movementHistory(childId = 104).events.isEmpty())
    }

    @Test fun independentLatestLocationIsVisibleBeforeBoardSyncAndIsScopedToSelectedChild() {
        savedPair(101, "guardian", 103)
        val sonEpoch = UUID.randomUUID().toString()
        val daughterEpoch = UUID.randomUUID().toString()
        val son = locationEvent(latitude = 10.0)
        val daughter = locationEvent(latitude = 20.0)
        store.transaction {
            store.familyChat.setActiveRoom(activeRoom)
            assertTrue(store.familyCare.saveLatestLocationHead(FamilyCareLocationHead(activeRoom.id, 103, sonEpoch, 80, son)))
            assertTrue(store.familyCare.saveLatestLocationHead(FamilyCareLocationHead(activeRoom.id, 104, daughterEpoch, 90, daughter)))
        }
        val first = repo.readUiSnapshot()
        assertNull(first.careSnapshot)
        assertEquals(son.id, first.latestLocation!!.id)
        assertEquals(103L, first.latestLocation!!.senderId)
        store.familyCare.saveState(FamilyCareState(activeRoom.id, 103, sonEpoch, 1, false,
            TelegramLedger.emptyState().put("stickerBalance", 7)))
        val synced = repo.readUiSnapshot()
        assertEquals(7, synced.careSnapshot!!.stickerBalance)
        assertEquals(1L, store.familyCare.stateMetadata(activeRoom.id, 103)!!.revision)
        assertEquals(son.id, synced.latestLocation!!.id)
        repo.selectCareChild(104)
        val selected = repo.readUiSnapshot()
        assertEquals(daughter.id, selected.latestLocation!!.id)
        assertEquals(104L, selected.latestLocation!!.senderId)
        assertNull(selected.careSnapshot)
        assertTrue(fake.methods.isEmpty())
    }

    private fun locationPayload(latitude: Double = 10.0) = JSONObject().put("latitude", latitude)
        .put("longitude", 20.0).put("accuracy", 12.0).put("capturedAt", measuredAt).put("source", "manual")

    private fun locationEvent(id: String = UUID.randomUUID().toString(), latitude: Double = 10.0) =
        FamilyEvent(id, "location", locationPayload(latitude), "child", measuredAt, "relayed")

    private class IdentityTelegram(private val members: List<FamilyChatMember>) : TelegramHttpTransport {
        val methods = mutableListOf<String>()
        val greetings = mutableListOf<JSONObject>()
        override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse {
            methods += method
            val own = members.first { it.botId == token.substringBefore(':').toLong() }
            val result = when (method) {
                "getMe" -> JSONObject().put("id", own.botId).put("is_bot", true).put("username", own.username.removePrefix("@"))
                "getWebhookInfo" -> JSONObject().put("url", "")
                "sendMessage" -> {
                    val request = JSONObject(json)
                    assertEquals("hello", JSONObject(request.getString("text")).getString("type"))
                    val peer = members.first { it.username == request.getString("chat_id") }
                    assertNotEquals(own.botId, peer.botId)
                    greetings += request
                    JSONObject().put("chat", JSONObject().put("id", peer.botId).put("type", "private")
                        .put("username", peer.username.removePrefix("@")))
                }
                else -> error("Repository setup tests must not send or poll Telegram: $method")
            }
            return TelegramHttpResponse(200, JSONObject().put("ok", true).put("result", result).toString())
        }
    }
}
