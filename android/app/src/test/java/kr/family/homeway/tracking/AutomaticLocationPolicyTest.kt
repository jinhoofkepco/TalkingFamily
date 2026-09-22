package kr.family.homeway.tracking

import org.junit.Assert.*
import org.junit.Test

class AutomaticLocationPolicyTest {
    private fun fix(elapsed: Long) = LocationFixSample(37.5, 127.0, 10.0, 1_700_000_000_000L + elapsed, elapsed)
    private fun save(policy: AutomaticLocationPolicy, elapsed: Long) {
        assertTrue(policy.reserve(fix(elapsed), elapsed))
        policy.committed()
    }

    @Test fun motionEntryRebasesFiveMinuteSlotAndAcceptsThirtySecondFixes() {
        val policy = AutomaticLocationPolicy(0)
        save(policy, 0)
        assertTrue(policy.updateInterval(30_000, 10_000))
        assertTrue(policy.beginWatchdog(10_000))
        save(policy, 11_000)
        save(policy, 40_000)
        save(policy, 69_000)
        assertEquals(100_000L, policy.nextScheduledAtMillis)
        assertFalse(policy.updateInterval(30_000, 75_000))
        assertEquals(100_000L, policy.nextScheduledAtMillis)
    }

    @Test fun idleReturnSchedulesFromLastWriteWithoutImmediateBurst() {
        val policy = AutomaticLocationPolicy(0, 30_000, 3_000)
        save(policy, 0)
        save(policy, 30_000)
        policy.updateInterval(300_000, 60_000)
        assertEquals(330_000L, policy.nextScheduledAtMillis)
        assertFalse(policy.reserve(fix(60_000), 60_000))
        assertFalse(policy.beginWatchdog(120_000))
        save(policy, 330_000)
    }

    @Test fun pendingIdleWriteCannotConsumeNewMovingSlot() {
        val policy = AutomaticLocationPolicy(0)
        assertTrue(policy.reserve(fix(0), 0))
        policy.updateInterval(30_000, 10_000)
        assertFalse(policy.beginWatchdog(10_000))
        policy.committed()
        assertEquals(10_000L, policy.nextScheduledAtMillis)
        assertTrue(policy.beginWatchdog(10_000))
        save(policy, 11_000)
        assertEquals(40_000L, policy.nextScheduledAtMillis)
    }

    @Test fun pendingMovingWriteCannotAdvanceIdleSlotByFiveMinutesTwice() {
        val policy = AutomaticLocationPolicy(0)
        policy.updateInterval(30_000, 0)
        assertTrue(policy.reserve(fix(0), 0))
        policy.updateInterval(300_000, 10_000)
        policy.committed()
        assertEquals(300_000L, policy.nextScheduledAtMillis)
        save(policy, 300_000)
    }

    @Test fun freshMotionRequestDoesNotBurstAfterJustCommittedLocation() {
        val policy = AutomaticLocationPolicy(0)
        save(policy, 0)
        policy.updateInterval(30_000, 1_000)
        assertFalse(policy.beginWatchdog(1_000))
        assertFalse(policy.reserve(fix(1_000), 1_000))
        assertTrue(policy.beginWatchdog(5_000))
        assertFalse(policy.beginWatchdog(10_000))
        assertFalse(policy.beginWatchdog(34_999))
        assertTrue(policy.beginWatchdog(35_000))
        save(policy, 36_000)
        assertFalse(policy.reserve(fix(36_000), 36_001))
    }

    @Test fun cancelledWriteAfterCadenceChangeLeavesMotionSlotAvailable() {
        val policy = AutomaticLocationPolicy(0)
        assertTrue(policy.reserve(fix(0), 0))
        policy.updateInterval(30_000, 10_000)
        policy.cancelReservation()
        save(policy, 10_000)
        assertEquals(40_000L, policy.nextScheduledAtMillis)
    }

    @Test fun stationaryInitialFixAndEveryFiveMinuteFixAreAccepted() {
        val policy = AutomaticLocationPolicy(0)
        save(policy, 0)
        save(policy, 300_000)
        save(policy, 600_000)
        assertEquals(900_000L, policy.nextScheduledAtMillis)
    }

