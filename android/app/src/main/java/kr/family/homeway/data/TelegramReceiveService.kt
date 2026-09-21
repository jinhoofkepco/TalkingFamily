package kr.family.homeway.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kr.family.homeway.MainActivity
import kr.family.homeway.R
import kr.family.homeway.overlay.FloatingStarService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** User-visible remote messaging session. No location access, boot receiver, or silent restart. */
class TelegramReceiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var polling: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop(this); return START_NOT_STICKY }
        if (!AppRepository(this).configured) { stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "텔레그램 가족 소식 수신", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        val open = PendingIntent.getActivity(this, 42, Intent(this, MainActivity::class.java)
            .setAction(FloatingStarService.ACTION_OPEN_CHAT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = PendingIntent.getService(this, 43, Intent(this, TelegramReceiveService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_homeway)
            .setContentTitle("가족 소식 수신 중").setContentText("텔레그램으로 대화와 칭찬을 주고받고 있어요.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "수신 중지", stopIntent).build()
        ServiceCompat.startForeground(this, NOTIFICATION, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
        state.value = ReceiveState(true)
        if (polling?.isActive != true) polling = scope.launch {
            val repo = AppRepository(this@TelegramReceiveService)
            while (isActive && repo.configured) {
                try {
                    repo.synchronize(10)
                    state.value = ReceiveState(true)
                    delay(1000)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    state.value = ReceiveState(true, "인터넷과 봇 설정을 확인해 주세요. 연결을 다시 시도하고 있어요.")
                    delay(15_000)
                }
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel(); state.value = ReceiveState(false)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "family_telegram_receiving"
        private const val NOTIFICATION = 4040
        private const val ACTION_STOP = "kr.family.homeway.STOP_TELEGRAM_RECEIVING"
        data class ReceiveState(val running: Boolean, val error: String? = null)
        private val state = MutableStateFlow(ReceiveState(false))
        val runtime = state.asStateFlow()
        fun wantsReceiving(context: Context): Boolean = context.getSharedPreferences("homeway_receiver", MODE_PRIVATE)
            .getBoolean("enabled", true)
        fun start(context: Context) {
            context.getSharedPreferences("homeway_receiver", MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            ContextCompat.startForegroundService(context, Intent(context, TelegramReceiveService::class.java))
        }
        fun stop(context: Context) {
            context.getSharedPreferences("homeway_receiver", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            context.stopService(Intent(context, TelegramReceiveService::class.java))
            state.value = ReceiveState(false)
        }
    }
}
