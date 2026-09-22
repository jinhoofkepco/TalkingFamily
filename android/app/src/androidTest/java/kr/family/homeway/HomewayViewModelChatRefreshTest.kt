package kr.family.homeway

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kr.family.homeway.data.*
import kr.family.homeway.overlay.FloatingStarService
import kr.family.homeway.tracking.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Emulator-only local account. All transport stays in this fake; no real bot receives a message. */
@RunWith(AndroidJUnit4::class)
class HomewayViewModelChatRefreshTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("homeway_settings", Context.MODE_PRIVATE)
    private val notificationPrefs get() = context.getSharedPreferences("family_notifications", Context.MODE_PRIVATE)
    private val store get() = LocalStore.get(context)
    private val models = ViewModelStore()
    private lateinit var fake: PausedTelegram
    private lateinit var repo: AppRepository
    private lateinit var model: HomewayViewModel
    private lateinit var room: FamilyChatRoom
    private var prepared = false
    private val presenceOwner = Any()

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
        notificationPrefs.edit().clear().commit()
        TokenVault(context).clear()
        context.getSharedPreferences("homeway_receiver", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
        prepared = true
        fake = PausedTelegram()
        repo = AppRepository(context) { token -> TelegramClient(token, fake) }
        // Conversation-only members keep this test independent of location/praise workers.
        room = FamilyChatRoom.create("화면 갱신 시험", listOf(
            FamilyChatMember(101, "@refresh_self_bot", "이 휴대폰", "family"),
            FamilyChatMember(202, "@refresh_peer_bot", "가족", "family")))
        repo.joinFamilyRoom("101:${"test_credentials_".repeat(2)}", room.toCode())
        instrumentation.runOnMainSync {
            model = HomewayViewModel(context.applicationContext as Application, repo)
            models.put("chat-refresh", model)
        }
        awaitState { model.state.value.room?.id == room.id && !model.state.value.roomLoading }
    }

    @After fun cleanUp() {
        if (!prepared) return
        TelegramChatPresence.setVisible(presenceOwner, false)
        if (::fake.isInitialized) {
            fake.releaseAck.countDown()
            fake.releasePoll.countDown()
        }
        instrumentation.runOnMainSync { models.clear() }
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        store.transaction { store.clear() }
        prefs.edit().clear().commit()
        notificationPrefs.edit().clear().commit()
        TokenVault(context).clear()
    }

    @Test fun committedIncomingMessageAppearsWhileItsNetworkAcknowledgementIsStillBlocked() = runBlocking {
        val incoming = message("저장되면 바로 보여요")
        fake.incoming = update(incoming)
        fake.pauseAck.set(true)
        val receiverRepo = AppRepository(context) { token -> TelegramClient(token, fake) }
        val receive = async(Dispatchers.IO) { receiverRepo.synchronize() }
        try {
            assertTrue("The fake ACK request should pause after the receive commit",
                fake.ackStarted.await(10, TimeUnit.SECONDS))
            // No model.refresh() call: a different repository instance's commit must wake the UI.
            awaitState { model.state.value.roomEvents.any { it.id == incoming.id } }
            assertEquals(incoming.text, model.state.value.roomEvents.single { it.id == incoming.id }.payload.getString("text"))
            assertFalse("Displaying the saved message must not wait for ACK HTTP", receive.isCompleted)
        } finally {
            fake.releaseAck.countDown()
            withTimeout(10_000) { receive.await() }
        }
    }

    @Test fun refreshDisplaysLocalHistoryBeforeWaitingForAnExistingLongPoll() = runBlocking {
        fake.pausePoll.set(true)
        val receive = async(Dispatchers.IO) { repo.synchronize(10) }
        try {
            assertTrue(fake.pollStarted.await(10, TimeUnit.SECONDS))
            val incoming = message("긴 수신 대기와 별개로 표시")
            store.transaction { store.familyChat.insertChatMessage(incoming, emptyList()) }
            instrumentation.runOnMainSync { model.refresh() }
            awaitState { model.state.value.roomEvents.any { it.id == incoming.id } }
            assertFalse("Local rendering must not wait for the network mutex", receive.isCompleted)
        } finally {
            fake.releasePoll.countDown()
            withTimeout(10_000) { receive.await() }
        }
    }

    @Test fun scheduledRefreshWaitsInBackgroundAndOpeningChatReceivesImmediately() = runBlocking {
        val incoming = message("배경에서는 기다리고 대화를 열면 확인")
        fake.incoming = update(incoming)
        TelegramChatPresence.setVisible(presenceOwner, true)
        TelegramChatPresence.setVisible(presenceOwner, false)
        repeat(3) { repo.refreshScheduled() }
        assertEquals("Automatic refresh must not consume Telegram before the shared deadline", 0, fake.pollRequests.get())
        assertNull(store.familyChat.chatMessage(room.id, incoming.id))
        assertTrue(repo.receiveDelayMillis() in 1..5_000)

        TelegramChatPresence.setVisible(presenceOwner, true)
        repo.refreshScheduled()
        assertEquals(1, fake.pollRequests.get())
        assertEquals(incoming.text, store.familyChat.chatMessage(room.id, incoming.id)?.text)
        awaitState { model.state.value.roomEvents.any { it.id == incoming.id } }
    }

    @Test fun outgoingOnlySendsWithoutConsumingUpdatesOrResettingTheBackgroundDeadline() = runBlocking {
        fake.incoming = update(message("다음 수신 시각까지 기다릴 메시지"))
        val outgoing = FamilyChatMessage(UUID.randomUUID().toString(), room.id, 101, "즉시 발송",
            "2026-09-22T12:00:00Z")
        store.transaction { store.familyChat.insertChatMessage(outgoing, listOf(202)) }
        TelegramChatPresence.setVisible(presenceOwner, true)
        TelegramChatPresence.setVisible(presenceOwner, false)
        val before = repo.receiveDelayMillis()
        repo.flushOutgoing()
        val after = repo.receiveDelayMillis()
        assertEquals(0, fake.pollRequests.get())
        assertEquals(1, fake.sentChats.get())
        assertEquals(0L, store.meta("offset"))
        assertTrue("Only elapsed time may change the receive deadline", after in 1..before)
    }

    @Test fun notificationSoundDefaultsOffAndBothChoicesSurviveViewModelRecreation() {
        assertFalse(model.state.value.messageNotificationSoundEnabled)
        assertFalse(FamilyNotifications.soundEnabled(context))

        instrumentation.runOnMainSync { model.setMessageNotificationSoundEnabled(true) }
        assertTrue(model.state.value.messageNotificationSoundEnabled)
        assertTrue(notificationPrefs.getBoolean("sound_enabled", false))
        recreateModel()
        assertTrue("A new ViewModel must reload the saved ON choice", model.state.value.messageNotificationSoundEnabled)

        instrumentation.runOnMainSync { model.setMessageNotificationSoundEnabled(false) }
        assertFalse(model.state.value.messageNotificationSoundEnabled)
        assertFalse(notificationPrefs.getBoolean("sound_enabled", true))
        recreateModel()
        assertFalse("A new ViewModel must reload the saved OFF choice", model.state.value.messageNotificationSoundEnabled)
        assertEquals("Changing notification sound must not poll Telegram", 0, fake.pollRequests.get())
        assertEquals("Changing notification sound must not send a message", 0, fake.sentChats.get())
    }

    private fun recreateModel() {
        instrumentation.runOnMainSync {
            models.clear()
            val reopened = AppRepository(context) { token -> TelegramClient(token, fake) }
            model = HomewayViewModel(context.applicationContext as Application, reopened)
            models.put("chat-refresh", model)
        }
    }

    private suspend fun awaitState(predicate: () -> Boolean) = withTimeout(5_000) {
        while (!predicate()) delay(10)
    }

    private fun message(text: String) = FamilyChatMessage(UUID.randomUUID().toString(), room.id, 202, text,
        "2026-09-22T12:00:00Z")

    private fun update(message: FamilyChatMessage) = JSONObject().put("update_id", 1).put("message", JSONObject()
        .put("text", FamilyChatProtocol.message(message))
        .put("from", JSONObject().put("id", 202).put("is_bot", true))
        .put("chat", JSONObject().put("id", 202).put("type", "private")))

    private class PausedTelegram : TelegramHttpTransport {
        @Volatile var incoming: JSONObject? = null
        val pollRequests = AtomicInteger()
        val sentChats = AtomicInteger()
        val pauseAck = AtomicBoolean(false)
        val pausePoll = AtomicBoolean(false)
        val ackStarted = CountDownLatch(1)
        val pollStarted = CountDownLatch(1)
        val releaseAck = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)

        override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse {
            val body = JSONObject(json)
            val result: Any = when (method) {
                "getMe" -> JSONObject().put("id", 101).put("is_bot", true).put("username", "refresh_self_bot")
                "getWebhookInfo" -> JSONObject().put("url", "")
                "getUpdates" -> {
                    pollRequests.incrementAndGet()
                    if (pausePoll.compareAndSet(true, false)) {
                        pollStarted.countDown()
                        check(releasePoll.await(15, TimeUnit.SECONDS)) { "Test did not release its fake poll" }
                    }
                    JSONArray().apply {
                        incoming?.takeIf { it.getLong("update_id") >= body.getLong("offset") }?.let { put(it) }
                    }
                }
                "sendMessage" -> {
                    assertEquals(202L, body.getLong("chat_id"))
                    val type = JSONObject(body.getString("text")).getString("type")
                    assertTrue(type in setOf("chat", "chat_ack"))
                    if (type == "chat") sentChats.incrementAndGet()
                    if (type == "chat_ack" && pauseAck.compareAndSet(true, false)) {
                        ackStarted.countDown()
                        check(releaseAck.await(15, TimeUnit.SECONDS)) { "Test did not release its fake ACK" }
                    }
                    JSONObject().put("chat", JSONObject().put("id", 202).put("type", "private"))
                }
                else -> error("Unexpected fake Telegram method $method")
            }
            return TelegramHttpResponse(200, JSONObject().put("ok", true).put("result", result).toString())
        }
    }
}
