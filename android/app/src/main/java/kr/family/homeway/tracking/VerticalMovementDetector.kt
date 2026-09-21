package kr.family.homeway.tracking

import kotlin.math.pow

/**
 * Relative barometric height only, never a floor number. Timestamps use the sensor's monotonic
 * clock so batching and network latency do not become the child's reported movement times.
 * Thresholds are conservative starting values and require testing in the actual buildings.
 */
class VerticalMovementDetector {
    data class Event(val phase: String, val relativeMeters: Double, val measuredAtMillis: Long)
    private data class Point(val time: Long, val altitude: Double, val motion: Boolean)
    private data class Journey(val direction: Int, val start: Point, var extreme: Point)

    private val recentPressure = ArrayDeque<Double>()
    private val history = ArrayDeque<Point>()
    private var filteredAltitude: Double? = null
    private var previousTime: Long? = null
    private var journey: Journey? = null
    val hasActiveMovement: Boolean get() = journey != null

    fun reset() {
        recentPressure.clear()
        history.clear()
        filteredAltitude = null
        previousTime = null
        journey = null
    }

    fun addPressure(pressureHpa: Double, timestampMillis: Long, recentPhysicalMotion: Boolean): List<Event> {
        if (!pressureHpa.isFinite() || pressureHpa !in 300.0..1100.0) return emptyList()
        previousTime?.let { previous ->
            if (timestampMillis <= previous) return emptyList()
            // A suspended or restarted sensor must not bridge two unrelated journeys.
            if (timestampMillis - previous > MAX_GAP_MS) reset()
        }
        previousTime = timestampMillis
        recentPressure.addLast(pressureHpa)
        while (recentPressure.size > 3) recentPressure.removeFirst()
        val median = recentPressure.sorted()[recentPressure.size / 2]
        val rawAltitude = 44330.0 * (1.0 - (median / 1013.25).pow(0.19029495))
        val smoothed = filteredAltitude?.let { it + 0.5 * (rawAltitude - it) } ?: rawAltitude
        filteredAltitude = smoothed
        val point = Point(timestampMillis, smoothed, recentPhysicalMotion)
        history.addLast(point)
        while (history.isNotEmpty() && timestampMillis - history.first().time > HISTORY_MS) history.removeFirst()

        val active = journey
        if (active == null) {
            val candidate = candidateStart(point) ?: return emptyList()
            journey = Journey(candidate.first, candidate.second, point)
            return listOf(Event(phase(candidate.first, true), 0.0, candidate.second.time))
        }

        val advance = active.direction * (point.altitude - active.extreme.altitude)
        if (advance > PROGRESS_METERS) active.extreme = point
        val reverseDistance = active.direction * (active.extreme.altitude - point.altitude)
        val sinceExtreme = point.time - active.extreme.time
        if (reverseDistance >= REVERSAL_METERS && sinceExtreme >= REVERSAL_MS) {
            // Close the prior movement before reporting the new direction, at the same pivot.
            val finished = finishedEvent(active)
            val direction = -active.direction
            journey = Journey(direction, active.extreme, point)
            history.clear()
            history.addLast(active.extreme)
            history.addLast(point)
            return listOf(finished, Event(phase(direction, true), 0.0, active.extreme.time))
        }
        if (sinceExtreme >= SETTLE_MS && reverseDistance < REVERSAL_METERS) {
            journey = null
            history.clear()
            history.addLast(point)
            return listOf(finishedEvent(active))
        }
        return emptyList()
    }

    private fun candidateStart(current: Point): Pair<Int, Point>? {
        if (history.size < 5 || history.none { it.motion }) return null
        val lowest = history.minOf { it.altitude }
        val highest = history.maxOf { it.altitude }
        // Last point near the baseline avoids counting an earlier stationary period as movement.
        val upStart = history.lastOrNull { it.altitude <= lowest + BASELINE_TOLERANCE_METERS }
        val downStart = history.lastOrNull { it.altitude >= highest - BASELINE_TOLERANCE_METERS }
        val candidates = listOfNotNull(upStart?.let { 1 to it }, downStart?.let { -1 to it })
        return candidates.firstOrNull { (direction, start) ->
            val duration = current.time - start.time
            val distance = direction * (current.altitude - start.altitude)
            duration >= START_MIN_MS && distance >= START_METERS &&
                distance / (duration / 1000.0) in MIN_SPEED_METERS_PER_SECOND..MAX_SPEED_METERS_PER_SECOND &&
                history.any { it.time >= start.time && it.motion }
        }
    }

    private fun finishedEvent(active: Journey) = Event(
        phase(active.direction, false),
        active.extreme.altitude - active.start.altitude,
        active.extreme.time,
    )

    private fun phase(direction: Int, start: Boolean) =
        (if (direction > 0) "ascent" else "descent") + (if (start) "_started" else "_finished")

    companion object {
        private const val MAX_GAP_MS = 12_000L
        private const val HISTORY_MS = 30_000L
        private const val START_MIN_MS = 4_000L
        private const val START_METERS = 1.8
        private const val BASELINE_TOLERANCE_METERS = 0.25
        private const val MIN_SPEED_METERS_PER_SECOND = 0.08
        private const val MAX_SPEED_METERS_PER_SECOND = 8.0
        private const val PROGRESS_METERS = 0.2
        private const val SETTLE_MS = 8_000L
        private const val REVERSAL_METERS = 1.5
        private const val REVERSAL_MS = 3_000L
    }
}
