package kr.family.homeway.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class MotionState { UNKNOWN, STILL, WALKING, RUNNING, BICYCLE, VEHICLE }

/** Display-only estimate. The caller retains the complete, unchanged provider sample separately. */
data class FilteredLocation(
    val displayLatitude: Double,
    val displayLongitude: Double,
    val displayAccuracyMeters: Double?,
    val motionState: MotionState,
    val stationarySinceElapsedMillis: Long?,
    val adjusted: Boolean,
)

/**
 * Combines positive activity evidence with repeated nearby fixes. Transition ENTER remains valid
 * until an exit; snapshots expire after 15 minutes. This never schedules,
 * suppresses, replaces or creates location records. No activity event is not evidence of rest.
 *
 * An anchor stays at its original coordinate rather than following a chain of noisy fixes. One
 * incompatible fix within 250 m widens displayed uncertainty; two consecutive incompatible fixes
 * or a single larger displacement release it, even if recognition incorrectly says STILL.
 * Thresholds are conservative starting values, not a guarantee of room-level positioning.
 */
class StationaryLocationFilter {
    private data class MotionUpdate(val state: MotionState, val time: Long, val persistentUntilExit: Boolean)
    private data class Candidate(val first: LocationFixSample, var confirmed: Boolean = false)

    private val pendingMotion = ArrayDeque<MotionUpdate>()
    private var motion = MotionState.UNKNOWN
    private var motionUpdatedAt: Long? = null
    private var stillSince: Long? = null
    private var stillLatchedUntilExit = false
    private var lastNow: Long? = null
    private var lastFixAt: Long? = null
    private var candidate: Candidate? = null
    private var incompatibleFixes = 0

    /** Event timestamps must use Android elapsed realtime, not wall-clock time. */
    @Synchronized
    fun updateMotion(state: MotionState, atElapsedMillis: Long, persistentUntilExit: Boolean = false) {
        if (atElapsedMillis < 0) return
        // Bound even a faulty event source. Lost transitions cannot support a continuous dwell.
        if (pendingMotion.size >= MAX_PENDING_EVENTS) {
            pendingMotion.clear()
            forgetMotion()
        }
        pendingMotion.addLast(MotionUpdate(state, atElapsedMillis, persistentUntilExit))
    }

    /** Call once per accepted automatic fix, before persisting its raw and display coordinates. */
    @Synchronized
    fun filter(sample: LocationFixSample, nowElapsedMillis: Long): FilteredLocation {
        if (nowElapsedMillis < 0 || lastNow?.let { nowElapsedMillis < it } == true) {
            return raw(sample, MotionState.UNKNOWN)
        }
        lastNow = nowElapsedMillis
        consumeMotion(nowElapsedMillis)
        if (LocationFixValidation.error(sample, nowElapsedMillis) != null) {
            clearContinuity()
            return raw(sample, MotionState.UNKNOWN)
        }
        // A duplicate/batched older fix cannot count as the second observation of the same area.
        if (lastFixAt?.let { sample.elapsedRealtimeMillis <= it } == true) {
            return raw(sample, MotionState.UNKNOWN)
        }
        if (lastFixAt?.let { sample.elapsedRealtimeMillis - it > MAX_FIX_GAP_MS } == true) {
            clearContinuity()
        }
        lastFixAt = sample.elapsedRealtimeMillis

        val motionStart = stillSince
        if (motion != MotionState.STILL || motionStart == null || sample.elapsedRealtimeMillis < motionStart) {
            clearContinuity()
            return raw(sample, motion)
        }

        val current = candidate ?: return beginCandidate(sample)
        val offset = distanceMeters(current.first, sample)
        if (offset > MAX_ANCHOR_OFFSET_METERS) {
            forgetMotion()
            return raw(sample, MotionState.UNKNOWN)
        }
        val radius = noiseRadiusMeters(current.first, sample)
        if (offset > radius) {
            if (!current.confirmed) return beginCandidate(sample)
            incompatibleFixes += 1
            if (incompatibleFixes >= INCOMPATIBLE_FIXES_TO_RELEASE) {
                // GPS contradicts this activity episode. Require fresh positive evidence and
                // another stable pair before starting a new dwell at a different place.
                forgetMotion()
                return raw(sample, MotionState.UNKNOWN)
            }
        } else {
            incompatibleFixes = 0
            if (!current.confirmed) {
                current.confirmed = sample.elapsedRealtimeMillis - motionStart >= MIN_STILL_MS &&
                    sample.elapsedRealtimeMillis - current.first.elapsedRealtimeMillis >= MIN_STILL_MS
            }
        }
        if (!current.confirmed) return raw(sample, motion)

        val anchor = current.first
        val ageAllowance = ((sample.elapsedRealtimeMillis - anchor.elapsedRealtimeMillis) / 60_000.0 * 0.5)
            .coerceIn(0.0, 30.0)
        // Accuracy describes the displayed anchor, not just the fresh fix at a different point.
        val accuracy = max(checkNotNull(anchor.accuracyMeters), offset + checkNotNull(sample.accuracyMeters)) + ageAllowance
        return FilteredLocation(
            displayLatitude = anchor.latitude,
            displayLongitude = anchor.longitude,
            displayAccuracyMeters = accuracy,
            motionState = motion,
            stationarySinceElapsedMillis = anchor.elapsedRealtimeMillis,
            adjusted = anchor.latitude != sample.latitude || anchor.longitude != sample.longitude,
        )
    }

