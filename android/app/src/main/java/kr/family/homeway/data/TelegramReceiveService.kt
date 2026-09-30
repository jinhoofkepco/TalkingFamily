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
import android.os.SystemClock
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileDescriptor
import java.io.PrintWriter

/** User-visible remote messaging session, recoverable after system process death while opted in. */
class TelegramReceiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var polling: Job? = null
    @Volatile private var backoffDeadline = 0L
    @Volatile private var nextPollDeadline = 0L
    @Volatile private var lastExchangeAttemptAt: Long? = null
    @Volatile private var lastSuccessfulCycleAt: Long? = null
    @Volatile private var failedCycles = 0L
    @Volatile private var consecutiveFailures = 0L
    @Volatile private var waitReason = "not_started"
    @Volatile private var catchUpActive = false
    @Volatile private var catchUpPolls = 0
    @Volatile private var catchUpCooldownMillis = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop(this); return START_NOT_STICKY }
        // A system sticky restart has a null intent. Never reverse the user's saved OFF choice.
        if (!wantsReceiving(this)) { stopSelf(); return START_NOT_STICKY }
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
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_family_notification)
            .setContentTitle("가족 소식 수신 중").setContentText("텔레그램으로 대화와 칭찬을 주고받고 있어요.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "수신 중지", stopIntent).build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
        } catch (_: RuntimeException) {
            AppDiagnostics.record(this, "telegram.receiver", "가족 소식 수신 재개 대기 · 앱을 열어 다시 시작해 주세요.")
            stopSelf()
            return START_NOT_STICKY
        }
        state.value = ReceiveState(true)
        if (polling?.isActive != true) polling = scope.launch {
            try {
                val repo = AppRepository(this@TelegramReceiveService)
                // Account/database checks and any upgrade work stay off the service's main thread.
                while (isActive && wantsReceiving(this@TelegramReceiveService) && repo.configured) {
                    waitReason = "checking_schedule"
                    // Capture before inspecting the queue: a send during the exchange or subsequent
                    // pause must remain visible, even if the HTTP poll already returned.
                    val revision = TelegramPollWakeup.revision
                    val retryDelay = repo.synchronizationRetryDelayMillis()
                    backoffDeadline = SystemClock.elapsedRealtime() + retryDelay
                    if (retryDelay > 0) {
                        waitReason = "telegram_backoff"
                        awaitWake(revision, retryDelay)
                        continue
                    }
                    val startedAt = SystemClock.elapsedRealtime()
                    var waitMillis: Long
                    try {
                        lastExchangeAttemptAt = startedAt
                        waitReason = "exchange_in_progress"
                        repo.synchronizeScheduled(TelegramChatReceiveSchedule.FOREGROUND_POLL_SECONDS)
                        state.value = ReceiveState(true)
                        // Long polling already waited. Only fast returns need pacing, including
                        // empty replies and a repository exchange skipped by a racing backoff.
                        val receiveDelay = repo.receiveDelayMillis()
                        val catchUp = TelegramChatReceiveCadence.catchUpState()
                        catchUpActive = catchUp.active
                        catchUpPolls = catchUp.polls
                        catchUpCooldownMillis = catchUp.cooldownRemainingMillis
                        nextPollDeadline = SystemClock.elapsedRealtime() + receiveDelay
                        val outgoingRetry = minOf(repo.outgoingRecheckDelayMillis() ?: Long.MAX_VALUE,
                            if (repo.hasPending()) OUTBOX_RECHECK_MILLIS else Long.MAX_VALUE)
                        waitMillis = if (receiveDelay > 0) minOf(receiveDelay, outgoingRetry)
                            else (TelegramChatReceiveSchedule.MIN_CYCLE_MILLIS -
                                (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0)
                        waitReason = when {
                            catchUp.active && receiveDelay <= 0 -> "catch_up_pacing"
                            receiveDelay <= 0 -> "foreground_pacing"
                            outgoingRetry < receiveDelay -> "outbox_recheck"
                            catchUp.active -> "catch_up_deadline"
                            else -> "receive_deadline"
                        }
                        // A completed cycle may have flushed outgoing work only, or skipped for a
                        // racing backoff. This deliberately does not claim a successful Telegram poll.
                        lastSuccessfulCycleAt = SystemClock.elapsedRealtime()
                        consecutiveFailures = 0
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        failedCycles++
                        consecutiveFailures++
                        AppDiagnostics.record(this@TelegramReceiveService, "telegram.receiver", "인터넷과 봇 설정을 확인해 주세요. 연결을 다시 시도하고 있어요.")
                        state.value = ReceiveState(true, "인터넷과 봇 설정을 확인해 주세요. 연결을 다시 시도하고 있어요.")
                        val errorBackoff = repo.synchronizationRetryDelayMillis()
                        backoffDeadline = SystemClock.elapsedRealtime() + errorBackoff
                        waitMillis = errorBackoff.takeIf { it > 0 } ?: TelegramChatReceiveSchedule.ERROR_RETRY_MILLIS
                        waitReason = if (errorBackoff > 0) "telegram_backoff" else "cycle_retry"
                    }
                    awaitWake(revision, waitMillis)
                }
                stopSelf()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A database/account initialization failure must not crash the location service too.
                AppDiagnostics.record(this@TelegramReceiveService, "telegram.receiver", "수신 기록을 준비하지 못했어요. 앱을 열어 다시 시도해 주세요.")
                stopSelf()
            } finally {
                waitReason = "stopped"
            }
        }
        return START_STICKY
    }
    private suspend fun awaitWake(revision: Long, waitMillis: Long) {
        if (waitMillis <= 0) return
        withTimeoutOrNull(waitMillis) { TelegramPollWakeup.changes.first { it != revision } }
    }
    override fun onDestroy() {
        waitReason = "stopped"
        scope.cancel()
        // Release a blocking idle poll after cancellation, so the next authorized worker or
        // session does not wait for this stopped service's remaining HTTP timeout.
        TelegramPollWakeup.signal()
        state.value = ReceiveState(false)
        super.onDestroy()
    }
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        val now = SystemClock.elapsedRealtime()
        fun age(at: Long?): String = at?.let { (now - it).coerceAtLeast(0).toString() } ?: "none"
        // Diagnostics must never wait on the repository lock from the service's main thread.
        val backoff = (backoffDeadline - now).coerceAtLeast(0)
        val nextPoll = (nextPollDeadline - now).coerceAtLeast(0)
        writer.println("receiverRunning=${state.value.running} pollingJobActive=${polling?.isActive == true}")
        writer.println("lastExchangeAttemptAgeMillis=${age(lastExchangeAttemptAt)} lastSuccessfulCycleAgeMillis=${age(lastSuccessfulCycleAt)}")
        writer.println("waitReason=$waitReason backoffRemainingMillis=$backoff nextPollDelayMillis=$nextPoll")
        writer.println("failedCycles=$failedCycles consecutiveFailures=$consecutiveFailures")
        writer.println("catchUpActive=$catchUpActive catchUpPolls=$catchUpPolls catchUpCooldownMillis=$catchUpCooldownMillis")
        writer.println("cycleSuccessDoesNotProvePollOrDelivery=true")
        FamilySyncDiagnostics.dump(writer)
    }
    companion object {
        private const val CHANNEL = "family_telegram_receiving"
        private const val NOTIFICATION = 4040
        private const val ACTION_STOP = "kr.family.homeway.STOP_TELEGRAM_RECEIVING"
        private const val OUTBOX_RECHECK_MILLIS = 15_000L
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
