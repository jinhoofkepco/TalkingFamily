package kr.family.homeway.data

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kr.family.homeway.MainActivity
import kr.family.homeway.R
import kr.family.homeway.overlay.FloatingStarService

internal object FamilyNotifications {
    fun received(context: Context, event: FamilyEvent) {
        if (event.kind != "chat") return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).setAction(FloatingStarService.ACTION_OPEN_CHAT)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, 41, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, "family_messages")
            .setSmallIcon(R.drawable.ic_family_notification).setContentTitle(context.getString(R.string.app_name))
            .setContentText("가족의 새 메시지가 도착했어요. 앱에서 확인해 주세요.")
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        NotificationManagerCompat.from(context).notify(event.id.hashCode(), notification)
    }
}
