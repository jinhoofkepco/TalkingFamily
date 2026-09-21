package kr.family.homeway.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kr.family.homeway.overlay.FloatingStarService
import kr.family.homeway.tracking.TrackingService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/** This suite is explicitly emulator-only. Every Telegram request uses the injected in-process fake. */
@RunWith(AndroidJUnit4::class)
class AppRepositoryFamilyRoomTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("homeway_settings", Context.MODE_PRIVATE)
    private val vault get() = TokenVault(context)
    private val store get() = LocalStore.get(context)
    private lateinit var fake: IdentityTelegram
    private lateinit var repo: AppRepository
    private var prepared = false

    @Before fun prepare() = runBlocking {
        assumeTrue("Repository reset tests must never run on a physical family phone", Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.startsWith("generic/") || Build.FINGERPRINT.startsWith("generic_x86/"))
        context.stopService(Intent(context, TelegramReceiveService::class.java))
        context.stopService(Intent(context, FloatingStarService::class.java))
        TrackingService.stopAndAwait(context)
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        store.transaction { store.clear() }
        prefs.edit().clear().commit()
        vault.clear()
        context.getSharedPreferences("homeway_receiver", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
        fake = IdentityTelegram()
        repo = AppRepository(context) { token -> TelegramClient(token, fake) }
        prepared = true
    }

    @After fun cleanUp() {
        if (!prepared) return
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        store.transaction { store.clear() }
        prefs.edit().clear().commit()
        vault.clear()
    }

    private fun token(id: Long) = "$id:${"test_credentials_".repeat(2)}"
    private fun room(ownId: Long = 101, ownRelationship: String = "son", ownName: String = "아들") = FamilyChatRoom.create("우리 가족", listOf(
        FamilyChatMember(ownId, "@same_username_bot", ownName, ownRelationship),
        FamilyChatMember(202, "@guardian_family_bot", "엄마", "mother"),
        FamilyChatMember(303, "@sibling_family_bot", "딸", "daughter")))

    @Test fun legacyPairWithoutOwnIdKeepsCursorLedgerOutboxSharingAndPeerWhenJoining() = runBlocking {
        vault.put(token(101))
        prefs.edit().putString("transport", "telegram_direct").putString("role", "child")
            .putString("botUsername", "@same_username_bot").putString("peerBotUsername", "@guardian_family_bot")
            .putLong("peerBotId", 202).putBoolean("sharingEnabled", true).commit()
        assertEquals(0L, repo.selfBotId)
        val event = FamilyEvent(UUID.randomUUID().toString(), "chat", JSONObject().put("text", "기존에 대기하던 메시지"),
            "child", "2026-09-22T12:00:00Z", "pending")
        val state = TelegramLedger.apply(TelegramLedger.emptyState().put("stickerBalance", 7), event)
        store.transaction {
            store.cache(state); store.enqueue(event); store.setMeta("offset", 7310)
            store.setMeta("sentAt", 99); store.queueReceipt(UUID.randomUUID().toString())
        }
        val oldCache = store.cached()!!.toString()
        val oldReceipts = store.receipts()
        repo.joinFamilyRoom("", room().toCode())

        assertTrue(repo.configured)
        assertTrue(repo.paired)
        assertTrue(repo.sharingEnabled)
        assertEquals("child", repo.role)
        assertEquals(101L, repo.selfBotId)
        assertEquals(202L, prefs.getLong("peerBotId", 0))
        assertEquals("@guardian_family_bot", repo.peerBotUsername)
        assertEquals(7310L, store.meta("offset"))
        assertEquals(99L, store.meta("sentAt"))
        assertEquals(oldCache, store.cached()!!.toString())
        assertEquals(oldReceipts, store.receipts())
        assertEquals(listOf(event.id), store.pending().map { it.id })
        assertEquals(token(101), vault.get())
        assertEquals(listOf("getMe", "getWebhookInfo"), fake.methods)
    }

    @Test fun roomOnlyPhoneIsConfiguredButCannotEnqueueLegacyLocationOrPraise() = runBlocking {
        val active = room(ownRelationship = "mother", ownName = "엄마")
        repo.joinFamilyRoom(token(101), active.toCode())
        assertTrue(repo.configured)
        assertFalse(repo.paired)
        assertEquals("guardian", repo.role)
        assertFalse(repo.sharingEnabled)
        assertEquals(active, repo.room)
        val locationFailure = runCatching { repo.enqueueEventOnly("location", JSONObject().put("source", "automatic")) }.exceptionOrNull()
        val praiseFailure = runCatching { repo.enqueueEventOnly("sticker_award", JSONObject().put("count", 1).put("reason", "칭찬")) }.exceptionOrNull()
        assertTrue(locationFailure is IllegalStateException)
        assertTrue(praiseFailure is IllegalStateException)
        assertTrue(store.pending().isEmpty())
        assertEquals(listOf("getMe", "getWebhookInfo"), fake.methods)
    }

    @Test fun leavingRoomStillPinsAccountIdEvenWhenDifferentBotReusesSameUsername() = runBlocking {
        val original = room()
        repo.joinFamilyRoom(token(101), original.toCode())
        store.setMeta("offset", 8129)
        val saved = FamilyChatMessage(UUID.randomUUID().toString(), original.id, 101, "남아 있는 대화", "2026-09-22T12:00:00Z")
        store.familyChat.insertChatMessage(saved, listOf(202, 303))
        repo.leaveFamilyRoom()
        assertFalse(repo.configured)
        assertNull(repo.room)
        val attempt = runCatching { repo.joinFamilyRoom(token(999), room(ownId = 999).toCode()) }.exceptionOrNull()
        assertTrue(attempt is IllegalArgumentException)
        assertEquals(101L, repo.selfBotId)
        assertEquals(token(101), vault.get())
        assertEquals(8129L, store.meta("offset"))
        assertNull(repo.room)
        assertEquals(saved.text, store.familyChat.chatMessage(original.id, saved.id)?.text)
        assertEquals(2, store.familyChat.pendingChatDeliveries(original.id).size)
        assertFalse(repo.configured)
    }

    @Test fun changedRosterWithSameRoomIdCannotPartlyChangeRoleOrReplaceActiveRoom() = runBlocking {
        val original = room(ownRelationship = "father", ownName = "아빠")
        repo.joinFamilyRoom(token(101), original.toCode())
        store.setMeta("offset", 500)
        val changed = original.copy(members = original.members.map {
            if (it.botId == 101L) it.copy(relationship = "son", displayName = "아들") else it
        })
        val attempt = runCatching { repo.joinFamilyRoom("", changed.toCode()) }.exceptionOrNull()
        assertTrue(attempt is IllegalArgumentException)
        assertEquals("guardian", repo.role)
        assertEquals(original, repo.room)
        assertEquals(101L, repo.selfBotId)
        assertEquals(token(101), vault.get())
        assertEquals(500L, store.meta("offset"))
        assertTrue(repo.configured)
        assertFalse(repo.paired)
    }

    private class IdentityTelegram : TelegramHttpTransport {
        val methods = mutableListOf<String>()
        override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse {
            methods += method
            val id = token.substringBefore(':').toLong()
            val result = when (method) {
                "getMe" -> JSONObject().put("id", id).put("is_bot", true).put("username", "same_username_bot")
                "getWebhookInfo" -> JSONObject().put("url", "")
                "sendMessage" -> error("Joining a family room must not send a network message")
                else -> error("Unexpected Telegram method in setup test: $method")
            }
            return TelegramHttpResponse(200, JSONObject().put("ok", true).put("result", result).toString())
        }
    }
}