    @Test fun oneSecondInitialOffsetDoesNotDrop299SecondScheduledCallback() {
        val policy = AutomaticLocationPolicy(0)
        save(policy, 1_000)
        save(policy, 300_000) // 299 seconds after the first fix.
        assertFalse(policy.reserve(fix(300_000), 300_000))
        assertFalse(policy.reserve(fix(300_001), 300_001))
        save(policy, 599_000)
        assertEquals(900_000L, policy.nextScheduledAtMillis)
    }

    @Test fun pendingWriteBlocksOverlapAndAdvancesScheduleOnlyWhenDurablyCommitted() {
        val policy = AutomaticLocationPolicy(0)
        assertTrue(policy.reserve(fix(0), 0))
        assertFalse(policy.reserve(fix(300_000), 300_000))
        assertEquals(0L, policy.nextScheduledAtMillis)
        assertFalse(policy.beginWatchdog(600_000))
        policy.committed()
        assertEquals(300_000L, policy.nextScheduledAtMillis)
        save(policy, 600_000)
    }

    @Test fun lateInitialWatchdogRecoveryDoesNotSuppressNextScheduledFix() {
        val policy = AutomaticLocationPolicy(0)
        assertFalse(policy.beginWatchdog(59_999))
        assertTrue(policy.beginWatchdog(60_000))
        save(policy, 70_000)
        assertFalse(policy.reserve(fix(80_000), 80_000)) // Subscription overlaps recovery.
        save(policy, 300_000)
        assertEquals(600_000L, policy.nextScheduledAtMillis)
    }

    @Test fun watchdogFailureLeavesNextCallbackEligibleAndRequestsRemainBounded() {
        val policy = AutomaticLocationPolicy(0)
        assertTrue(policy.beginWatchdog(60_000))
        assertFalse(policy.beginWatchdog(90_000))
        assertFalse(policy.beginWatchdog(359_999))
        assertTrue(policy.beginWatchdog(360_000))
        save(policy, 365_000)
        assertFalse(policy.awaitingFreshFix(365_001)) // A losing fallback must not report failure now.
        assertFalse(policy.beginWatchdog(400_000))
        save(policy, 600_000)
    }

    @Test fun delayedCallbacksSkipMissedSlotsWithoutDriftOrCatchupBursts() {
        val policy = AutomaticLocationPolicy(0)
        save(policy, 0)
        save(policy, 970_000)
        assertEquals(1_200_000L, policy.nextScheduledAtMillis)
        assertFalse(policy.reserve(fix(970_001), 970_001))
        save(policy, 1_200_000)
        save(policy, 1_499_000)
        assertEquals(1_800_000L, policy.nextScheduledAtMillis)
    }

    @Test fun lateFixNearBoundaryCannotCreateTwoRecordsInSeconds() {
        val policy = AutomaticLocationPolicy(0)
        save(policy, 0)
        save(policy, 565_000)
        assertEquals(600_000L, policy.nextScheduledAtMillis)
        assertFalse(policy.reserve(fix(600_000), 600_000))
        save(policy, 900_000)
    }

    @Test fun duplicateOrOlderMeasurementCannotBeRelabeledAsANewFix() {
        val policy = AutomaticLocationPolicy(0)
        save(policy, 0)
        assertFalse(policy.reserve(fix(0).copy(capturedAtMillis = 1_700_000_300_000L), 300_000))
        save(policy, 300_000)
        assertFalse(policy.reserve(fix(299_999), 300_001))
    }

    @Test fun validationRejectsStaleFutureAndInaccurateMeasurementsWithoutConsumingSlot() {
        val policy = AutomaticLocationPolicy(0)
        val invalid = listOf(fix(0), fix(20_002), fix(20_001).copy(latitude = Double.NaN),
            fix(20_001).copy(longitude = 181.0), fix(20_001).copy(capturedAtMillis = 0),
            fix(20_001).copy(accuracyMeters = null), fix(20_001).copy(accuracyMeters = 150.01),
            fix(20_001).copy(accuracyMeters = Double.NaN), fix(20_001).copy(accuracyMeters = -1.0))
        invalid.forEach { assertFalse(policy.reserve(it, 20_001)) }
        val original = fix(20_001).copy(accuracyMeters = 150.0)
        assertNull(LocationFixValidation.error(original, 40_001))
        assertTrue(policy.reserve(original, 20_001))
        assertEquals(1_700_000_020_001L, original.capturedAtMillis)
    }
}
