package kr.family.homeway.overlay

internal object OverlayStartPolicy {
    enum class Decision { START, STOP, CLEAR_CHOICE_AND_STOP }

    fun decide(enabled: Boolean, permission: Boolean, accountAvailable: Boolean): Decision = when {
        !enabled -> Decision.STOP
        !permission -> Decision.CLEAR_CHOICE_AND_STOP
        // Explicit disconnect already turns the shortcut off. Temporary account unavailability
        // must never rewrite an enabled preference as a deliberate user choice.
        !accountAvailable -> Decision.STOP
        else -> Decision.START
    }

    fun accountAvailable(demoMode: Boolean, telegramTransport: Boolean, peerConfigured: Boolean,
        storedToken: Boolean, storedIv: Boolean): Boolean =
        demoMode || (telegramTransport && peerConfigured && storedToken && storedIv)
}

/** A short bounded recovery burst, then wait for a real visibility event without stopping the FGS. */
internal class OverlayWindowRecovery {
    var attemptsUsed = 0
        private set

    fun nextDelayMillis(): Long? {
        val delay = DELAYS_MILLIS.getOrNull(attemptsUsed) ?: return null
        attemptsUsed += 1
        return delay
    }

    fun resetForVisibilityEvent() { attemptsUsed = 0 }

    private companion object {
        val DELAYS_MILLIS = longArrayOf(300L, 1_500L, 5_000L)
    }
}

/** Recheck only briefly after a screen/unlock event; keyguard state may lag the broadcast. */
internal class OverlayUnlockRecovery {
    var attemptsUsed = 0
        private set
    var active = false
        private set

    fun begin() {
        attemptsUsed = 0
        active = true
    }

    fun nextDelayMillis(): Long? {
        if (!active) return null
        val delay = DELAYS_MILLIS.getOrNull(attemptsUsed)
        if (delay == null) active = false else attemptsUsed += 1
        return delay
    }

    fun cancel() { active = false }

    private companion object {
        // Relative delays give checks at 300 ms, 1.5 s and 5 s after the latest screen event.
        val DELAYS_MILLIS = longArrayOf(300L, 1_200L, 3_500L)
    }
}
