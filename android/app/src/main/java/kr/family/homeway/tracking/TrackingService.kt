package kr.family.homeway.tracking

import android.Manifest
import android.annotation.SuppressLint
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
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.FileDescriptor
import java.io.PrintWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kr.family.homeway.data.AppRepository
import org.json.JSONObject

/** Child-consented sharing; new starts require a visible Activity, OS sticky restarts retain opt-in. */
class TrackingService : Service(), SensorEventListener {
    private lateinit var repository: AppRepository
    private lateinit var sensors: SensorManager
    private lateinit var policy: AutomaticLocationPolicy
    private lateinit var activityMotionMonitor: ActivityMotionMonitor
    private val stationaryFilter = StationaryLocationFilter()
    private val cadence = AdaptiveTrackingCadence()
    private var lastFilteredDisplay: FilteredLocation? = null
    private var lastFilteredAtElapsedMillis: Long? = null
    private var lastRawAccuracyMeters: Double? = null
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
    private var outboxRelay: Job? = null
    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var subscriptionActive = false
    private var subscriptionStarting = false
    private var lastSubscriptionAttemptAt: Long? = null
    private var subscriptionIntervalMillis: Long? = null
    private var locationCallback: LocationCallback? = null
    private var significantMotion: Sensor? = null
    private var stepDetector: Sensor? = null
    private var lastMotionMillis = Long.MIN_VALUE
    private var accelerationCount = 0
    private var accelerationWindowStart = 0L
    private var lastHeartbeatMillis = 0L
    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            handler.post {
                if (!started) return@post
                val now = SystemClock.elapsedRealtime()
                val at = event?.timestamp?.div(1_000_000L) ?: now
                cadence.onSignificantMotion(at, now)
                notePhysicalMovement(at, now)
                noteMovement(at)
                handler.postDelayed({ if (started) armSignificantMotion() }, 1_000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(applicationContext)
        activityMotionMonitor = ActivityMotionMonitor(this) { state, at, persistentUntilExit ->
            stationaryFilter.updateMotion(state, at, persistentUntilExit)
            val now = SystemClock.elapsedRealtime()
            cadence.onActivity(state, at, persistentUntilExit, now)
            if (started) {
                refreshCadence(now)
                requestFreshFixIfDue(now)
            }
        }
        runningInstance = this
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "자녀 위치 공유", NotificationManager.IMPORTANCE_LOW).apply {
                description = "공유 중에는 계속 표시하며 알림에서 언제든 종료할 수 있습니다."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        sender = scope.launch {
            val writer = TrackingEventWriter(repository::enqueueEventOnly, onFailure = {
                repository.noteTrackingStatus("기록 저장을 완료하지 못했어요. 저장을 다시 시도하고 있어요.")
            })
            for ((kind, payload) in outgoing) {
                writer.write(kind, payload)
                if (kind == "location") {
                    policy.committed()
                    if (started && !stopping) {
                        val savedTime = Instant.parse(payload.getString("capturedAt")).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        repository.noteTrackingStatus("자동 공유 중 · 마지막 위치 저장 $savedTime")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            return START_NOT_STICKY
        }
        if (stopping) return START_NOT_STICKY
        // A null intent is delivered only by Android when restarting an existing sticky service.
        if (intent != null && intent.action != ACTION_START) {
            if (!started) stopSelf()
            return if (started) START_STICKY else START_NOT_STICKY
        }
        if (startDecision(repository, this) != TrackingStartPolicy.Decision.START) {
            repository.noteTrackingStatus("자녀 화면에서 위치 공유 안내와 권한을 확인하고 다시 시작해 주세요.")
            stopSharing()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        mutableRuntime.value = TrackingRuntime(starting = true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(NOTIFICATION_ID, notification())
        } catch (_: RuntimeException) {
            // Background/while-in-use rejection is not withdrawal of a still-valid opt-in.
            if (startDecision(repository, this) != TrackingStartPolicy.Decision.START) repository.sharingEnabled = false
            val message = "자동 위치 공유를 시작하지 못했어요. 앱을 열고 위치 권한을 확인해 주세요."
            repository.noteTrackingStatus(message)
            mutableRuntime.value = TrackingRuntime(error = message)
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        runningInstance = this
        mutableRuntime.value = TrackingRuntime(running = true)
        val now = SystemClock.elapsedRealtime()
        policy = AutomaticLocationPolicy(now)
        lastHeartbeatMillis = now
        repository.noteTrackingStatus("자동 공유 중 · 이동 중 약 20초, 정지 중 약 5분 · 첫 위치를 기다리고 있어요")
        registerSensors()
        activityMotionMonitor.refresh()
        startLocationUpdates()
        outboxRelay = scope.launch { TrackingOutboxRelay.run(repository) }
        outgoing.trySend("sharing_status" to JSONObject().put("enabled", true))
        enqueueHeartbeat()
        ticker = scope.launch {
            while (started) {
                delay(5_000L)
                if (startDecision(repository, this@TrackingService) != TrackingStartPolicy.Decision.START) {
                    stopSharing()
                    break
                }
                checkDeadlines()
            }
        }
        return START_STICKY
    }

    private fun registerSensors() {
        significantMotion = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        armSignificantMotion()
        refreshStepDetector()
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
            Sensor.TYPE_STEP_DETECTOR -> {
                detector.noteStep(at)
                val now = SystemClock.elapsedRealtime()
                cadence.onStep(at, now)
                notePhysicalMovement(at, now)
                noteMovement(at)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size < 3) return
                val magnitude = sqrt(event.values.take(3).sumOf { it.toDouble() * it })
                if (abs(magnitude - SensorManager.GRAVITY_EARTH) > 0.65) {
                    if (at - accelerationWindowStart > 2_000L) {
                        accelerationWindowStart = at
                        accelerationCount = 0
                    }
                    accelerationCount++
                    if (accelerationCount >= 3) {
                        cadence.onAccelerationMotion(at, SystemClock.elapsedRealtime())
                        noteMovement(at)
                    }
                }
            }
            Sensor.TYPE_PRESSURE -> {
                if (!pressureReliable) return
                val recentMotion = lastMotionMillis != Long.MIN_VALUE && at - lastMotionMillis in 0..5_000L
                detector.addPressure(event.values[0].toDouble(), at, recentMotion).forEach { movement ->
                    outgoing.trySend("vertical" to JSONObject()
                        .put("phase", movement.phase)
                        .put("relativeMeters", movement.relativeMeters)
                        .put("measuredAt", sensorInstant(movement.measuredAtMillis))
                        .put("detectedAt", Instant.now().toString())
                        .put("confidence", "estimated")
                        .put("evidence", movement.evidence))
                }
            }
        }
    }

    private fun noteMovement(at: Long) {
        lastMotionMillis = maxOf(lastMotionMillis, at)
        val now = SystemClock.elapsedRealtime()
        refreshCadence(now)
        requestFreshFixIfDue(now)
    }

    /** A grant from settings must activate steps in the existing sharing session immediately. */
    private fun refreshStepDetector() {
        if (!ActivityMotionMonitor.hasPermission(this)) {
            stepDetector?.let { sensors.unregisterListener(this, it) }
            stepDetector = null
            return
        }
        if (stepDetector != null) return
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return
        val registered = try {
            sensors.registerListener(this, sensor, 200_000, 5_000_000, handler)
        } catch (_: SecurityException) { false }
        if (registered) stepDetector = sensor
    }

    private fun notePhysicalMovement(at: Long, now: Long) {
        if (at >= 0 && now - at in 0..AdaptiveTrackingCadence.MAX_SENSOR_AGE_MILLIS) {
            stationaryFilter.notePhysicalMovement(at)
        }
    }

    private fun refreshCadence(now: Long) {
        if (!started || !repository.sharingEnabled) return
        if (policy.updateInterval(cadence.intervalMillis(now), now)) startLocationUpdates()
    }

    /** FLP owns periodic delivery; replace its subscription when sensor evidence changes cadence. */
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!started) return
        val interval = policy.intervalMillis
        if ((subscriptionActive || subscriptionStarting) && subscriptionIntervalMillis == interval) return
        removeLocationSubscription()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // Late callbacks from a replaced request must not consume a current slot.
                if (locationCallback === this && started) {
                    result.locations.maxByOrNull { it.elapsedRealtimeNanos }?.let(::acceptLocation)
                }
            }
        }
        locationCallback = callback
        subscriptionIntervalMillis = interval
        subscriptionStarting = true
        lastSubscriptionAttemptAt = SystemClock.elapsedRealtime()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval)
            .setMaxUpdateDelayMillis(0)
            .setMaxUpdateAgeMillis(0)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            fusedLocation.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnSuccessListener {
                    if (started && locationCallback === callback) {
                        subscriptionStarting = false
                        subscriptionActive = true
                    } else runCatching { fusedLocation.removeLocationUpdates(callback) }
                }
                .addOnFailureListener {
                    if (locationCallback === callback) {
                        subscriptionStarting = false
                        subscriptionActive = false
                        if (started) repository.noteTrackingStatus("위치 자동 확인을 연결하지 못했어요. 권한과 위치 설정을 확인해 주세요.")
                    }
                }
        } catch (_: SecurityException) {
            subscriptionStarting = false
            repository.noteTrackingStatus("정확한 위치 권한을 확인해 주세요. 새 위치를 기록하지 못했어요.")
        }
    }

    private fun removeLocationSubscription() {
        val previous = locationCallback
        locationCallback = null
        subscriptionActive = false
        subscriptionStarting = false
        subscriptionIntervalMillis = null
        previous?.let { runCatching { fusedLocation.removeLocationUpdates(it) } }
    }

    private fun stopLocationUpdates() {
        outboxRelay?.cancel()
        removeLocationSubscription()
    }

    private fun acceptLocation(location: Location) {
        if (!started || !repository.sharingEnabled) return
        val sample = location.toFixSample()
        val now = SystemClock.elapsedRealtime()
        refreshCadence(now)
        LocationFixValidation.error(sample, now)?.let {
            repository.noteTrackingStatus(it)
            return
        }
        if (!policy.reserve(sample, now)) return
        activityMotionMonitor.refresh()
        val display = stationaryFilter.filter(sample, now)
        lastFilteredDisplay = display
        lastFilteredAtElapsedMillis = sample.elapsedRealtimeMillis
        lastRawAccuracyMeters = sample.accuracyMeters
        val payload = JSONObject()
            .put("latitude", sample.latitude)
            .put("longitude", sample.longitude)
            .put("accuracy", sample.accuracyMeters)
            .put("capturedAt", Instant.ofEpochMilli(sample.capturedAtMillis).toString())
            .put("source", "automatic")
            .put("displayLatitude", display.displayLatitude)
            .put("displayLongitude", display.displayLongitude)
            .put("displayAccuracy", display.displayAccuracyMeters)
            .put("positionAdjusted", display.adjusted)
            .put("motion", display.motionState.name.lowercase(Locale.ROOT))
        display.stationarySinceElapsedMillis?.let { since ->
            val durationAtFix = (sample.elapsedRealtimeMillis - since).coerceAtLeast(0L)
            payload.put("stationarySince", Instant.ofEpochMilli(sample.capturedAtMillis - durationAtFix).toString())
        }
        val queued = outgoing.trySend("location" to payload)
        if (queued.isFailure) policy.cancelReservation()
        // "Saved" is reported by the writer only after the SQLite transaction has committed.
    }

    private fun checkDeadlines() {
        if (!started || !repository.sharingEnabled) return
        activityMotionMonitor.refresh()
        val now = SystemClock.elapsedRealtime()
        refreshCadence(now)
        if (now - lastHeartbeatMillis >= HEARTBEAT_INTERVAL_MILLIS) {
            lastHeartbeatMillis = now
            enqueueHeartbeat()
        }
        if (!subscriptionActive && !subscriptionStarting &&
            lastSubscriptionAttemptAt?.let { now - it >= policy.intervalMillis } != false) startLocationUpdates()
        requestFreshFixIfDue(now)
    }

    private fun requestFreshFixIfDue(now: Long) {
        if (!started || !repository.sharingEnabled) return
        // This awake-only watchdog is recovery, not the authoritative GPS timer.
        if (locationJob?.isActive != true && policy.beginWatchdog(now)) {
            locationJob = scope.launch {
                try {
                    val location = CurrentLocationProvider.capture(this@TrackingService, durationMillis = 30_000L)
                    acceptLocation(location)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (started && repository.sharingEnabled && policy.awaitingFreshFix(SystemClock.elapsedRealtime())) repository.noteTrackingStatus(
                        "현재 위치를 찾지 못했어요. 이전 위치를 새 기록으로 보내지 않고 새 위치를 기다려요.")
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
            .setContentTitle("부모님께 위치 공유 중")
            .setContentText("이동 중 약 20초, 정지 중 약 5분 · 높이 변화 추정")
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
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
        mutableRuntime.value = TrackingRuntime(stopping = true)
        repository.sharingEnabled = false
        started = false
        ticker?.cancel()
        locationJob?.cancel()
        stopLocationUpdates()
        activityMotionMonitor.stop()
        unregisterSensors()
        repository.noteTrackingStatus("자동 위치 공유 종료")
        // Stop collection immediately, then let the repository durably save queued metadata.
        scope.launch {
            if (repository.canShareLocation && !repository.demoMode) {
                outgoing.send("sharing_status" to JSONObject().put("enabled", false))
            }
            outgoing.close()
            withTimeoutOrNull(5_000L) { sender?.join() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            stopSelf()
        }
    }

    private fun unregisterSensors() {
        sensors.unregisterListener(this)
        stepDetector = null
        significantMotion?.let { sensors.cancelTriggerSensor(triggerListener, it) }
        handler.removeCallbacksAndMessages(null)
        detector.reset()
    }

    override fun onDestroy() {
        started = false
        if (runningInstance === this) runningInstance = null
        val error = mutableRuntime.value.error
        mutableRuntime.value = TrackingRuntime(error = error)
        if (repository.sharingEnabled) repository.noteTrackingStatus(error ?: "자동 위치 공유 재개 대기 · 앱을 열면 다시 시작해요.")
        stopLocationUpdates()
        activityMotionMonitor.stop()
        unregisterSensors()
        outgoing.close()
        scope.cancel()
        stopped.complete(Unit)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.println("sharingRunning=$started sharingStopping=$stopping")
        writer.println("requestedLocationIntervalMillis=${if (::policy.isInitialized) policy.intervalMillis else "none"} subscriptionIntervalMillis=${subscriptionIntervalMillis ?: "none"} subscriptionActive=$subscriptionActive")
        activityMotionMonitor.dump(writer)
        val display = lastFilteredDisplay
        val duration = display?.stationarySinceElapsedMillis?.let { since ->
            lastFilteredAtElapsedMillis?.let { (it - since).coerceAtLeast(0L) }
        }
        writer.println("lastDisplayMotion=${display?.motionState ?: MotionState.UNKNOWN} lastPositionAdjusted=${display?.adjusted ?: false}")
        writer.println("lastRawAccuracyMeters=${lastRawAccuracyMeters ?: "none"} lastDisplayAccuracyMeters=${display?.displayAccuracyMeters ?: "none"} lastFixAgeMillis=${lastFilteredAtElapsedMillis?.let { SystemClock.elapsedRealtime() - it } ?: "none"}")
        writer.println("stationaryDurationAtLastFixMillis=${duration ?: "none"}")
    }

    companion object {
        data class TrackingRuntime(val running: Boolean = false, val starting: Boolean = false,
            val stopping: Boolean = false, val error: String? = null)
        private val mutableRuntime = MutableStateFlow(TrackingRuntime())
        val runtime = mutableRuntime.asStateFlow()
        @Volatile private var runningInstance: TrackingService? = null
        private const val CHANNEL_ID = "homeway_location_sharing"
        private const val NOTIFICATION_ID = 4001
        private const val ACTION_START = "kr.family.homeway.START_LOCATION_SHARING"
        private const val ACTION_STOP = "kr.family.homeway.STOP_LOCATION_SHARING"
        private const val HEARTBEAT_INTERVAL_MILLIS = 5 * 60_000L

        /** Call only from a visible activity after the child's explicit sharing consent. */
        fun start(context: Context): Boolean {
            check(runningInstance?.stopping != true) { "위치 공유를 종료하고 있어요. 잠시 후 다시 켜 주세요." }
            if (runtime.value.running || runtime.value.starting) return true
            val repo = AppRepository(context)
            if (startDecision(repo, context) != TrackingStartPolicy.Decision.START) {
                repo.sharingEnabled = false
                repo.noteTrackingStatus("위치 공유 설정과 권한을 확인해 주세요.")
                return false
            }
            mutableRuntime.value = TrackingRuntime(starting = true)
            repo.noteTrackingStatus("자동 위치 공유 시작 중…")
            return try {
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).setAction(ACTION_START))
                true
            } catch (_: RuntimeException) {
                val message = "자동 위치 공유 재개 대기 · 앱을 열고 다시 시도해 주세요."
                repo.noteTrackingStatus(message)
                mutableRuntime.value = TrackingRuntime(error = message)
                false
            }
        }

        /** Call only while an Activity is visible; never changes an off preference to on. */
        fun resumeSavedSharing(context: Context) {
            val repo = AppRepository(context)
            when (startDecision(repo, context)) {
                TrackingStartPolicy.Decision.STOP -> Unit
                TrackingStartPolicy.Decision.CLEAR_CONSENT_AND_STOP -> stop(context)
                TrackingStartPolicy.Decision.START -> if (!runtime.value.stopping) start(context)
            }
        }

        /** Refresh an existing child's session after a visible Activity returns from permission UI. */
        fun refreshActivityRecognition(context: Context) {
            val active = runningInstance ?: return
            fun refresh() {
                if (active.started && !active.stopping &&
                    startDecision(active.repository, context) == TrackingStartPolicy.Decision.START) {
                    active.refreshStepDetector()
                    active.armSignificantMotion()
                    active.activityMotionMonitor.refresh()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) refresh() else active.handler.post { refresh() }
        }

        /** A receipt can only enrich an already running, valid, opted-in child session. */
        internal fun receiveActivityMotion(intent: Intent) {
            val active = runningInstance ?: return
            if (!active.started || active.stopping ||
                startDecision(active.repository, active) != TrackingStartPolicy.Decision.START) return
            active.activityMotionMonitor.receive(intent)
        }

        private fun startDecision(repo: AppRepository, context: Context) = TrackingStartPolicy.decide(
            repo.sharingEnabled, repo.canShareLocation, repo.isChild, repo.demoMode, hasRequiredPermissions(context))

        fun stop(context: Context) {
            // Do not create a service for demo/reset flows, and stop collection before returning.
            val active = runningInstance
            if (active != null) {
                if (Looper.myLooper() == Looper.getMainLooper()) active.stopSharing()
                else active.handler.post { active.stopSharing() }
            } else {
                AppRepository(context).apply { sharingEnabled = false; noteTrackingStatus("자동 위치 공유 종료") }
                context.stopService(Intent(context, TrackingService::class.java))
                mutableRuntime.value = TrackingRuntime()
            }
        }

        /** Wait before clearing account settings so queued sensor writes cannot cross accounts. */
        suspend fun stopAndAwait(context: Context) = withContext(Dispatchers.Main.immediate) {
            val active = runningInstance
            if (active != null) {
                active.stopSharing()
                active.stopped.await()
            } else stop(context)
        }

        fun hasRequiredPermissions(context: Context): Boolean {
            val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val notification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            return location && notification
        }
    }
}
