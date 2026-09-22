package kr.family.homeway.tracking

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Relative barometric height only, never a floor number. Timestamps use the sensor's monotonic
 * clock so batching and network latency do not become the child's reported movement times.
 * Pressure trend and physical motion must agree. Steps allow slower climbs, but never prove
 * stairs: a ramp or walking in a lift can have the same evidence. Thresholds require field tests.
 */
class VerticalMovementDetector {
    data class Event(val phase: String, val relativeMeters: Double, val measuredAtMillis: Long,
        val evidence: String = "barometer_motion")
    private data class Point(val time: Long, val altitude: Double, val rawAltitude: Double, val motion: Boolean)
    private data class Journey(val direction: Int, val start: Point, var extreme: Point, var evidence: String)

    private val recentPressure = ArrayDeque<Double>()
    private val history = ArrayDeque<Point>()
    private val steps = mutableListOf<Long>()
    private var filteredAltitude: Double? = null
    private var previousTime: Long? = null
    private var journey: Journey? = null
    val hasActiveMovement: Boolean get() = journey != null

    fun reset() {
        recentPressure.clear()
        history.clear()
        steps.clear()
        filteredAltitude = null
        previousTime = null
        journey = null
    }

    /** Actual step event time on the same elapsed-realtime clock as pressure, not delivery time. */
    fun noteStep(timestampMillis: Long) {
        if (timestampMillis < 0 || timestampMillis in steps) return
        previousTime?.let { if (timestampMillis < it - HISTORY_MS) return }
        // Different sensor batches can arrive in either order; retain their original timestamps.
        steps.add(timestampMillis)
        steps.sort()
        while (steps.size > MAX_STEPS) steps.removeAt(0)
    }

    fun addPressure(pressureHpa: Double, timestampMillis: Long, recentPhysicalMotion: Boolean): List<Event> {
        if (!pressureHpa.isFinite() || pressureHpa !in 300.0..1100.0 || timestampMillis < 0) return emptyList()
        previousTime?.let { previous ->
            if (timestampMillis <= previous) return emptyList()
            // A suspended or restarted sensor must not bridge two unrelated journeys.
            if (timestampMillis - previous > MAX_GAP_MS) reset()
            else if (timestampMillis - previous < MIN_ANALYSIS_INTERVAL_MS) return emptyList()
        }
        val deltaMillis = previousTime?.let { timestampMillis - it }
        previousTime = timestampMillis
        steps.removeAll { timestampMillis - it > HISTORY_MS }
        recentPressure.addLast(pressureHpa)
        while (recentPressure.size > 3) recentPressure.removeFirst()
        val median = recentPressure.sorted()[recentPressure.size / 2]
        val rawAltitude = 44330.0 * (1.0 - (median / 1013.25).pow(0.19029495))
        // A requested Android sampling period is a hint. Filtering must use measured time.
        val alpha = deltaMillis?.let { 1.0 - exp(-it / SMOOTHING_TIME_MS) } ?: 1.0
        val smoothed = filteredAltitude?.let { it + alpha * (rawAltitude - it) } ?: rawAltitude
        filteredAltitude = smoothed
        val point = Point(timestampMillis, smoothed, rawAltitude, recentPhysicalMotion)
        history.addLast(point)
        while (history.isNotEmpty() && timestampMillis - history.first().time > HISTORY_MS) history.removeFirst()

        val active = journey
        if (active == null) {
            val candidate = candidateStart(point) ?: return emptyList()
            val evidence = evidence(candidate.second, point)
            journey = Journey(candidate.first, candidate.second, point, evidence)
            return listOf(Event(phase(candidate.first, true), 0.0, candidate.second.time, evidence))
        }

        if (hasSteps(active.start.time, point.time)) active.evidence = "barometer_steps"
        val advance = active.direction * (point.altitude - active.extreme.altitude)
        val continuedTrend = directionalTrend(active.direction, active.start.time, point)
        if (advance > PROGRESS_METERS && continuedTrend && movementEvidence(active.start.time, point)) active.extreme = point
        val reverseDistance = active.direction * (active.extreme.altitude - point.altitude)
        val sinceExtreme = point.time - active.extreme.time
        val reversing = directionalTrend(-active.direction, active.extreme.time, point, fromTurningPoint = true) &&
            movementEvidence(active.extreme.time, point)
        if (reverseDistance >= REVERSAL_METERS && sinceExtreme >= REVERSAL_MS && reversing) {
            // Close the prior movement before reporting the new direction, at the same pivot.
            val finished = finishedEvent(active)
            val direction = -active.direction
            val evidence = evidence(active.extreme, point)
            journey = Journey(direction, active.extreme, point, evidence)
            history.clear()
            history.addLast(active.extreme)
            history.addLast(point)
            return listOf(finished, Event(phase(direction, true), 0.0, active.extreme.time, evidence))
        }
        if (sinceExtreme >= SETTLE_MS && !reversing) {
            // An unsupported pressure reversal must not leave the old journey alive forever.
            // Conversely, a slow supported descent can take longer than SETTLE_MS to reach
            // the reversal distance. Retain its pivot instead of discarding the first metres.
            journey = null
            history.clear()
            history.addLast(point)
            return listOf(finishedEvent(active))
        }
        return emptyList()
    }

