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

    @Test fun permissionRevocationInvalidatesButAccountUnavailabilityPreservesSavedChoice() {
        assertEquals(OverlayStartPolicy.Decision.CLEAR_CHOICE_AND_STOP, OverlayStartPolicy.decide(true, false, true))
        assertEquals(OverlayStartPolicy.Decision.STOP, OverlayStartPolicy.decide(true, true, false))
        // The unchanged enabled choice can resume when the account becomes available again.
        assertEquals(OverlayStartPolicy.Decision.START, OverlayStartPolicy.decide(true, true, true))
    }

    @Test fun repeatedWindowDetachUsesOnlyABoundedBurstThenCanRecoverOnANewVisibilityEvent() {
        val recovery = OverlayWindowRecovery()
        assertEquals(300L, recovery.nextDelayMillis())
        assertEquals(1_500L, recovery.nextDelayMillis())
        assertEquals(5_000L, recovery.nextDelayMillis())
        repeat(10) { assertEquals(null, recovery.nextDelayMillis()) }
        assertEquals(3, recovery.attemptsUsed)
        recovery.resetForVisibilityEvent()
        assertEquals(0, recovery.attemptsUsed)
        assertEquals(300L, recovery.nextDelayMillis())
    }

    @Test fun overlayEligibilityUsesStoredConfigurationWithoutNeedingADecryptedToken() {
        assertTrue(OverlayStartPolicy.accountAvailable(false, true, true, true, true))
        assertFalse(OverlayStartPolicy.accountAvailable(false, false, true, true, true))
        assertFalse(OverlayStartPolicy.accountAvailable(false, true, false, true, true))
        assertFalse(OverlayStartPolicy.accountAvailable(false, true, true, false, true))
        assertFalse(OverlayStartPolicy.accountAvailable(false, true, true, true, false))
        assertTrue(OverlayStartPolicy.accountAvailable(true, false, false, false, false))
    }

    @Test fun explicitDisconnectOrOffCannotBeResurrectedByRecovery() {
        assertEquals(OverlayStartPolicy.Decision.STOP, OverlayStartPolicy.decide(false, true, false))
        assertEquals(OverlayStartPolicy.Decision.STOP, OverlayStartPolicy.decide(false, true, true))
    }

    @Test fun unlockChecksStopAfterFiveSecondsAndDoNotBecomeContinuousPolling() {
        val recovery = OverlayUnlockRecovery()
        assertEquals(null, recovery.nextDelayMillis())
        recovery.begin()
        var elapsed = 0L
        val checkedAt = List(3) { elapsed += checkNotNull(recovery.nextDelayMillis()); elapsed }
        assertEquals(listOf(300L, 1_500L, 5_000L), checkedAt)
        assertEquals(3, recovery.attemptsUsed)
        repeat(20) { assertEquals(null, recovery.nextDelayMillis()) }
        assertFalse(recovery.active)
    }

    @Test fun screenOffMessengerOpenOrStopCanCancelAllRemainingUnlockChecks() {
        val recovery = OverlayUnlockRecovery()
        recovery.begin()
        assertEquals(300L, recovery.nextDelayMillis())
        recovery.cancel()
        assertFalse(recovery.active)
        repeat(5) { assertEquals(null, recovery.nextDelayMillis()) }
        recovery.begin() // A later actual screen event starts a fresh bounded burst.
        assertEquals(300L, recovery.nextDelayMillis())
        assertEquals(1, recovery.attemptsUsed)
    }

    @Test fun unlockChecksDoNotResetOrConsumeTheIndependentWindowFailureBudget() {
        val windows = OverlayWindowRecovery()
        val unlock = OverlayUnlockRecovery()
        repeat(3) { assertTrue(windows.nextDelayMillis() != null) }
        unlock.begin()
        repeat(3) { assertTrue(unlock.nextDelayMillis() != null) }
        assertEquals(null, windows.nextDelayMillis())
        assertEquals(3, windows.attemptsUsed)
        unlock.cancel()
        assertEquals(null, windows.nextDelayMillis())
    }
}
