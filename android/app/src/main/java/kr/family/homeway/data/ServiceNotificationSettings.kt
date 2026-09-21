package kr.family.homeway.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Opens the user's channel controls without changing notification permissions or service state. */
object ServiceNotificationSettings {
    enum class Kind(val channelId: String, val label: String) {
        LOCATION("homeway_location_sharing", "자녀 위치 공유"),
        RECEIVING("family_telegram_receiving", "텔레그램 가족 소식 수신"),
        STAR("homeway_floating_star", "메신저 별 아이콘"),
    }

    /** Returns false only if this device has neither supported settings screen available. */
    fun open(context: Context, kind: Kind): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Creating only missing channels leaves the user's existing choices untouched.
        if (manager.getNotificationChannel(kind.channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(kind.channelId, kind.label, NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
        val channelSettings = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, kind.channelId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryOpen(context, channelSettings)) return true
        val appSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryOpen(context, appSettings)
    }

    private fun tryOpen(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
