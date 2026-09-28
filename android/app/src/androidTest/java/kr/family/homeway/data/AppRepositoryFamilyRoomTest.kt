package kr.family.homeway.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kr.family.homeway.overlay.FloatingStarService
import kr.family.homeway.tracking.TrackingService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
        assertEquals(listOf("getMe", "getWebhookInfo", "sendMessage", "sendMessage"), fake.methods)
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
        assertEquals(listOf("getMe", "getWebhookInfo", "sendMessage", "sendMessage"), fake.methods)
    }

    @Test fun joiningGreetsEveryOtherMemberByUsernameWithoutSendingFamilyContent() = runBlocking {
        val active = room()
        repo.joinFamilyRoom(token(101), active.toCode())

        assertEquals(active, repo.room)
        assertEquals(listOf("@guardian_family_bot", "@sibling_family_bot"),
            fake.sent.map { it.getString("chat_id") })
        fake.sent.forEach { request ->
            assertTrue(request.getBoolean("protect_content"))
            val hello = JSONObject(request.getString("text"))
            assertEquals(setOf("app", "v", "type"), hello.keys().asSequence().toSet())
            assertEquals("TalkingFamily", hello.getString("app"))
            assertEquals(3, hello.getInt("v"))
            assertEquals("hello", hello.getString("type"))
            // A greeting cannot become a visible chat or acknowledgement on the receiving phone.
            val senderUpdate = JSONObject().put("message", JSONObject().put("text", hello.toString())
                .put("from", JSONObject().put("id", 101).put("is_bot", true))
                .put("chat", JSONObject().put("id", 101).put("type", "private")))
            assertNull(FamilyChatProtocol.receive(senderUpdate, active, 202))
        }
        assertTrue(store.familyChat.chatHistory(active.id).messages.isEmpty())
        assertTrue(store.familyChat.pendingChatDeliveries(active.id).isEmpty())
    }

    @Test fun incorrectOrNonPrivateGreetingIdentityCannotSaveTheRoomOrCredentials() = runBlocking {
        val invalidChats = listOf(
            JSONObject(),
            JSONObject().put("id", 999).put("type", "private"),
            JSONObject().put("id", "202").put("type", "private"),
            JSONObject().put("id", 202.5).put("type", "private"),
            JSONObject().put("id", 202).put("type", "group"),
            JSONObject().put("id", 202).put("type", "private").put("username", "different_owner_bot"),
        )
        val beforePrefs = prefs.all.toMap()
        invalidChats.forEach { chat ->
            fake.sent.clear()
            fake.chatOverrides["@guardian_family_bot"] = chat
            val failure = runCatching { repo.joinFamilyRoom(token(101), room().toCode()) }.exceptionOrNull()
            assertTrue("Unexpected greeting identity must fail: $chat", failure is TelegramException)
            assertEquals(TelegramFailureReason.PEER_IDENTITY_MISMATCH, (failure as TelegramException).reason)
            assertEquals("Failure must stop before greeting the next family member", 1, fake.sent.size)
            assertNull(repo.room)
            assertEquals("", vault.get())
            assertEquals(beforePrefs, prefs.all)
            assertEquals(0L, repo.selfBotId)
            assertFalse(repo.configured)
        }
    }

    @Test fun absentOptionalUsernameAndCaseInsensitiveMatchingUsernameAreAccepted() = runBlocking {
        fake.chatOverrides["@guardian_family_bot"] = JSONObject().put("id", 202).put("type", "private")
        fake.chatOverrides["@sibling_family_bot"] = JSONObject().put("id", 303).put("type", "private")
            .put("username", "SIBLING_FAMILY_BOT")
        val active = room()
        repo.joinFamilyRoom(token(101), active.toCode())
        assertEquals(active, repo.room)
        assertEquals(2, fake.sent.size)
    }

    @Test fun usernameGreetingLetsAnUnopenedSiblingConversationAcceptTheNextNumericSend() = runBlocking {
        val active = room()
        val sibling = active.members.single { it.botId == 303L }
        val message = FamilyChatMessage(UUID.randomUUID().toString(), active.id, 101, "형제 사이 시험 메시지",
            "2026-09-22T12:00:00Z")
        val text = FamilyChatProtocol.message(message)
        val client = TelegramClient(token(101), fake)
        // Model Telegram returning a valid getChat identity without opening a private conversation.
        val before = runCatching { client.sendToFamilyMember(sibling, text) }.exceptionOrNull()
        assertTrue(before is TelegramException)
        assertEquals(TelegramFailureReason.CHAT_NOT_FOUND, (before as TelegramException).reason)
        assertEquals(listOf("sendMessage", "getChat", "sendMessage"), fake.methods)
        fake.sent.clear()
        fake.methods.clear()

        repo.joinFamilyRoom(token(101), active.toCode())
        val result = client.sendToFamilyMember(sibling, text)

        assertEquals(303L, result.getJSONObject("chat").getLong("id"))
        assertEquals(listOf("@guardian_family_bot", "@sibling_family_bot"),
            fake.sent.dropLast(1).map { it.getString("chat_id") })
        assertEquals(303L, fake.sent.last().getLong("chat_id"))
        assertEquals(text, fake.sent.last().getString("text"))
        assertEquals(listOf("getMe", "getWebhookInfo", "sendMessage", "sendMessage", "sendMessage"), fake.methods)
    }

    @Test fun failedLaterGreetingDuringRejoinPreservesAccountHistoryAndPendingDeliveries() = runBlocking {
        val active = room()
        repo.joinFamilyRoom(token(101), active.toCode())
        store.setMeta("offset", 9172)
        val saved = FamilyChatMessage(UUID.randomUUID().toString(), active.id, 101, "이미 저장한 가족 대화",
            "2026-09-22T12:00:00Z")
        store.familyChat.insertChatMessage(saved, listOf(202, 303))
        val beforePrefs = prefs.all.toMap()
        val beforeDeliveries = store.familyChat.pendingChatDeliveries(active.id)
        fake.sent.clear()
        fake.failPeer = "@sibling_family_bot"

        val failure = runCatching {
            repo.joinFamilyRoom(token(101) + "rotated", active.toCode())
        }.exceptionOrNull()

        assertTrue(failure is TelegramException)
        assertEquals(403, (failure as TelegramException).errorCode)
        assertEquals(listOf("@guardian_family_bot", "@sibling_family_bot"),
            fake.sent.map { it.getString("chat_id") })
        assertEquals(active, repo.room)
        assertEquals(token(101), vault.get())
        assertEquals(beforePrefs, prefs.all)
        assertEquals(9172L, store.meta("offset"))
        assertEquals(saved.text, store.familyChat.chatMessage(active.id, saved.id)?.text)
        assertEquals(beforeDeliveries, store.familyChat.pendingChatDeliveries(active.id))
    }

    @Test fun sameRoomRejoinGreetsAgainWithoutLosingHistoryOrTheReceiveCursor() = runBlocking {
        val active = room()
        repo.joinFamilyRoom(token(101), active.toCode())
        val saved = FamilyChatMessage(UUID.randomUUID().toString(), active.id, 101, "다시 참여해도 남는 대화",
            "2026-09-22T12:00:00Z")
        store.familyChat.insertChatMessage(saved, listOf(202, 303))
        store.setMeta("offset", 4712)
        fake.sent.clear()

        repo.joinFamilyRoom("", active.toCode())

        assertEquals(listOf("@guardian_family_bot", "@sibling_family_bot"),
            fake.sent.map { it.getString("chat_id") })
        assertEquals(active, repo.room)
        assertEquals(token(101), vault.get())
        assertEquals(4712L, store.meta("offset"))
        assertEquals(saved.text, store.familyChat.chatMessage(active.id, saved.id)?.text)
        assertEquals(2, store.familyChat.pendingChatDeliveries(active.id).size)
    }

    @Test fun invalidCodeMissingSelfWrongSelfUsernameAndWebhookFailBeforeAnyGreeting() = runBlocking {
        val wrongUsername = room().let { active -> active.copy(members = active.members.map {
            if (it.botId == 101L) it.copy(username = "@different_self_bot") else it
        }) }
        listOf("not a room code", room(ownId = 999).toCode(), wrongUsername.toCode()).forEach { code ->
            assertNotNull(runCatching { repo.joinFamilyRoom(token(101), code) }.exceptionOrNull())
            assertTrue(fake.sent.isEmpty())
            assertNull(repo.room)
            assertEquals("", vault.get())
        }
        fake.webhookUrl = "https://example.invalid/already-configured"
        assertNotNull(runCatching { repo.joinFamilyRoom(token(101), room().toCode()) }.exceptionOrNull())
        assertTrue(fake.sent.isEmpty())
        assertFalse("Joining must not replace the bot's webhook", fake.methods.contains("deleteWebhook"))
        assertNull(repo.room)
        assertEquals("", vault.get())
    }

    @Test fun aDifferentActiveRoomIsRejectedBeforeAnyNetworkRequest() = runBlocking {
        val original = room()
        repo.joinFamilyRoom(token(101), original.toCode())
        fake.methods.clear()
        fake.sent.clear()

        val failure = runCatching { repo.joinFamilyRoom("", room().toCode()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(fake.methods.isEmpty())
        assertTrue(fake.sent.isEmpty())
        assertEquals(original, repo.room)
    }

    @Test fun cancellationDuringGreetingsStopsBeforeTheNextPeerAndBeforeSaving() = runBlocking {
        listOf(1, 2).forEach { cancelAfter ->
            fake.sent.clear()
            lateinit var joining: Deferred<Unit>
            fake.afterGreeting = { if (fake.sent.size == cancelAfter) joining.cancel() }
            joining = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                repo.joinFamilyRoom(token(101), room().toCode())
            }
            joining.start()
            joining.join()

            assertTrue(joining.isCancelled)
            assertEquals(cancelAfter, fake.sent.size)
            assertNull(repo.room)
            assertEquals("", vault.get())
            assertEquals(0L, repo.selfBotId)
        }
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
        fake.methods.clear()
        fake.sent.clear()
        val attempt = runCatching { repo.joinFamilyRoom("", changed.toCode()) }.exceptionOrNull()
        assertTrue(attempt is IllegalArgumentException)
        assertTrue("Reject a changed roster before contacting Telegram", fake.methods.isEmpty())
        assertTrue(fake.sent.isEmpty())
        assertEquals("guardian", repo.role)
        assertEquals(original, repo.room)
        assertEquals(101L, repo.selfBotId)
        assertEquals(token(101), vault.get())
        assertEquals(500L, store.meta("offset"))
        assertTrue(repo.configured)
        assertFalse(repo.paired)

        repo.leaveFamilyRoom()
        assertTrue(runCatching { repo.joinFamilyRoom("", changed.toCode()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue("A left room still pins its roster before network calls", fake.methods.isEmpty())
        assertNull(repo.room)
        assertEquals(token(101), vault.get())
        assertEquals(500L, store.meta("offset"))
    }

    private class IdentityTelegram : TelegramHttpTransport {
        val methods = mutableListOf<String>()
        val sent = mutableListOf<JSONObject>()
        val chatOverrides = mutableMapOf<String, JSONObject>()
        private val openedPeers = mutableSetOf<Long>()
        var failPeer: String? = null
        var webhookUrl = ""
        var afterGreeting: (() -> Unit)? = null
        override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse {
            methods += method
            val id = token.substringBefore(':').toLong()
            val result = when (method) {
                "getMe" -> JSONObject().put("id", id).put("is_bot", true).put("username", "same_username_bot")
                "getWebhookInfo" -> JSONObject().put("url", webhookUrl)
                "getChat" -> chatFor(JSONObject(json).getString("chat_id"))
                "sendMessage" -> {
                    val request = JSONObject(json)
                    sent += request
                    val destination = request.get("chat_id")
                    val type = JSONObject(request.getString("text")).getString("type")
                    if (destination is Number) {
                        assertEquals("chat", type)
                        if (destination.toLong() !in openedPeers) return failure(400, "Bad Request: chat not found")
                        JSONObject().put("chat", JSONObject().put("id", destination).put("type", "private"))
                    } else {
                        val username = request.getString("chat_id")
                        assertEquals("hello", type)
                        if (username == failPeer) return failure(403, "Forbidden: bot-to-bot communication is disabled")
                        val chat = chatOverrides[username] ?: chatFor(username)
                        openedPeers += chatFor(username).getLong("id")
                        afterGreeting?.invoke()
                        JSONObject().put("chat", chat)
                    }
                }
                else -> error("Unexpected Telegram method in setup test: $method")
            }
            return TelegramHttpResponse(200, JSONObject().put("ok", true).put("result", result).toString())
        }

        private fun chatFor(username: String): JSONObject {
            val peerId = when (username) {
                "@guardian_family_bot" -> 202L
                "@sibling_family_bot" -> 303L
                else -> error("Unexpected greeting destination: $username")
            }
            return JSONObject().put("id", peerId).put("type", "private")
                .put("username", username.removePrefix("@"))
        }

        private fun failure(code: Int, description: String) = TelegramHttpResponse(code,
            JSONObject().put("ok", false).put("error_code", code).put("description", description).toString())
    }
}
