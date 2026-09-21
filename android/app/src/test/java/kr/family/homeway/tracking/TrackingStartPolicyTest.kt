package kr.family.homeway.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingStartPolicyTest {
    @Test fun aStoppedChildNeverStartsJustBecauseTheProcessOrActivityReturns() {
        assertEquals(TrackingStartPolicy.Decision.STOP,
            TrackingStartPolicy.decide(false, configured = true, child = true, demo = false, permissions = true))
    }

    @Test fun savedConsentRemainsEligibleAfterAnUpdateWithoutGrantingNewConsent() {
        assertEquals(TrackingStartPolicy.Decision.START,
            TrackingStartPolicy.decide(true, configured = true, child = true, demo = false, permissions = true))
    }

    @Test fun savedConsentCannotCrossAnAccountRoleOrDemoBoundary() {
        for ((configured, child, demo) in listOf(Triple(false, true, false), Triple(true, false, false), Triple(true, true, true))) {
            assertEquals(TrackingStartPolicy.Decision.CLEAR_CONSENT_AND_STOP,
                TrackingStartPolicy.decide(true, configured, child, demo, permissions = true))
        }
    }

    @Test fun permissionRevocationInvalidatesPreviouslyEnabledSharing() {
        assertEquals(TrackingStartPolicy.Decision.CLEAR_CONSENT_AND_STOP,
            TrackingStartPolicy.decide(true, configured = true, child = true, demo = false, permissions = false))
    }
}
