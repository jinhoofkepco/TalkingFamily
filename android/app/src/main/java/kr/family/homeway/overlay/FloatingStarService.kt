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
)

/**
 * User-started chat shortcut. No boot receiver, polling, wake lock, sensors or network.
 * A killed process is not restarted in the background; the next launcher opening can start it again.
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

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) hideBubble() else refreshBubble()
        }
    }
    private val permissionListener = AppOpsManager.OnOpChangedListener { operation, packageName ->
        if (operation == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == this.packageName) {
            handler.post {
                if (started && !Settings.canDrawOverlays(this)) {
                    end("다른 앱 위에 표시 권한이 꺼져 별 아이콘을 종료했어요.")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = OverlayPreferences(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "메신저 별 아이콘", NotificationManager.IMPORTANCE_LOW).apply {
                description = "다른 앱 위에 떠 있는 별로 메신저를 여는 동안 표시해요. 알림에서 끌 수 있어요."
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            end()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || stopping) {
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (!preferences.enabled || !Settings.canDrawOverlays(this)) {
            end("별 아이콘을 켜려면 다른 앱 위에 표시 권한을 허용해 주세요.")
            return START_NOT_STICKY
        }
        if (!started) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else startForeground(NOTIFICATION_ID, notification())
            } catch (_: RuntimeException) {
                end("별 아이콘을 시작하지 못했어요. 앱을 열어 다시 켜 주세요.")
                return START_NOT_STICKY
            }
            started = true
            runningInstance = this
            mutableRuntime.value = OverlayRuntime(running = true)
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            try {
                appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener)
                watchingPermission = true
            } catch (_: RuntimeException) {
                // Also recheck permission on every show, drag and activity visibility change.
            }
        }
        refreshBubble()
        return START_NOT_STICKY
    }

    private fun screenIsAvailable(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun refreshBubble() {
        if (!started || stopping) return
        if (!Settings.canDrawOverlays(this)) {
            end("다른 앱 위에 표시 권한이 꺼져 별 아이콘을 종료했어요.")
            return
        }
        if (messengerVisible || !screenIsAvailable()) hideBubble() else showBubble()
    }

    private fun showBubble() {
        if (bubble != null) return
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
            title = "우리 오는 길 메신저 별"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            x = OverlayGeometry.coordinate(safeBounds.left, safeBounds.right, size, if (preferences.edgeRight) 1f else 0f)
            y = OverlayGeometry.coordinate(safeBounds.top, safeBounds.bottom, size, preferences.verticalFraction)
        }
        val view = FloatingStarView(this)
        view.setOnClickListener { openMessenger() }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attached: View) {
                if (bubble === attached && started && !stopping) mutableRuntime.value = OverlayRuntime(true, true)
            }
            override fun onViewDetachedFromWindow(detached: View) {
                if (bubble === detached || bubble == null) mutableRuntime.value = mutableRuntime.value.copy(visible = false)
            }
        })
        installDrag(view, layout)
        bubble = view
        params = layout
        try {
            windowManager.addView(view, layout)
        } catch (_: RuntimeException) {
            bubble = null
            params = null
            end("별 아이콘을 표시하지 못했어요. 다른 앱 위에 표시 권한을 확인해 주세요.")
        }
    }

    private fun hideBubble() {
        val view = bubble
        bubble = null
        params = null
        if (view != null) {
            try { windowManager.removeViewImmediate(view) } catch (_: IllegalArgumentException) { }
        }
        mutableRuntime.value = mutableRuntime.value.copy(visible = false)
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
            end("다른 앱 위에 표시 권한이 꺼져 별 아이콘을 종료했어요.")
            return
        }
        try { windowManager.updateViewLayout(view, layout) } catch (_: RuntimeException) {
            end("별 아이콘을 표시하지 못했어요. 앱을 열어 다시 켜 주세요.")
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
        val stop = PendingIntent.getService(this, 4101, Intent(this, FloatingStarService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_homeway)
            .setContentTitle("메신저 별 아이콘 켜짐")
            .setContentText("별을 누르면 대화가 열려요. 닫으면 별로 돌아와요.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_dialog_email, "메신저 열기", open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "별 아이콘 끄기", stop)
            .build()
    }

    private fun end(error: String? = null) {
        if (stopping) return
        stopping = true
        preferences.enabled = false
        started = false
        hideBubble()
        mutableRuntime.value = OverlayRuntime(error = error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        started = false
        stopping = true
        hideBubble()
        if (runningInstance === this) runningInstance = null
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        if (watchingPermission) appOps.stopWatchingMode(permissionListener)
        handler.removeCallbacksAndMessages(null)
        mutableRuntime.value = mutableRuntime.value.copy(running = false, visible = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

        /** Call from a visible activity after explaining and obtaining the system overlay permission. */
        fun start(context: Context) {
            check(runningInstance?.stopping != true) { "별 아이콘을 종료하고 있어요. 잠시 후 다시 켜 주세요." }
            check(Settings.canDrawOverlays(context)) { "다른 앱 위에 표시 권한을 허용해 주세요." }
            val preferences = OverlayPreferences(context)
            preferences.enabled = true
            mutableRuntime.value = mutableRuntime.value.copy(error = null)
            try {
                ContextCompat.startForegroundService(context, Intent(context, FloatingStarService::class.java).setAction(ACTION_START))
            } catch (failure: RuntimeException) {
                preferences.enabled = false
                mutableRuntime.value = OverlayRuntime(error = "별 아이콘을 시작하지 못했어요. 앱을 열어 다시 켜 주세요.")
                throw failure
            }
        }

        fun stop(context: Context) {
            OverlayPreferences(context).enabled = false
            val active = runningInstance
            if (active != null) {
                if (Looper.myLooper() == Looper.getMainLooper()) active.end() else active.handler.post { active.end() }
            } else {
                context.stopService(Intent(context, FloatingStarService::class.java))
                mutableRuntime.value = OverlayRuntime()
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
