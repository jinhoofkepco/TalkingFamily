package kr.family.homeway.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.io.PrintWriter
import java.util.UUID

/** Low-power activity evidence only. This class never requests location or starts a service. */
internal class ActivityMotionMonitor(
    context: Context,
    private val onMotion: (MotionState, Long, Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val client = ActivityRecognition.getClient(appContext)
    private var active = false
    private var sessionId = ""
    private var evidence = ActivityMotionEvidence(Long.MAX_VALUE)
    private var transitionsRequested = false
    private var samplesRequested = false
    private var transitionsRegistered = false
    private var samplesRegistered = false
    private var lastFailure: String? = null
    private var lastMotionState = MotionState.UNKNOWN
    private var lastMotionPersistent = false
    private var lastMotionAt: Long? = null
    private var lastReceiptAt: Long? = null
    private var receiptCount = 0L
    private var acceptedCount = 0L
    private var lastAttemptAt: Long? = null
    private var transitionIntent: PendingIntent? = null
    private var sampleIntent: PendingIntent? = null

    /** Also called on the normal visible-Activity resume path after a permission grant. */
    fun refresh() {
        if (!hasPermission(appContext)) {
            stop()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!active) {
            active = true
            owner = this
            sessionId = UUID.randomUUID().toString()
            evidence = ActivityMotionEvidence(now)
            lastMotionState = MotionState.UNKNOWN
            lastMotionPersistent = false
            lastMotionAt = null
            lastReceiptAt = null
            receiptCount = 0
            acceptedCount = 0
            lastFailure = null
            transitionIntent = pendingIntent(ACTION_TRANSITIONS, 4101)
            sampleIntent = pendingIntent(ACTION_SAMPLES, 4102)
        }
        if (transitionsRequested && samplesRequested) return
        if (lastAttemptAt?.let { now - it < SAMPLE_INTERVAL_MILLIS } == true) return
        lastAttemptAt = now
        registerUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun registerUpdates() {
        val registeringSession = sessionId
        if (!transitionsRequested) {
            transitionsRequested = true
            val callback = checkNotNull(transitionIntent)
            enqueue(appContext) {
                if (!owns(registeringSession)) return@enqueue completed()
                val transitions = SUPPORTED_ACTIVITIES.flatMap { activity ->
                    listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                        .map { transition -> ActivityTransition.Builder().setActivityType(activity)
                            .setActivityTransition(transition).build() }
                }
                client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), callback)
            }.addOnSuccessListener {
                if (owns(registeringSession)) transitionsRegistered = true
            }.addOnFailureListener {
                if (owns(registeringSession)) {
                    transitionsRequested = false
                    transitionsRegistered = false
                    lastFailure = failureCode(it)
                }
            }
        }
        if (!samplesRequested) {
            samplesRequested = true
            val callback = checkNotNull(sampleIntent)
            enqueue(appContext) {
                if (!owns(registeringSession)) return@enqueue completed()
                // Bootstrap a stationary phone and refresh evidence; no raw sensor stream is sent.
                client.requestActivityUpdates(SAMPLE_INTERVAL_MILLIS, callback)
            }.addOnSuccessListener {
                if (owns(registeringSession)) samplesRegistered = true
            }.addOnFailureListener {
                if (owns(registeringSession)) {
                    samplesRequested = false
                    samplesRegistered = false
                    lastFailure = failureCode(it)
                }
            }
        }
    }

    fun receive(intent: Intent) {
        if (!active || owner !== this || intent.getStringExtra(EXTRA_SESSION) != sessionId) return
        if (!hasPermission(appContext)) {
            stop()
            return
        }
        val now = SystemClock.elapsedRealtime()
        receiptCount += 1
        lastReceiptAt = now
        // Bad parcels or provider failures must not stop the independent location schedule.
        runCatching {
            when (intent.action) {
                ACTION_TRANSITIONS -> ActivityTransitionResult.extractResult(intent)?.transitionEvents
                    ?.forEach { event ->
                        val state = motionState(event.activityType)
                        val entering = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                        if (entering || event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                            evidence.transition(state, entering, event.elapsedRealTimeNanos / 1_000_000L, now)
                                ?.let(::acceptObservation)
                        }
                    }
                ACTION_SAMPLES -> ActivityRecognitionResult.extractResult(intent)?.let { result ->
                    val supported = result.probableActivities.filter { motionState(it.type) != MotionState.UNKNOWN }
                        .sortedByDescending { it.confidence }
                    val mostProbable = result.mostProbableActivity
                    // ON_FOOT may refine to WALKING/RUNNING; any other unsupported strongest
                    // result stays UNKNOWN instead of promoting a weaker STILL prediction.
                    val strongest = if (mostProbable.type == DetectedActivity.ON_FOOT &&
                        supported.firstOrNull()?.type in listOf(DetectedActivity.WALKING, DetectedActivity.RUNNING))
                        supported.first() else mostProbable
                    val ambiguous = motionState(strongest.type) != MotionState.UNKNOWN &&
                        supported.size > 1 && strongest.confidence - supported[1].confidence < 10
                    if (!ambiguous) evidence.sample(motionState(strongest.type), strongest.confidence,
                        result.elapsedRealtimeMillis, now)?.let(::acceptObservation)
                }
            }
        }
    }

    private fun acceptObservation(observation: ActivityMotionEvidence.Observation) {
        acceptedCount += 1
        lastMotionPersistent = observation.persistentUntilExit ||
            (lastMotionPersistent && lastMotionState == MotionState.STILL && observation.state == MotionState.STILL)
        lastMotionState = observation.state
        lastMotionAt = observation.atElapsedMillis
        onMotion(observation.state, observation.atElapsedMillis, observation.persistentUntilExit)
    }

    /** Debug-service diagnostics contain status only, never coordinates, account data or raw events. */
    fun dump(writer: PrintWriter) {
        val now = SystemClock.elapsedRealtime()
        val age = lastMotionAt?.let { now - it }
        val freshState = lastMotionState.takeIf { age != null && age >= 0 &&
            (lastMotionPersistent || age <= ActivityMotionEvidence.MAX_AGE_MILLIS) }
            ?: MotionState.UNKNOWN
        writer.println("activityRecognitionPermission=${hasPermission(appContext)} active=$active")
        writer.println("activityTransitionsRegistered=$transitionsRegistered activitySamplesRegistered=$samplesRegistered")
        writer.println("activityLastFailure=${lastFailure ?: "none"} receiptCount=$receiptCount acceptedCount=$acceptedCount")
        writer.println("activityState=$freshState transitionLatched=$lastMotionPersistent evidenceAgeMillis=${age ?: "none"} receiptAgeMillis=${lastReceiptAt?.let { now - it } ?: "none"}")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!active) return
        active = false
        transitionsRequested = false
        samplesRequested = false
        transitionsRegistered = false
        samplesRegistered = false
        lastMotionState = MotionState.UNKNOWN
        lastMotionPersistent = false
        lastMotionAt = null
        lastAttemptAt = null
        if (owner === this) owner = null
        onMotion(MotionState.UNKNOWN, SystemClock.elapsedRealtime(), false)
        val transitions = transitionIntent
        val samples = sampleIntent
        // Registration and removal share one main-thread queue across service generations.
        // A stop while registration is in flight removes it after completion; a replacement
        // monitor owns the stable PendingIntents and must not be removed by the old owner.
        enqueue(appContext) {
            if (owner != null || transitions == null) completed()
            else client.removeActivityTransitionUpdates(transitions)
        }
        enqueue(appContext) {
            if (owner != null || samples == null) completed()
            else client.removeActivityUpdates(samples)
        }
    }

    private fun owns(expectedSession: String) = active && owner === this && sessionId == expectedSession

    private fun pendingIntent(action: String, requestCode: Int): PendingIntent {
        // A stable identity replaces registrations left by process death. The nonce and
        // monotonic session start reject old deliveries without persisting activity state.
        val intent = Intent(appContext, ActivityMotionReceiver::class.java).setAction(action)
            .putExtra(EXTRA_SESSION, sessionId)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(appContext, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    companion object {
        private const val ACTION_TRANSITIONS = "kr.family.homeway.ACTIVITY_TRANSITIONS"
        private const val ACTION_SAMPLES = "kr.family.homeway.ACTIVITY_SAMPLES"
        private const val EXTRA_SESSION = "activityMonitorSession"
        private const val SAMPLE_INTERVAL_MILLIS = 5 * 60_000L
        private val SUPPORTED_ACTIVITIES = listOf(DetectedActivity.STILL, DetectedActivity.WALKING,
            DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE)
        private var owner: ActivityMotionMonitor? = null
        private var operations: Task<Void> = completed()

        private fun completed(): Task<Void> = Tasks.forResult(null)

        private fun failureCode(failure: Exception): String = when (failure) {
            is ApiException -> "api_${failure.statusCode}"
            is SecurityException -> "permission"
            else -> "unavailable"
        }

        /** Both successes and failures release the queue; no registration can outrun cleanup. */
        private fun enqueue(context: Context, operation: () -> Task<Void>): Task<Void> {
            operations = operations.continueWithTask(ContextCompat.getMainExecutor(context)) {
                try { operation() } catch (failure: RuntimeException) { Tasks.forException(failure) }
            }
            return operations
        }

        fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        private fun motionState(type: Int): MotionState = when (type) {
            DetectedActivity.STILL -> MotionState.STILL
            DetectedActivity.WALKING -> MotionState.WALKING
            DetectedActivity.RUNNING -> MotionState.RUNNING
            DetectedActivity.ON_BICYCLE -> MotionState.BICYCLE
            DetectedActivity.IN_VEHICLE -> MotionState.VEHICLE
            else -> MotionState.UNKNOWN
        }
    }
}
