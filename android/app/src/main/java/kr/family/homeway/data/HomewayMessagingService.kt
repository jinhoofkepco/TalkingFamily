package kr.family.homeway.data

import android.app.PendingIntent
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kr.family.homeway.MainActivity
import kr.family.homeway.R

class HomewayMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Token registration is retried by MainActivity on every foreground entry.
        getSharedPreferences("homeway_push", MODE_PRIVATE).edit().putString("token", token).apply()
    }
    override fun onMessageReceived(message: RemoteMessage) {
        if (!AppRepository(this).configured) return
        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<OutboxWorker>().build())
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val location = message.data["kind"] == "location"
        val open = Intent(this, MainActivity::class.java)
            .setAction(if (location) "kr.family.homeway.OPEN_APP" else kr.family.homeway.overlay.FloatingStarService.ACTION_OPEN_CHAT)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val intent = PendingIntent.getActivity(this, if (location) 10 else 11, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "family_messages")
            .setSmallIcon(R.drawable.ic_homeway).setContentTitle(if(location) "새 위치가 도착했어요" else "우리 오는 길")
            .setContentText("가족의 새 소식이 도착했어요. 앱에서 확인해 주세요.")
            .setContentIntent(intent).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        NotificationManagerCompat.from(this).notify((message.data["eventId"] ?: message.messageId ?: "family").hashCode(), notification)
    }
}
