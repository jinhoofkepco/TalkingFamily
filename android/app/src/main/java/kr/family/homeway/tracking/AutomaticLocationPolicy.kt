package kr.family.homeway.tracking

/** Original provider timestamps are retained; elapsed time controls freshness and duplicate checks. */
data class LocationFixSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val capturedAtMillis: Long,
    val elapsedRealtimeMillis: Long,
)

object LocationFixValidation {
    fun error(sample: LocationFixSample, nowElapsedMillis: Long): String? = when {
        !sample.latitude.isFinite() || sample.latitude !in -90.0..90.0 ||
            !sample.longitude.isFinite() || sample.longitude !in -180.0..180.0 || sample.capturedAtMillis <= 0 ->
            "현재 위치 정보를 확인하지 못했어요. 새 위치를 기다리고 있어요."
        sample.elapsedRealtimeMillis < 0 || nowElapsedMillis - sample.elapsedRealtimeMillis !in 0..20_000L ->
            "새 위치를 확인하지 못했어요. 이전 위치는 보내지 않았어요."
        sample.accuracyMeters == null || !sample.accuracyMeters.isFinite() || sample.accuracyMeters !in 0.0..150.0 ->
            "위치 오차가 커서 보내지 못했어요. 새 위치를 기다리고 있어요."
        else -> null
    }
}

/** FLP supplies fixes, independent of motion. This only limits duplicates, overlap and catch-up bursts. */
class AutomaticLocationPolicy(
    private val startedAtMillis: Long,
    intervalMillis: Long = 5 * 60_000L,
    private val toleranceMillis: Long = 30_000L,
) {
    init { require(intervalMillis > 0 && toleranceMillis in 0 until intervalMillis) }

    var intervalMillis: Long = intervalMillis
        private set
    private val tolerance get() = minOf(toleranceMillis, intervalMillis / 10)
    private var generation = 0L
    private var motionFixDue = false
    private data class Reservation(val sample: LocationFixSample, val receivedAt: Long, val generation: Long)
    private var pending: Reservation? = null
    private var lastFixMillis: Long? = null
    private var lastReceivedAt: Long? = null
    private var lastWatchdogAt: Long? = null
    var nextScheduledAtMillis: Long = startedAtMillis
        private set

    /** Sensor evidence changes cadence; an outstanding durable write keeps its reservation. */
    fun updateInterval(newIntervalMillis: Long, nowMillis: Long): Boolean {
        require(newIntervalMillis > 0)
        if (newIntervalMillis == intervalMillis) return false
        val faster = newIntervalMillis < intervalMillis
        intervalMillis = newIntervalMillis
        generation++
        motionFixDue = faster
        lastWatchdogAt = null
        nextScheduledAtMillis = if (faster) nowMillis else
            (pending?.receivedAt ?: lastReceivedAt)?.plus(newIntervalMillis) ?: nowMillis
        return true
    }

    /** A reservation remains pending until SQLite commits, so a failed write cannot claim a record. */
    fun reserve(sample: LocationFixSample, receivedAtMillis: Long): Boolean {
        if (LocationFixValidation.error(sample, receivedAtMillis) != null || pending != null) return false
        if (lastFixMillis?.let { sample.elapsedRealtimeMillis <= it } == true) return false
        if (receivedAtMillis < nextScheduledAtMillis - tolerance) return false
        // A late recovery must not suppress the next normal slot. Half an interval still prevents bursts.
        val spacing = if (motionFixDue) minOf(5_000L, intervalMillis / 2) else intervalMillis / 2
        if (lastReceivedAt?.let { receivedAtMillis - it < spacing } == true) return false
        pending = Reservation(sample, receivedAtMillis, generation)
        return true
    }

    fun committed() {
        val saved = checkNotNull(pending) { "No pending automatic location" }
        lastFixMillis = saved.sample.elapsedRealtimeMillis
        lastReceivedAt = saved.receivedAt
        // Retain the original schedule, skip missed slots, and never manufacture catch-up records.
        if (saved.generation == generation) {
            val slots = ((saved.receivedAt + tolerance - nextScheduledAtMillis) / intervalMillis) + 1
            nextScheduledAtMillis += slots.coerceAtLeast(1) * intervalMillis
            motionFixDue = false
        } // An old-cadence write must not consume a new-cadence slot.
        pending = null
    }

    fun cancelReservation() { pending = null }

    fun awaitingFreshFix(nowMillis: Long): Boolean = pending == null &&
        nowMillis >= nextScheduledAtMillis + if (motionFixDue) 0 else minOf(60_000L, intervalMillis)

    /** Fresh fix on motion entry, then bounded awake-only recovery; failure consumes no slot. */
    fun beginWatchdog(nowMillis: Long): Boolean {
        if (!awaitingFreshFix(nowMillis)) return false
        if (lastWatchdogAt?.let { nowMillis - it < intervalMillis } == true) return false
        val spacing = if (motionFixDue) minOf(5_000L, intervalMillis / 2) else intervalMillis / 2
        if (lastReceivedAt?.let { nowMillis - it < spacing } == true) return false
        lastWatchdogAt = nowMillis
        return true
    }
}