    private fun consumeMotion(now: Long) {
        while (pendingMotion.isNotEmpty()) {
            val event = pendingMotion.removeFirst()
            // Future data cannot poison later valid events. A stale exit may end a latched
            // dwell, but a stale positive STILL observation cannot start or revive one.
            if (event.time > now || (event.state == MotionState.STILL && now - event.time > MAX_ACTIVITY_AGE_MS) ||
                motionUpdatedAt?.let { event.time < it } == true) continue
            if (event.time == motionUpdatedAt) {
                if (event.state == MotionState.STILL) {
                    // A transition may share a timestamp with a snapshot or EXIT(old). Honor
                    // an explicit ENTER(new) in delivery order, but a weak STILL snapshot
                    // cannot overwrite a simultaneous known exit.
                    if (motion == MotionState.STILL) {
                        if (event.persistentUntilExit) stillLatchedUntilExit = true
                        continue
                    }
                    if (!event.persistentUntilExit) continue
                }
                if (event.state == motion) continue
            }
            val gap = !stillLatchedUntilExit && motionUpdatedAt?.let { event.time - it > MAX_ACTIVITY_AGE_MS } == true
            if (gap || event.state != motion || event.state != MotionState.STILL) {
                clearContinuity()
                stillSince = if (event.state == MotionState.STILL) event.time else null
            }
            stillLatchedUntilExit = event.state == MotionState.STILL && (stillLatchedUntilExit || event.persistentUntilExit)
            motion = event.state
            motionUpdatedAt = event.time
        }
        if (!stillLatchedUntilExit && motionUpdatedAt?.let { now - it > MAX_ACTIVITY_AGE_MS } != false) forgetMotion()
    }

    private fun beginCandidate(sample: LocationFixSample): FilteredLocation {
        candidate = Candidate(sample)
        incompatibleFixes = 0
        return raw(sample, motion)
    }

    private fun clearContinuity() {
        candidate = null
        incompatibleFixes = 0
    }

    private fun forgetMotion() {
        motion = MotionState.UNKNOWN
        stillSince = null
        stillLatchedUntilExit = false
        clearContinuity()
    }

    private fun raw(sample: LocationFixSample, state: MotionState) = FilteredLocation(
        sample.latitude, sample.longitude, sample.accuracyMeters, state, null, false,
    )

    private fun noiseRadiusMeters(anchor: LocationFixSample, sample: LocationFixSample): Double =
        (2.0 * hypot(checkNotNull(anchor.accuracyMeters), checkNotNull(sample.accuracyMeters)))
            .coerceIn(MIN_NOISE_RADIUS_METERS, MAX_NOISE_RADIUS_METERS)

    private fun distanceMeters(first: LocationFixSample, second: LocationFixSample): Double {
        val firstLat = Math.toRadians(first.latitude)
        val secondLat = Math.toRadians(second.latitude)
        val deltaLat = secondLat - firstLat
        val deltaLon = Math.toRadians(second.longitude - first.longitude)
        val haversine = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(firstLat) * cos(secondLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 6_371_000.0 * 2 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    companion object {
        private const val MIN_STILL_MS = 2 * 60_000L
        private const val MAX_ACTIVITY_AGE_MS = 15 * 60_000L
        private const val MAX_FIX_GAP_MS = 10 * 60_000L
        private const val MIN_NOISE_RADIUS_METERS = 40.0
        private const val MAX_NOISE_RADIUS_METERS = 120.0
        private const val MAX_ANCHOR_OFFSET_METERS = 250.0
        private const val INCOMPATIBLE_FIXES_TO_RELEASE = 2
        private const val MAX_PENDING_EVENTS = 64
    }
}