    private fun candidateStart(current: Point): Pair<Int, Point>? {
        if (history.size < 5 || (!current.motion && !hasSteps(history.first().time, current.time))) return null
        val lowest = history.minOf { it.altitude }
        val highest = history.maxOf { it.altitude }
        // Last point near the baseline avoids counting an earlier stationary period as movement.
        val upStart = history.lastOrNull { it.altitude <= lowest + BASELINE_TOLERANCE_METERS }
        val downStart = history.lastOrNull { it.altitude >= highest - BASELINE_TOLERANCE_METERS }
        val candidates = listOfNotNull(upStart?.let { 1 to it }, downStart?.let { -1 to it })
        return candidates.firstOrNull { (direction, start) ->
            val duration = current.time - start.time
            val distance = direction * (current.altitude - start.altitude)
            val minimumSpeed = if (hasSteps(start.time, current.time)) MIN_STEPPING_SPEED else MIN_SPEED_METERS_PER_SECOND
            duration >= START_MIN_MS && distance >= START_METERS &&
                distance / (duration / 1000.0) in minimumSpeed..MAX_SPEED_METERS_PER_SECOND &&
                directionalTrend(direction, start.time, current)
        }
    }

    private fun hasSteps(start: Long, end: Long): Boolean {
        val within = steps.filter { it in start..end }
        return within.size >= MIN_STEPS && end - within.last() <= RECENT_STEP_MS &&
            within.last() - within.first() >= MIN_STEP_SPAN_MS
    }

    private fun evidence(start: Point, current: Point) =
        if (hasSteps(start.time, current.time)) "barometer_steps" else "barometer_motion"

    /** A coherent raw trend rejects a pressure step whose low-pass tail only looks like climbing. */
    private fun directionalTrend(direction: Int, start: Long, current: Point, fromTurningPoint: Boolean = false): Boolean {
        val window = history.filter { it.time >= maxOf(start, current.time - TREND_WINDOW_MS) }
        // The smoothed progress checkpoint can precede the actual pressure peak. Counting that
        // remaining ascent as part of a slow descent dilutes its speed and prematurely settles
        // the journey. Test the opposite raw trend from its last turning point, with the same
        // duration, repeated progress, speed and consistency requirements as any other trend.
        val segment = if (fromTurningPoint && window.isNotEmpty()) {
            val baseline = window.minOf { direction * it.rawAltitude }
            window.drop(window.indexOfLast { direction * it.rawAltitude == baseline })
        } else window
        if (segment.size < 4) return false
        val span = current.time - segment.first().time
        if (span < TREND_MIN_MS) return false
        val changes = segment.zipWithNext { a, b -> direction * (b.rawAltitude - a.rawAltitude) }
        val progress = changes.filter { it >= RAW_PROGRESS_METERS }
        if (progress.size < 3) return false
        val net = direction * (current.rawAltitude - segment.first().rawAltitude)
        val total = changes.sumOf { abs(it) }
        val minimumSpeed = if (hasSteps(start, current.time)) MIN_STEPPING_SPEED else MIN_SPEED_METERS_PER_SECOND
        return net / (span / 1000.0) in minimumSpeed..MAX_SPEED_METERS_PER_SECOND &&
            net >= total * MIN_TREND_CONSISTENCY && (progress.maxOrNull() ?: 0.0) <= net * MAX_SINGLE_JUMP_SHARE
    }

    private fun movementEvidence(start: Long, current: Point): Boolean {
        if (current.motion || hasSteps(start, current.time)) return true
        // A lift can continue at a steady speed without steps or fresh acceleration.
        val earlier = history.firstOrNull { it.time >= current.time - TREND_WINDOW_MS } ?: return false
        val span = current.time - earlier.time
        return span >= TREND_MIN_MS && abs(current.rawAltitude - earlier.rawAltitude) / (span / 1000.0) >= CONTINUOUS_VERTICAL_SPEED
    }

    private fun finishedEvent(active: Journey) = Event(
        phase(active.direction, false),
        active.extreme.altitude - active.start.altitude,
        active.extreme.time,
        active.evidence,
    )

    private fun phase(direction: Int, start: Boolean) =
        (if (direction > 0) "ascent" else "descent") + (if (start) "_started" else "_finished")

    companion object {
        private const val MAX_GAP_MS = 12_000L
        private const val HISTORY_MS = 60_000L
        private const val MAX_STEPS = 512
        private const val MIN_ANALYSIS_INTERVAL_MS = 500L
        private const val SMOOTHING_TIME_MS = 1442.6950408889634 // alpha 0.5 at one second
        private const val START_MIN_MS = 4_000L
        private const val START_METERS = 1.8
        private const val BASELINE_TOLERANCE_METERS = 0.25
        private const val MIN_SPEED_METERS_PER_SECOND = 0.08
        private const val MIN_STEPPING_SPEED = 0.035
        private const val MAX_SPEED_METERS_PER_SECOND = 8.0
        private const val PROGRESS_METERS = 0.2
        private const val SETTLE_MS = 8_000L
        private const val REVERSAL_METERS = 1.5
        private const val REVERSAL_MS = 3_000L
        private const val RECENT_STEP_MS = 5_000L
        private const val MIN_STEPS = 4
        private const val MIN_STEP_SPAN_MS = 2_000L
        private const val TREND_WINDOW_MS = 8_000L
        private const val TREND_MIN_MS = 3_000L
        private const val RAW_PROGRESS_METERS = 0.01
        private const val MIN_TREND_CONSISTENCY = 0.7
        private const val MAX_SINGLE_JUMP_SHARE = 0.6
        private const val CONTINUOUS_VERTICAL_SPEED = 0.5
    }
}
