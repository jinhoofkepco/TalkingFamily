package kr.family.homeway.overlay

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.FileDescriptor
import java.io.PrintWriter
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.family.homeway.MainActivity
import kr.family.homeway.R

data class OverlayRuntime(
    val running: Boolean = false,
    val visible: Boolean = false,
    val error: String? = null,
    val starting: Boolean = false,
    val stopping: Boolean = false,
)

/**
 * User-started chat shortcut. No boot receiver, polling, wake lock, sensors or network.
 * Saved choice survives system sticky restarts; new starts happen only from a visible Activity.
 * The service keeps its notification while the messenger is open but removes its overlay window.
 */
class FloatingStarService : Service() {
    private lateinit var preferences: OverlayPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var appOps: AppOpsManager
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: FloatingStarView? = null
    private var params: WindowManager.LayoutParams? = null
    private var started = false
    private var stopping = false
    private var receiverRegistered = false
    private var watchingPermission = false
    private var safeBounds = Rect()
    private val windowRecovery = OverlayWindowRecovery()
    private var recoveryPending = false
    private var windowFailureCount = 0L
    private var recoveryAttemptCount = 0L
    private var lastWindowFailure = "none"
    private val recoverWindow = Runnable {
        recoveryPending = false
        recoveryAttemptCount += 1
        refreshBubble(resetRecovery = false)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) hideBubble() else refreshBubble()
        }
    }
    private val permissionListener = AppOpsManager.OnOpChangedListener { operation, packageName ->
        if (operation == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == this.packageName) {
            handler.post {
                if (started && !Settings.canDrawOverlays(this)) {
                    end("다른 앱 위에 표시 권한이 꺼져 별 아이콘을 종료했어요.", clearChoice = true)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = OverlayPreferences(this)
        runningInstance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "메신저 별 아이콘", NotificationManager.IMPORTANCE_LOW).apply {
                description = "다른 앱 위에 떠 있는 별로 메신저를 여는 동안 표시해요. 앱 설정에서 끌 수 있어요."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            end(clearChoice = true)
            return START_NOT_STICKY
        }
        if (stopping) return START_NOT_STICKY
        if (intent != null && intent.action != ACTION_START) {
            if (!started) stopSelf()
            return if (started) START_STICKY else START_NOT_STICKY
        }
        // Null intent is a system sticky restart, never a new opt-in.
        val decision = startDecision(this)
        if (decision != OverlayStartPolicy.Decision.START) {
            end(clearChoice = decision == OverlayStartPolicy.Decision.CLEAR_CHOICE_AND_STOP)
            return START_NOT_STICKY
        }
        if (!started) {
            mutableRuntime.value = OverlayRuntime(starting = true)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else startForeground(NOTIFICATION_ID, notification())
            } catch (_: RuntimeException) {
                end("별 아이콘 재개 대기 · 앱을 다시 열면 재시도해요.",
                    clearChoice = startDecision(this) == OverlayStartPolicy.Decision.CLEAR_CHOICE_AND_STOP)
                return START_NOT_STICKY
            }
            started = true
            runningInstance = this
            mutableRuntime.value = OverlayRuntime(running = true)
            try {
                ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
            } catch (_: RuntimeException) {
                end("별 아이콘 재개 대기 · 앱을 다시 열면 재시도해요.")
                return START_NOT_STICKY
            }
            try {
                appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener)
                watchingPermission = true
            } catch (_: RuntimeException) {
                // Also recheck permission on every show, drag and activity visibility change.
            }
        }
        refreshBubble()
        return if (stopping) START_NOT_STICKY else START_STICKY
    }

    private fun screenIsAvailable(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun refreshBubble(resetRecovery: Boolean = true) {
        if (!started || stopping) return
        val decision = startDecision(this)
        if (decision != OverlayStartPolicy.Decision.START) {
            end(clearChoice = decision == OverlayStartPolicy.Decision.CLEAR_CHOICE_AND_STOP)
            return
        }
        if (resetRecovery) windowRecovery.resetForVisibilityEvent()
        if (messengerVisible || !screenIsAvailable()) hideBubble() else showBubble()
    }

    private fun showBubble() {
        if (bubble != null || recoveryPending) return
        // A previous waiting error must not make MainActivity's collapse wait abort before
        // this new attach has a chance to complete.
        mutableRuntime.value = mutableRuntime.value.copy(error = null)
        try {
            attachBubble()
        } catch (_: RuntimeException) {
            handleWindowFailure("attach")
        }
    }

    private fun attachBubble() {
        val size = (48f * resources.displayMetrics.density).roundToInt()
        safeBounds = availableBounds()
        val layout = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            // Android's obscuring-opacity ceiling is 0.8: use only one small, translucent window.
            alpha = 0.72f
            title = "우리집 칭찬톡 메신저 별"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            x = OverlayGeometry.coordinate(safeBounds.left, safeBounds.right, size, if (preferences.edgeRight) 1f else 0f)
            y = OverlayGeometry.coordinate(safeBounds.top, safeBounds.bottom, size, preferences.verticalFraction)
        }
        val view = FloatingStarView(this)
        view.setOnClickListener { openMessenger() }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attached: View) {
                if (bubble === attached && started && !stopping) mutableRuntime.value = OverlayRuntime(running = true, visible = true)
            }
            override fun onViewDetachedFromWindow(detached: View) {
                // Intentional hides clear bubble before removing the view. An unexpected detach
                // must release its stale reference or showBubble would never create another one.
                if (bubble === detached) {
                    bubble = null
                    params = null
                    mutableRuntime.value = mutableRuntime.value.copy(visible = false)
                    handler.post {
                        if (started && !stopping && bubble == null && !recoveryPending) {
                            try { windowManager.removeViewImmediate(detached) } catch (_: RuntimeException) { }
                            handleWindowFailure("detach")
                        }
                    }
                }
            }
        })
        installDrag(view, layout)
        bubble = view
        params = layout
        windowManager.addView(view, layout)
    }

    private fun hideBubble() {
        handler.removeCallbacks(recoverWindow)
        recoveryPending = false
        val view = bubble
        bubble = null
        params = null
        if (view != null) {
            try { windowManager.removeViewImmediate(view) } catch (_: RuntimeException) { }
        }
        mutableRuntime.value = mutableRuntime.value.copy(visible = false)
    }

    private fun handleWindowFailure(reason: String) {
        windowFailureCount += 1
        lastWindowFailure = reason
        hideBubble()
        if (!started || stopping) return
        val decision = startDecision(this)
        if (decision != OverlayStartPolicy.Decision.START) {
            end(clearChoice = decision == OverlayStartPolicy.Decision.CLEAR_CHOICE_AND_STOP)
            return
        }
        if (messengerVisible || !screenIsAvailable()) return
        val delay = windowRecovery.nextDelayMillis()
        if (delay != null) {
            recoveryPending = true
            handler.postDelayed(recoverWindow, delay)
        } else {
            // stopSelf would cancel the sticky session and its unlock receiver. Keep the
            // existing foreground service so the next unlock/activity/configuration event
            // can recreate the window, with no continuing timer, wake lock or restart loop.
            mutableRuntime.value = OverlayRuntime(running = true,
                error = "별 아이콘 표시를 기다리고 있어요. 화면 전환 후 자동으로 다시 표시해요.")
        }
    }

    private fun installDrag(view: FloatingStarView, layout: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layout.x
                    initialY = layout.y
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        layout.x = OverlayGeometry.clamp(initialX + dx.roundToInt(), safeBounds.left, safeBounds.right, layout.width)
                        layout.y = OverlayGeometry.clamp(initialY + dy.roundToInt(), safeBounds.top, safeBounds.bottom, layout.height)
                        updateLayout(view, layout)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) snapAndSave(view, layout) else view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapAndSave(view, layout)
                    true
                }
                else -> false
            }
        }
    }

    private fun snapAndSave(view: View, layout: WindowManager.LayoutParams) {
        preferences.edgeRight = layout.x + layout.width / 2 >= safeBounds.centerX()
        preferences.verticalFraction = OverlayGeometry.fraction(layout.y, safeBounds.top, safeBounds.bottom, layout.height)
        layout.x = OverlayGeometry.coordinate(safeBounds.left, safeBounds.right, layout.width, if (preferences.edgeRight) 1f else 0f)
        updateLayout(view, layout)
    }

    private fun updateLayout(view: View, layout: WindowManager.LayoutParams) {
        if (bubble !== view || !started || stopping) return
        if (!Settings.canDrawOverlays(this)) {
            end("다른 앱 위에 표시 권한이 꺼져 별 아이콘을 종료했어요.", clearChoice = true)
            return
        }
        try { windowManager.updateViewLayout(view, layout) } catch (_: RuntimeException) {
            handleWindowFailure("update")
        }
    }

    private fun availableBounds(): Rect {
        val margin = (4f * resources.displayMetrics.density).roundToInt()
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Rect(insets.left, insets.top, metrics.bounds.width() - insets.right, metrics.bounds.height() - insets.bottom)
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            fun systemDimension(name: String): Int {
                val id = resources.getIdentifier(name, "dimen", "android")
                return if (id != 0) resources.getDimensionPixelSize(id) else 0
            }
            val nav = systemDimension("navigation_bar_height")
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            Rect(0, systemDimension("status_bar_height"), metrics.widthPixels - if (landscape) nav else 0,
                metrics.heightPixels - if (landscape) 0 else nav)
        }
        bounds.inset(margin, margin)
        return bounds
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Recreate the tiny window using new physical DPI, insets and persisted fractional position.
        hideBubble()
        refreshBubble()
    }

    private fun openMessenger() {
        if (!started || stopping || !screenIsAvailable()) return
        try {
            // Keep the icon visible until the activity resumes, avoiding a lost shortcut on failure.
            startActivity(chatIntent(this))
        } catch (_: RuntimeException) {
            mutableRuntime.value = mutableRuntime.value.copy(error = "메신저를 열지 못했어요. 알림에서 메신저 열기를 눌러 주세요.")
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 4100, chatIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_family_notification)
            .setContentTitle("메신저 별 아이콘 켜짐")
            .setContentText("별을 누르면 대화가 열려요. 닫으면 별로 돌아와요.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_dialog_email, "메신저 열기", open)
            .build()
    }

    private fun end(error: String? = null, clearChoice: Boolean = false) {
        if (clearChoice) preferences.enabled = false
        if (stopping) return
        stopping = true
        started = false
        hideBubble()
        mutableRuntime.value = OverlayRuntime(error = error, stopping = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        started = false
        stopping = true
        hideBubble()
        if (runningInstance === this) runningInstance = null
        if (receiverRegistered) try { unregisterReceiver(screenReceiver) } catch (_: RuntimeException) { }
        if (watchingPermission) try { appOps.stopWatchingMode(permissionListener) } catch (_: RuntimeException) { }
        handler.removeCallbacksAndMessages(null)
        // Unexpected destruction preserves the saved choice for OS/visible-Activity resume.
        mutableRuntime.value = mutableRuntime.value.copy(running = false, visible = false, starting = false, stopping = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        dumpState(writer)
    }

    private fun dumpState(writer: PrintWriter) {
        val permission = Settings.canDrawOverlays(this)
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val account = accountAvailable(this)
        val state = when {
            stopping -> "stopping"
            !started -> "not_started"
            !preferences.enabled -> "disabled"
            !permission -> "permission_unavailable"
            !account -> "account_unavailable"
            !interactive || locked -> "screen_unavailable"
            messengerVisible -> "messenger_open"
            recoveryPending -> "window_retry_pending"
            bubble?.isAttachedToWindow == true -> "window_attached"
            else -> "waiting_for_window"
        }
        writer.println("overlayServicePresent=true overlayState=$state savedEnabled=${preferences.enabled} hasSavedChoice=${preferences.hasSavedChoice}")
        writer.println("overlayPermission=$permission accountEligible=$account started=$started stopping=$stopping")
        writer.println("screenInteractive=$interactive keyguardLocked=$locked messengerVisible=$messengerVisible")
        writer.println("windowAttached=${bubble?.isAttachedToWindow == true} reportedVisible=${mutableRuntime.value.visible}")
        writer.println("windowFailures=$windowFailureCount recoveryAttempts=$recoveryAttemptCount burstAttempts=${windowRecovery.attemptsUsed} recoveryPending=$recoveryPending lastWindowFailure=$lastWindowFailure")
    }

    companion object {
        const val ACTION_OPEN_CHAT = "kr.family.homeway.OPEN_CHAT"
        private const val ACTION_START = "kr.family.homeway.START_FLOATING_STAR"
        private const val ACTION_STOP = "kr.family.homeway.STOP_FLOATING_STAR"
        private const val CHANNEL_ID = "homeway_floating_star"
        private const val NOTIFICATION_ID = 4100
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var runningInstance: FloatingStarService? = null
        @Volatile private var messengerVisible = false
        private val mutableRuntime = MutableStateFlow(OverlayRuntime())
        val runtime: StateFlow<OverlayRuntime> = mutableRuntime.asStateFlow()
        val isRunning: Boolean get() = mutableRuntime.value.running
        val isBubbleVisible: Boolean get() = mutableRuntime.value.visible

        fun wantsOverlay(context: Context): Boolean = OverlayPreferences(context).enabled
        fun hasSavedChoice(context: Context): Boolean = OverlayPreferences(context).hasSavedChoice

        /** MainActivity may expose this through its normal Android dump even when the service is absent. */
        fun dumpDiagnostics(context: Context, writer: PrintWriter) {
            val active = runningInstance
            if (active != null) {
                active.dumpState(writer)
                return
            }
            val saved = OverlayPreferences(context)
            val runtime = mutableRuntime.value
            writer.println("overlayServicePresent=false savedEnabled=${saved.enabled} hasSavedChoice=${saved.hasSavedChoice}")
            writer.println("overlayPermission=${Settings.canDrawOverlays(context)} accountEligible=${accountAvailable(context)}")
            writer.println("screenInteractive=${context.getSystemService(PowerManager::class.java).isInteractive} keyguardLocked=${context.getSystemService(KeyguardManager::class.java).isKeyguardLocked} messengerVisible=$messengerVisible")
            writer.println("runtimeRunning=${runtime.running} runtimeVisible=${runtime.visible} runtimeStarting=${runtime.starting} runtimeStopping=${runtime.stopping} runtimeErrorPresent=${runtime.error != null}")
        }

        /** Call from a visible activity after explaining and obtaining the system overlay permission. */
        fun start(context: Context): Boolean {
            OverlayPreferences(context).enabled = true
            return resumeSavedOverlay(context)
        }

        /** Restore only a prior enabled choice, and only when called from a visible Activity. */
        fun resumeSavedOverlay(context: Context): Boolean {
            val decision = startDecision(context)
            if (decision != OverlayStartPolicy.Decision.START) {
                if (decision == OverlayStartPolicy.Decision.CLEAR_CHOICE_AND_STOP) stop(context)
                return false
            }
            if (runningInstance?.stopping == true || mutableRuntime.value.stopping) return false
            if (mutableRuntime.value.running || mutableRuntime.value.starting) return true
            mutableRuntime.value = OverlayRuntime(starting = true)
            try {
                ContextCompat.startForegroundService(context, Intent(context, FloatingStarService::class.java).setAction(ACTION_START))
                return true
            } catch (_: RuntimeException) {
                // Android 15 may reject a new FGS if Activity visibility changed in flight.
                // Keep the saved choice unless permission/account eligibility actually changed.
                if (startDecision(context) == OverlayStartPolicy.Decision.CLEAR_CHOICE_AND_STOP) {
                    OverlayPreferences(context).enabled = false
                }
                mutableRuntime.value = OverlayRuntime(error = "별 아이콘 재개 대기 · 앱을 다시 열면 재시도해요.")
                return false
            }
        }

        private fun startDecision(context: Context): OverlayStartPolicy.Decision {
            val enabled = OverlayPreferences(context).enabled
            if (!enabled) return OverlayStartPolicy.Decision.STOP
            return OverlayStartPolicy.decide(enabled, Settings.canDrawOverlays(context), accountAvailable(context))
        }

        private fun accountAvailable(context: Context): Boolean = runCatching {
            // A visual shortcut does not need to decrypt Telegram credentials. Keystore can
            // be temporarily unavailable; treating that as disconnect used to erase saved ON.
            // Only inspect durable configuration and encrypted-record presence, never contents.
            val settings = context.applicationContext.getSharedPreferences("homeway_settings", Context.MODE_PRIVATE)
            val credentials = context.applicationContext.getSharedPreferences("homeway_credentials", Context.MODE_PRIVATE)
            OverlayStartPolicy.accountAvailable(settings.getBoolean("demoMode", false),
                settings.getString("transport", "") == "telegram_direct", settings.getLong("peerBotId", 0) > 0 ||
                    (kr.family.homeway.data.LocalStore.get(context).familyChat.activeRoom()?.members
                        ?.any { it.botId == settings.getLong("ownBotId", 0) } == true),
                !credentials.getString("token", null).isNullOrBlank(), !credentials.getString("iv", null).isNullOrBlank())
        }.getOrDefault(false)

        fun stop(context: Context) {
            OverlayPreferences(context).enabled = false
            stopSession(context, clearChoice = true)
        }

        /** A temporary failure must not turn the user's saved enabled choice into an off. */
        fun pause(context: Context, error: String? = null) {
            stopSession(context, clearChoice = false, error = error)
        }

        private fun stopSession(context: Context, clearChoice: Boolean, error: String? = null) {
            val active = runningInstance
            if (active != null) {
                if (Looper.myLooper() == Looper.getMainLooper()) active.end(error, clearChoice)
                else active.handler.post { active.end(error, clearChoice) }
            } else {
                context.stopService(Intent(context, FloatingStarService::class.java))
                mutableRuntime.value = OverlayRuntime(error = error)
            }
        }

        /** Changes only an existing service; background lifecycle callbacks never start a new one. */
        fun setMessengerVisible(visible: Boolean) {
            messengerVisible = visible
            if (Looper.myLooper() == Looper.getMainLooper()) runningInstance?.refreshBubble()
            else mainHandler.post { runningInstance?.refreshBubble() }
        }

        fun chatIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CHAT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
