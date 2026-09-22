package kr.family.homeway.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kr.family.homeway.R
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Emulator-only fake incoming events; unique channels/prefs and no Telegram or family account access. */
@RunWith(AndroidJUnit4::class)
class FamilyNotificationsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var prefix: String
    private lateinit var ids: FamilyNotificationIds
    private lateinit var publisher: FamilyNotificationPublisher
    private val visible = AtomicBoolean(false)
    private var prepared = false

    @Before fun prepare() {
        assumeTrue("Notification tests must never change a physical family's notification settings",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic/") ||
                Build.FINGERPRINT.startsWith("generic_x86/"))
        if (Build.VERSION.SDK_INT >= 33) UiDevice.getInstance(instrumentation)
            .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        assumeTrue(manager.areNotificationsEnabled())
        prefix = "family-notification-test-${UUID.randomUUID()}"
        ids = FamilyNotificationIds("${prefix}_old", "${prefix}_silent", "${prefix}_sound", "${prefix}_prefs", prefix)
        publisher = FamilyNotificationPublisher(ids, visible::get)
        prepared = true
    }

    @After fun cleanUp() {
        if (!prepared) return
        manager.activeNotifications.filter { it.tag?.startsWith(prefix) == true || it.notification.channelId.startsWith(prefix) }
            .forEach { manager.cancel(it.tag, it.id) }
        manager.notificationChannels.filter { it.id.startsWith(prefix) }.forEach { manager.deleteNotificationChannel(it.id) }
        context.getSharedPreferences(ids.preferences, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun messagesUseOneSilentPrivateNotificationAndNonChatEventsNeverCreateOne() {
        publisher.initialize(context)
        assertFalse(publisher.soundEnabled(context))
        publisher.received(context, event("location"))
        assertTrue(active().isEmpty())
        publisher.received(context, event())
        await { active().size == 1 }
        val first = active().single()
        repeat(5) { publisher.received(context, event()) }
        await { active().singleOrNull()?.notification?.channelId == ids.silentChannel }
        val latest = active().single()
        assertEquals(first.key, latest.key)
        assertEquals(Notification.VISIBILITY_PRIVATE, latest.notification.visibility)
        assertTrue(latest.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(latest.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals("가족의 새 메시지가 도착했어요. 앱에서 확인해 주세요.",
            latest.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(latest.notification.extras.toString().contains("PRIVATE_FAKE_MESSAGE"))
        val channel = manager.getNotificationChannel(ids.silentChannel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    @Test fun optInSoundUsesDefaultChannelAndKeepsSameNotificationWhenUpdatedOrMutedAgain() {
        publisher.setSoundEnabled(context, true)
        assertTrue(FamilyNotificationPublisher(ids).soundEnabled(context))
        publisher.received(context, event())
        await { active().size == 1 }
        val first = active().single()
        val channel = manager.getNotificationChannel(ids.audibleChannel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNotNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        repeat(3) { publisher.received(context, event()) }
        assertEquals(first.key, active().single().key)
        assertTrue(active().single().notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        publisher.setSoundEnabled(context, false)
        publisher.received(context, event())
        await { active().singleOrNull()?.notification?.channelId == ids.silentChannel }
        assertEquals(first.key, active().single().key)
        assertFalse(FamilyNotificationPublisher(ids).soundEnabled(context))
    }

    @Test fun openChatClearsExistingNotificationAndSuppressesIncomingMessages() {
        publisher.received(context, event())
        await { active().size == 1 }
        visible.set(true)
        publisher.onChatOpened(context)
        await { active().isEmpty() }
        repeat(3) { publisher.received(context, event()) }
        assertTrue(active().isEmpty())
        visible.set(false)
        publisher.received(context, event())
        await { active().size == 1 }
    }

    @Test fun finalPresenceCheckAndConcurrentOpenCannotLeaveAnExternalNotification() {
        val reads = AtomicInteger()
        val opening = FamilyNotificationPublisher(ids) { reads.incrementAndGet() >= 2 }
        opening.received(context, event()) // Becomes visible after the initial policy decision.
        assertTrue(active().isEmpty())
        repeat(10) {
            visible.set(false)
            val receiving = thread { publisher.received(context, event()) }
            visible.set(true)
            publisher.onChatOpened(context)
            receiving.join(3_000)
            assertFalse(receiving.isAlive)
            await { active().isEmpty() }
        }
    }

    @Test fun migrationAndChatOpenClearOnlyLegacyMessageChannelAndKeepServiceNotification() {
        manager.createNotificationChannel(NotificationChannel(ids.legacyChannel, "이전 시험 채널", NotificationManager.IMPORTANCE_HIGH))
        val serviceChannel = "${prefix}_service"
        manager.createNotificationChannel(NotificationChannel(serviceChannel, "서비스 시험 채널", NotificationManager.IMPORTANCE_LOW))
        repeat(2) { manager.notify("${prefix}_legacy", it, fixture(ids.legacyChannel)) }
        manager.notify("${prefix}_service", 4100, fixture(serviceChannel, service = true))
        await { manager.activeNotifications.count { it.notification.channelId == ids.legacyChannel } == 2 }
        publisher.initialize(context)
        await { manager.activeNotifications.none { it.notification.channelId == ids.legacyChannel } }
        publisher.onChatOpened(context)
        assertTrue(manager.activeNotifications.any { it.tag == "${prefix}_service" && it.id == 4100 })
        assertNotNull(manager.getNotificationChannel(ids.legacyChannel))
    }

    @Test fun legacyChannelBlockSurvivesMigrationAndSoundOptIn() {
        manager.createNotificationChannel(NotificationChannel(ids.legacyChannel, "차단된 이전 시험 채널", NotificationManager.IMPORTANCE_NONE))
        publisher.initialize(context)
        publisher.received(context, event())
        publisher.setSoundEnabled(context, true)
        publisher.received(context, event())
        assertTrue(active().isEmpty())
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(ids.legacyChannel).importance)
    }

    @Test fun blockedSelectedChannelDoesNotFallBackToAnotherChannel() {
        manager.createNotificationChannel(NotificationChannel(ids.audibleChannel, "차단된 소리 시험 채널", NotificationManager.IMPORTANCE_NONE))
        publisher.setSoundEnabled(context, true)
        publisher.received(context, event())
        assertTrue(active().isEmpty())
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(ids.audibleChannel).importance)
    }

    @Test fun notificationServiceFailureDoesNotEscapeIntoMessageReceiving() {
        val unavailable = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.NOTIFICATION_SERVICE) throw SecurityException("PRIVATE_FAKE_OS_FAILURE")
                return super.getSystemService(name)
            }
        }
        publisher.initialize(unavailable)
        publisher.received(unavailable, event())
        publisher.onChatOpened(unavailable)
        assertTrue(active().isEmpty())
    }

    private fun event(kind: String = "chat") = FamilyEvent(UUID.randomUUID().toString(), kind,
        JSONObject().put("text", "PRIVATE_FAKE_MESSAGE"), "guardian", "2026-09-22T12:00:00Z", "relayed")
    private fun active() = manager.activeNotifications.filter { it.tag == ids.tag && it.id == ids.notificationId }
    private fun fixture(channel: String, service: Boolean = false) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_family_notification).setContentTitle("로컬 알림 시험")
        .setSilent(true).setOngoing(service).setCategory(if (service) NotificationCompat.CATEGORY_SERVICE else NotificationCompat.CATEGORY_MESSAGE).build()
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue(condition())
    }
}
