package kr.family.homeway.tracking

/** Session-local validation of Google activity results; silence is never evidence of stillness. */
class ActivityMotionEvidence(private val sessionStartedAtMillis: Long) {
    data class Observation(val state: MotionState, val atElapsedMillis: Long, val persistentUntilExit: Boolean = false)

    private var lastObservedAtMillis = Long.MIN_VALUE
    private var currentState = MotionState.UNKNOWN
    private var activeTransitionState: MotionState? = null

    fun transition(state: MotionState, entering: Boolean, at: Long, now: Long): Observation? {
        if (state == MotionState.UNKNOWN || !validTime(at, now, allowStale = true)) return null
        // Google may report ENTER(new) and EXIT(old) at the same instant. An unrelated
        // EXIT must not erase a just-entered state; exiting STILL never implies walking.
        if (!entering && currentState != state && activeTransitionState != state) return null
        if (now - at > MAX_AGE_MILLIS) {
            // A delayed exit/movement can disprove latched stillness but cannot establish
            // a current activity. An old STILL entry cannot begin a new stationary episode.
            if (entering && state == MotionState.STILL) return null
            updateTransitionState(state, entering)
            return observe(MotionState.UNKNOWN, at)
        }
        updateTransitionState(state, entering)
        return observe(if (entering) state else MotionState.UNKNOWN, at, persistentUntilExit = entering)
    }

    fun sample(state: MotionState, confidence: Int, at: Long, now: Long): Observation? {
        if (confidence !in MIN_CONFIDENCE..100 || !validTime(at, now)) return null
        // Sampling can momentarily call a smoothly moving vehicle STILL. Only a transition
        // exit/new STILL entry can end an explicit moving episode; snapshots cannot erase it.
        if (state == MotionState.STILL && activeTransitionState?.let { it != MotionState.STILL } == true) return null
        return observe(state, at)
    }

    private fun updateTransitionState(state: MotionState, entering: Boolean) {
        if (entering) activeTransitionState = state
        else if (activeTransitionState == state) activeTransitionState = null
    }

    private fun validTime(at: Long, now: Long, allowStale: Boolean = false): Boolean {
        if (at < sessionStartedAtMillis || at > now || (!allowStale && now - at > MAX_AGE_MILLIS) ||
            at < lastObservedAtMillis) return false
        return true
    }

    private fun observe(state: MotionState, at: Long, persistentUntilExit: Boolean = false): Observation {
        lastObservedAtMillis = at
        currentState = state
        return Observation(state, at, persistentUntilExit)
    }

    companion object {
        const val MIN_CONFIDENCE = 75
        const val MAX_AGE_MILLIS = 15 * 60_000L
    }
}
