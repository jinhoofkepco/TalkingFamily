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
    private val intervalMillis: Long = 5 * 60_000L,
    private val toleranceMillis: Long = 30_000L,
) {
    init { require(intervalMillis > 0 && toleranceMillis in 0 until intervalMillis) }

    private data class Reservation(val sample: LocationFixSample, val receivedAt: Long)
    private var pending: Reservation? = null
    private var lastFixMillis: Long? = null
    private var lastReceivedAt: Long? = null
    private var lastWatchdogAt: Long? = null
    var nextScheduledAtMillis: Long = startedAtMillis
        private set

    /** A reservation remains pending until SQLite commits, so a failed write cannot claim a record. */
    fun reserve(sample: LocationFixSample, receivedAtMillis: Long): Boolean {
        if (LocationFixValidation.error(sample, receivedAtMillis) != null || pending != null) return false
        if (lastFixMillis?.let { sample.elapsedRealtimeMillis <= it } == true) return false
        if (receivedAtMillis < nextScheduledAtMillis - toleranceMillis) return false
        // A late recovery must not suppress the next normal slot. Half an interval still prevents bursts.
        if (lastReceivedAt?.let { receivedAtMillis - it < intervalMillis / 2 } == true) return false
        pending = Reservation(sample, receivedAtMillis)
        return true
    }

    fun committed() {
        val saved = checkNotNull(pending) { "No pending automatic location" }
        lastFixMillis = saved.sample.elapsedRealtimeMillis
        lastReceivedAt = saved.receivedAt
        // Retain the original schedule, skip missed slots, and never manufacture catch-up records.
        val slots = ((saved.receivedAt + toleranceMillis - nextScheduledAtMillis) / intervalMillis) + 1
        nextScheduledAtMillis += slots * intervalMillis
        pending = null
    }

    fun cancelReservation() { pending = null }

    fun awaitingFreshFix(nowMillis: Long): Boolean = pending == null && nowMillis >= nextScheduledAtMillis + 60_000L

    /** Awake-only recovery, at most one fresh request per five minutes; failure consumes no slot. */
    fun beginWatchdog(nowMillis: Long): Boolean {
        if (!awaitingFreshFix(nowMillis)) return false
        if (lastWatchdogAt?.let { nowMillis - it < intervalMillis } == true) return false
        if (lastReceivedAt?.let { nowMillis - it < intervalMillis / 2 } == true) return false
        lastWatchdogAt = nowMillis
        return true
    }
}
