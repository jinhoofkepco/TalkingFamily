package kr.family.homeway.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.family.homeway.data.AppRepository
import org.json.JSONObject

/** Visible, child-initiated session. No boot receiver, sticky restart or wake lock. */
class TrackingService : Service(), SensorEventListener {
    private lateinit var repository: AppRepository
    private lateinit var sensors: SensorManager
    private lateinit var policy: MovementSamplingPolicy
    private val detector = VerticalMovementDetector()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stopped = CompletableDeferred<Unit>()
    private val outgoing = Channel<Pair<String, JSONObject>>(Channel.UNLIMITED)
    private var started = false
    private var stopping = false
    private var pressureReliable = true
    private var ticker: Job? = null
    private var sender: Job? = null
    private var locationJob: Job? = null
    private var significantMotion: Sensor? = null
    private var lastMotionMillis = Long.MIN_VALUE
    private var accelerationCount = 0
    private var accelerationWindowStart = 0L
    private var lastHeartbeatMillis = 0L
    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            handler.post {
                if (!started) return@post
                noteMovement(event?.timestamp?.div(1_000_000L) ?: SystemClock.elapsedRealtime())
                handler.postDelayed({ if (started) armSignificantMotion() }, 1_000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(applicationContext)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "자녀 위치 공유", NotificationManager.IMPORTANCE_LOW).apply {
                description = "공유 중에는 계속 표시하며 알림에서 언제든 종료할 수 있습니다."
            },
        )
        sender = scope.launch {
            for ((kind, payload) in outgoing) {
                try {
                    repository.enqueueEventOnly(kind, payload, UUID.randomUUID().toString())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    repository.noteTrackingStatus("통신 연결을 확인해 주세요. 저장된 기록은 연결 후 다시 보냅니다.")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            return START_NOT_STICKY
        }
        if (started || stopping) return START_NOT_STICKY
        if (intent?.action != ACTION_START || !repository.configured || !repository.isChild ||
            repository.demoMode || !repository.sharingEnabled || !hasRequiredPermissions(this)) {
            repository.sharingEnabled = false
            repository.noteTrackingStatus("자녀 화면에서 위치 공유 안내와 권한을 확인하고 다시 시작해 주세요.")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(NOTIFICATION_ID, notification())
        } catch (_: SecurityException) {
            repository.sharingEnabled = false
            repository.noteTrackingStatus("위치 공유를 시작하지 못했어요. 위치 권한을 확인해 주세요.")
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        runningInstance = this
        val now = SystemClock.elapsedRealtime()
        policy = MovementSamplingPolicy(now)
        lastHeartbeatMillis = now
        repository.noteTrackingStatus("자동 공유 중 · 움직임이 있는 5분 구간마다 위치 확인 · 높이 변화는 추정")
        registerSensors()
        outgoing.trySend("sharing_status" to JSONObject().put("enabled", true))
        enqueueHeartbeat()
        ticker = scope.launch {
            while (started) {
                delay(30_000L)
                if (!repository.sharingEnabled || !repository.isChild || !hasRequiredPermissions(this@TrackingService)) {
                    stopSharing()
                    break
                }
                checkDeadlines()
            }
        }
        return START_NOT_STICKY
    }

    private fun registerSensors() {
        significantMotion = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        armSignificantMotion()
        val activityAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (activityAllowed) register(Sensor.TYPE_STEP_DETECTOR, 200_000)
        // Low-rate acceleration also notices an elevator starting when there are no steps.
        register(Sensor.TYPE_ACCELEROMETER, 200_000)
        if (!register(Sensor.TYPE_PRESSURE, 1_000_000)) {
            repository.noteTrackingStatus("기압 센서를 사용할 수 없어 높이 변화는 표시하지 않습니다.")
        }
    }

    private fun register(type: Int, samplingMicros: Int): Boolean {
        val sensor = sensors.getDefaultSensor(type, true) ?: sensors.getDefaultSensor(type) ?: return false
        return try {
            sensors.registerListener(this, sensor, samplingMicros, 5_000_000, handler)
        } catch (_: SecurityException) { false }
    }

    private fun armSignificantMotion() {
        significantMotion?.let { sensor ->
            try { sensors.requestTriggerSensor(triggerListener, sensor) } catch (_: SecurityException) { }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started || !repository.sharingEnabled || event.values.isEmpty()) return
        val at = event.timestamp / 1_000_000L
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> noteMovement(at)
            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size < 3) return
                val magnitude = sqrt(event.values.take(3).sumOf { it.toDouble() * it })
                if (abs(magnitude - SensorManager.GRAVITY_EARTH) > 0.65) {
                    if (at - accelerationWindowStart > 2_000L) {
                        accelerationWindowStart = at
                        accelerationCount = 0
                    }
                    accelerationCount++
                    if (accelerationCount >= 3) noteMovement(at)
                }
            }
            Sensor.TYPE_PRESSURE -> {
                if (!pressureReliable) return
                val recentMotion = lastMotionMillis != Long.MIN_VALUE && at - lastMotionMillis in 0..30_000L
                detector.addPressure(event.values[0].toDouble(), at, recentMotion).forEach { movement ->
                    policy.noteMovement()
                    outgoing.trySend("vertical" to JSONObject()
                        .put("phase", movement.phase)
                        .put("relativeMeters", movement.relativeMeters)
                        .put("measuredAt", sensorInstant(movement.measuredAtMillis))
                        .put("detectedAt", Instant.now().toString())
                        .put("confidence", "estimated"))
                }
            }
        }
    }

    private fun noteMovement(at: Long) {
        lastMotionMillis = maxOf(lastMotionMillis, at)
        policy.noteMovement()
        checkDeadlines()
    }

    private fun checkDeadlines() {
        if (!started || !repository.sharingEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeartbeatMillis >= INTERVAL_MILLIS) {
            lastHeartbeatMillis = now
            enqueueHeartbeat()
        }
        if (locationJob?.isActive != true && policy.consumeDue(now)) {
            locationJob = scope.launch {
                try {
                    val location = CurrentLocationProvider.capture(this@TrackingService)
                    if (!started || !repository.sharingEnabled) return@launch
                    outgoing.send("location" to JSONObject()
                        .put("latitude", location.latitude)
                        .put("longitude", location.longitude)
                        .put("accuracy", location.accuracy.toDouble())
                        .put("capturedAt", Instant.ofEpochMilli(location.time).toString())
                        .put("source", "automatic"))
                    repository.noteTrackingStatus("현재 위치 기록 · ${Instant.ofEpochMilli(location.time)}")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    policy.retryNextInterval()
                    repository.noteTrackingStatus("현재 위치를 찾지 못했어요. 다음 5분 구간에 다시 확인합니다.")
                }
            }
        }
    }

    private fun enqueueHeartbeat() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val payload = JSONObject().put("recordedAt", Instant.now().toString())
        if (level >= 0 && scale > 0) payload.put("batteryPercent", (level * 100 / scale).coerceIn(0, 100))
        outgoing.trySend("heartbeat" to payload)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_PRESSURE) {
            pressureReliable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
            if (!pressureReliable) detector.reset()
        }
    }

    private fun sensorInstant(elapsedMillis: Long): String = Instant.ofEpochMilli(
        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsedMillis),
    ).toString()

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("아빠에게 위치 공유 중")
            .setContentText("움직임이 있는 5분 구간마다 위치를 확인해요. 높이 변화는 추정해요.")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "공유 종료", stop)
        // Notification entry opens the app; only an explicit launcher entry starts collapsed.
        val launch = Intent(this, kr.family.homeway.MainActivity::class.java)
            .setAction("kr.family.homeway.OPEN_APP")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        builder.setContentIntent(PendingIntent.getActivity(this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return builder.build()
    }

    private fun stopSharing() {
        if (stopping) return
        stopping = true
        repository.sharingEnabled = false
        started = false
        ticker?.cancel()
        locationJob?.cancel()
        unregisterSensors()
        repository.noteTrackingStatus("자동 위치 공유 종료")
        // Stop collection immediately, then let the repository durably save queued metadata.
        scope.launch {
            if (repository.configured && repository.isChild && !repository.demoMode) {
                outgoing.send("sharing_status" to JSONObject().put("enabled", false))
            }
            outgoing.close()
            sender?.join()
            stopForeground(STOP_FOREGROUND_REMOVE)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            stopSelf()
        }
    }

    private fun unregisterSensors() {
        sensors.unregisterListener(this)
        significantMotion?.let { sensors.cancelTriggerSensor(triggerListener, it) }
        handler.removeCallbacksAndMessages(null)
        detector.reset()
    }

    override fun onDestroy() {
        started = false
        if (runningInstance === this) runningInstance = null
        repository.sharingEnabled = false
        unregisterSensors()
        outgoing.close()
        scope.cancel()
        stopped.complete(Unit)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile private var runningInstance: TrackingService? = null
        private const val CHANNEL_ID = "homeway_location_sharing"
        private const val NOTIFICATION_ID = 4001
        private const val ACTION_START = "kr.family.homeway.START_LOCATION_SHARING"
        private const val ACTION_STOP = "kr.family.homeway.STOP_LOCATION_SHARING"
        private const val INTERVAL_MILLIS = 5 * 60_000L

        /** Call only from a visible activity after the child's explicit sharing consent. */
        fun start(context: Context) {
            check(runningInstance?.stopping != true) { "위치 공유를 종료하고 있어요. 잠시 후 다시 켜 주세요." }
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            // Do not create a service for demo/reset flows, and stop collection before returning.
            val active = runningInstance
            if (active != null) {
                if (Looper.myLooper() == Looper.getMainLooper()) active.stopSharing()
                else active.handler.post { active.stopSharing() }
            } else AppRepository(context).sharingEnabled = false
        }

        /** Wait before clearing account settings so queued sensor writes cannot cross accounts. */
        suspend fun stopAndAwait(context: Context) = withContext(Dispatchers.Main.immediate) {
            val active = runningInstance
            if (active != null) {
                active.stopSharing()
                active.stopped.await()
            } else AppRepository(context).sharingEnabled = false
        }

        fun hasRequiredPermissions(context: Context): Boolean {
            val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val notification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            return location && notification
        }
    }
}
