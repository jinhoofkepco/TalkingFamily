package kr.family.homeway.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kr.family.homeway.MainActivity
import kr.family.homeway.R
import kr.family.homeway.overlay.FloatingStarService

internal object FamilyNotifications {
    private val publisher = FamilyNotificationPublisher()
    fun initialize(context: Context) = publisher.initialize(context)
    fun soundEnabled(context: Context): Boolean = publisher.soundEnabled(context)
    fun setSoundEnabled(context: Context, enabled: Boolean) = publisher.setSoundEnabled(context, enabled)
    fun onChatOpened(context: Context) = publisher.onChatOpened(context)
    fun received(context: Context, event: FamilyEvent) = publisher.received(context, event)
}

/** Separate IDs let local instrumentation exercise the real OS without changing the user's channels. */
internal data class FamilyNotificationIds(
    val legacyChannel: String = "family_messages",
    val silentChannel: String = "family_messages_silent_v2",
    val audibleChannel: String = "family_messages_sound_v2",
    val preferences: String = "family_notifications",
    val tag: String = "family_chat_messages",
    val notificationId: Int = 4201,
)

internal class FamilyNotificationPublisher(
    private val ids: FamilyNotificationIds = FamilyNotificationIds(),
    private val chatVisible: () -> Boolean = { TelegramChatPresence.visible.value },
) {
    private val lock = Any()
    private var initialized = false

    fun soundEnabled(context: Context): Boolean = runCatching {
        preferences(context).getBoolean("sound_enabled", false)
    }.getOrDefault(false)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        try { synchronized(lock) { preferences(context).edit().putBoolean("sound_enabled", enabled).apply() } }
        catch (_: RuntimeException) { AppDiagnostics.record(context, "notification_settings", "메시지 알림 소리 설정을 저장하지 못했어요.") }
        // Keep an existing notification so changing this option cannot restart its alert lifecycle.
        initialize(context)
    }

    fun initialize(context: Context) {
        try { synchronized(lock) {
            val manager = initializeLocked(context)
            if (chatVisible()) manager.cancel(ids.tag, ids.notificationId)
        } } catch (_: RuntimeException) {
            AppDiagnostics.record(context, "notification_channels", "메시지 알림 채널을 준비하지 못했어요.")
        }
    }

    fun onChatOpened(context: Context) {
        try { synchronized(lock) { clearMessages(manager(context)) } } catch (_: RuntimeException) {
            AppDiagnostics.record(context, "notification_clear", "확인한 메시지 알림을 지우지 못했어요.")
        }
    }

    @SuppressLint("MissingPermission")
    fun received(context: Context, event: FamilyEvent) {
        if (event.kind != "chat") return
        try { synchronized(lock) {
            val manager = initializeLocked(context)
            val sound = soundEnabled(context)
            val channel = if (sound) ids.audibleChannel else ids.silentChannel
            val action = FamilyNotificationPolicy.action(
                chatVisible = chatVisible(),
                appAllowed = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
                legacyAllowed = channelAllowed(manager, ids.legacyChannel),
                selectedChannelAllowed = channelAllowed(manager, channel),
                soundEnabled = sound,
            )
            when (action) {
                FamilyNotificationPolicy.Action.CLEAR -> { manager.cancel(ids.tag, ids.notificationId); return }
                FamilyNotificationPolicy.Action.IGNORE -> return
                else -> Unit
            }
            val intent = Intent(context, MainActivity::class.java).setAction(FloatingStarService.ACTION_OPEN_CHAT)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val open = PendingIntent.getActivity(context, 41, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_family_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText("가족의 새 메시지가 도착했어요. 앱에서 확인해 주세요.")
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                .setSilent(action == FamilyNotificationPolicy.Action.SILENT)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            // MainActivity sets presence before calling onChatOpened. Both clear/post share this lock.
            if (chatVisible()) { manager.cancel(ids.tag, ids.notificationId); return }
            try { manager.notify(ids.tag, ids.notificationId, notification) }
            catch (_: SecurityException) {
                AppDiagnostics.record(context, "notification_permission", "알림 권한이 변경되어 메시지 알림을 표시하지 못했어요.")
                return
            }
            if (chatVisible()) manager.cancel(ids.tag, ids.notificationId)
        } } catch (_: RuntimeException) {
            // Android notification failures must not interrupt the committed chat/ACK receive loop.
            AppDiagnostics.record(context, "notification_post", "메시지 알림을 표시하지 못했어요.")
        }
    }

    private fun initializeLocked(context: Context): NotificationManager {
        val manager = manager(context)
        if (initialized) return manager
        manager.createNotificationChannel(NotificationChannel(ids.silentChannel, "가족 메시지 · 무음", NotificationManager.IMPORTANCE_LOW).apply {
            description = "새 메시지는 알림 하나로 조용히 표시합니다."
            setSound(null, null)
            enableVibration(false)
        })
        manager.createNotificationChannel(NotificationChannel(ids.audibleChannel, "가족 메시지 · 소리", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "앱에서 소리를 켠 경우 새 알림이 처음 나타날 때만 울립니다."
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(false)
        })
        // Keep the retired channel so an earlier OS-level block remains an effective veto.
        clearLegacyMessages(manager)
        initialized = true
        return manager
    }

    private fun channelAllowed(manager: NotificationManager, id: String): Boolean {
        val channel = manager.getNotificationChannel(id) ?: return true
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        return Build.VERSION.SDK_INT < 28 || channel.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } != true
    }

    private fun clearMessages(manager: NotificationManager) {
        manager.cancel(ids.tag, ids.notificationId)
        clearLegacyMessages(manager)
    }

    private fun clearLegacyMessages(manager: NotificationManager) {
        manager.activeNotifications.filter { it.notification.channelId == ids.legacyChannel }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
    private fun preferences(context: Context) = context.getSharedPreferences(ids.preferences, Context.MODE_PRIVATE)
}
