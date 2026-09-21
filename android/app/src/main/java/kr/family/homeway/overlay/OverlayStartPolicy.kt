package kr.family.homeway.overlay

internal object OverlayStartPolicy {
    enum class Decision { START, STOP, CLEAR_CHOICE_AND_STOP }

    fun decide(enabled: Boolean, permission: Boolean, accountAvailable: Boolean): Decision = when {
        !enabled -> Decision.STOP
        !permission || !accountAvailable -> Decision.CLEAR_CHOICE_AND_STOP
        else -> Decision.START
    }
}

/** A window failure may recreate once, never loop after repeated add/detach failures. */
internal class OverlayWindowRecovery {
    private var attempted = false

    fun retryOnce(): Boolean {
        if (attempted) return false
        attempted = true
        return true
    }

    fun resetForVisibilityEvent() { attempted = false }
}
