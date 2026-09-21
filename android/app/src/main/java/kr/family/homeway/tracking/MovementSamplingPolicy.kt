package kr.family.homeway.tracking

/** Monotonic-clock policy. A step remains relevant even if the child stops before the deadline. */
class MovementSamplingPolicy(
    startedAtMillis: Long,
    private val intervalMillis: Long = 5 * 60_000L,
) {
    init { require(intervalMillis > 0) }

    private var nextDeadline = startedAtMillis + intervalMillis
    var movementPending: Boolean = false
        private set

    fun noteMovement() { movementPending = true }

    /** Consumes at most one opportunity; delayed delivery never causes a burst of GPS requests. */
    fun consumeDue(nowMillis: Long): Boolean {
        if (nowMillis < nextDeadline) return false
        nextDeadline = nowMillis + intervalMillis
        return movementPending.also { movementPending = false }
    }

    /** A failed fix may be retried at the next five-minute opportunity, never in a tight loop. */
    fun retryNextInterval() { movementPending = true }
}
