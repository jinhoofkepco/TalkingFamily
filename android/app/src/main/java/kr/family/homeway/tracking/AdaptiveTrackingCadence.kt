package kr.family.homeway.tracking

/**
 * Selects a bounded tracking interval from positive motion evidence, never GPS displacement.
 * Returning to the idle cadence means conserving power; it does not claim the phone is stationary.
 * All timestamps use elapsed realtime, and leases are based on observation time, not delivery time.
 */
class AdaptiveTrackingCadence {
    private var lastNow: Long? = null
    private var activityUntil = 0L
    private var sensorUntil = 0L
    private var lastActivityAt: Long? = null
    private var lastActivityState = MotionState.UNKNOWN
    private var lastStepAt: Long? = null
    private var lastSignificantMotionAt: Long? = null
    private var lastAccelerationAt: Long? = null
    private var accelerationWindowAt: Long? = null
    private var lastAccelerationBurstAt: Long? = null
    private var accelerationBursts = 0

    /** Upstream ActivityMotionEvidence has already checked confidence and transition ordering. */
    @Synchronized
    fun onActivity(state: MotionState, observedAt: Long, persistentUntilExit: Boolean, now: Long) {
        if (!fresh(observedAt, now, MAX_ACTIVITY_AGE_MILLIS)) return
        if (lastActivityAt?.let { observedAt < it } == true) return
        if (observedAt == lastActivityAt) {
            if (state == lastActivityState) return
            // A simultaneous weak STILL snapshot cannot undo a moving transition.
            if (state == MotionState.STILL && !persistentUntilExit && moving(lastActivityState)) return
        }
        lastActivityAt = observedAt
        lastActivityState = state
        if (moving(state)) {
            // Even ENTER has a maximum lease: a lost EXIT cannot leave high-rate GPS on overnight.
            // Fresh five-minute activity samples renew this six-minute allowance.
            activityUntil = deadline(observedAt, ACTIVITY_LEASE_MILLIS)
        } else {
            // Neither EXIT/UNKNOWN nor STILL invents motion. They only shorten an existing lease.
            // Repeated resting observations cannot keep postponing the return to idle.
            activityUntil = minOf(activityUntil, deadline(observedAt, STOP_HYSTERESIS_MILLIS))
        }
    }

    @Synchronized
    fun onStep(at: Long, now: Long) {
        if (!fresh(at, now, MAX_SENSOR_AGE_MILLIS) || lastStepAt?.let { at <= it } == true) return
        lastStepAt = at
        extendSensorLease(at)
    }

    @Synchronized
    fun onSignificantMotion(at: Long, now: Long) {
        if (!fresh(at, now, MAX_SENSOR_AGE_MILLIS) || lastSignificantMotionAt?.let { at <= it } == true) return
        lastSignificantMotionAt = at
        extendSensorLease(at)
    }

    /** A single pickup or a dense batch from one short shake must not start high-rate tracking. */
    @Synchronized
    fun onAccelerationMotion(at: Long, now: Long) {
        if (!fresh(at, now, MAX_SENSOR_AGE_MILLIS) || lastAccelerationAt?.let { at <= it } == true) return
        lastAccelerationAt = at
        val start = accelerationWindowAt
        if (start == null || at - start > ACCELERATION_WINDOW_MILLIS) {
            startAccelerationWindow(at)
            return
        }
        if (lastAccelerationBurstAt?.let { at - it < ACCELERATION_BURST_SEPARATION_MILLIS } == true) return
        lastAccelerationBurstAt = at
        accelerationBursts++
        if (accelerationBursts >= ACCELERATION_REQUIRED_BURSTS && at - start >= ACCELERATION_CONFIRMATION_MILLIS) {
            extendSensorLease(at)
            startAccelerationWindow(at)
        }
    }

    @Synchronized
    fun intervalMillis(now: Long): Long {
        if (!validNow(now)) return IDLE_INTERVAL_MILLIS
        return if (now < maxOf(activityUntil, sensorUntil)) MOVING_INTERVAL_MILLIS else IDLE_INTERVAL_MILLIS
    }

    private fun extendSensorLease(at: Long) { sensorUntil = maxOf(sensorUntil, deadline(at, SENSOR_LEASE_MILLIS)) }

    private fun startAccelerationWindow(at: Long) {
        accelerationWindowAt = at
        lastAccelerationBurstAt = at
        accelerationBursts = 1
    }

    private fun fresh(at: Long, now: Long, maxAge: Long): Boolean =
        validNow(now) && at >= 0 && at <= now && now - at <= maxAge

    private fun validNow(now: Long): Boolean {
        if (now < 0 || lastNow?.let { now < it } == true) return false
        lastNow = now
        return true
    }

    private fun deadline(at: Long, duration: Long): Long = if (Long.MAX_VALUE - at < duration) Long.MAX_VALUE else at + duration

    private fun moving(state: MotionState) = state in setOf(MotionState.WALKING, MotionState.RUNNING, MotionState.BICYCLE, MotionState.VEHICLE)

    companion object {
        const val MOVING_INTERVAL_MILLIS = 20_000L
        const val IDLE_INTERVAL_MILLIS = 300_000L
        const val MAX_ACTIVITY_AGE_MILLIS = 30_000L
        const val MAX_SENSOR_AGE_MILLIS = 30_000L
        const val ACTIVITY_LEASE_MILLIS = 360_000L
        const val SENSOR_LEASE_MILLIS = 120_000L
        const val STOP_HYSTERESIS_MILLIS = 120_000L
        const val ACCELERATION_CONFIRMATION_MILLIS = 2_000L
        const val ACCELERATION_WINDOW_MILLIS = 4_000L
        const val ACCELERATION_BURST_SEPARATION_MILLIS = 500L
        const val ACCELERATION_REQUIRED_BURSTS = 3
    }
}
