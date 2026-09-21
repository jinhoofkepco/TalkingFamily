package kr.family.homeway.tracking

/** The same consent boundary applies to a button, a visible Activity and an OS restart. */
internal object TrackingStartPolicy {
    enum class Decision { START, STOP, CLEAR_CONSENT_AND_STOP }

    fun decide(savedConsent: Boolean, configured: Boolean, child: Boolean, demo: Boolean, permissions: Boolean): Decision = when {
        !savedConsent -> Decision.STOP
        !configured || !child || demo || !permissions -> Decision.CLEAR_CONSENT_AND_STOP
        else -> Decision.START
    }
}
