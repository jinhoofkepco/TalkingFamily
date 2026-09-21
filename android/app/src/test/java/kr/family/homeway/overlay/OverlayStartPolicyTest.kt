package kr.family.homeway.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStartPolicyTest {
    @Test fun anExplicitOffStaysOffEvenWhenTheAccountAndPermissionAreReady() {
        assertEquals(OverlayStartPolicy.Decision.STOP, OverlayStartPolicy.decide(false, true, true))
    }

    @Test fun aSavedEnabledSessionCanResumeAfterAnUnexpectedProcessLoss() {
        assertEquals(OverlayStartPolicy.Decision.START, OverlayStartPolicy.decide(true, true, true))
    }

    @Test fun permissionRevocationAndAccountRemovalInvalidateTheSavedSession() {
        assertEquals(OverlayStartPolicy.Decision.CLEAR_CHOICE_AND_STOP, OverlayStartPolicy.decide(true, false, true))
        assertEquals(OverlayStartPolicy.Decision.CLEAR_CHOICE_AND_STOP, OverlayStartPolicy.decide(true, true, false))
    }

    @Test fun repeatedWindowDetachCannotCreateAnUnboundedRecoveryLoop() {
        val recovery = OverlayWindowRecovery()
        assertTrue(recovery.retryOnce())
        repeat(10) { assertFalse(recovery.retryOnce()) }
        // A later real unlock/activity transition can try again without a timer/polling loop.
        recovery.resetForVisibilityEvent()
        assertTrue(recovery.retryOnce())
        assertFalse(recovery.retryOnce())
    }
}
