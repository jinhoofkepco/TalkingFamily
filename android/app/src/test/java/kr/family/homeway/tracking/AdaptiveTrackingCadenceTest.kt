package kr.family.homeway.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveTrackingCadenceTest {
    private fun idle(cadence: AdaptiveTrackingCadence, now: Long) =
        assertEquals(300_000L, cadence.intervalMillis(now))
    private fun moving(cadence: AdaptiveTrackingCadence, now: Long) =
        assertEquals(20_000L, cadence.intervalMillis(now))

    @Test fun noActivityOrOnlyStillAndExitEvidenceNeverInventsMovement() {
        val cadence = AdaptiveTrackingCadence()
        idle(cadence, 0)
        cadence.onActivity(MotionState.STILL, 1_000, true, 1_000)
        idle(cadence, 1_000)
        cadence.onActivity(MotionState.UNKNOWN, 2_000, false, 2_000)
        idle(cadence, 2_000)
        idle(cadence, 12 * 60 * 60_000L)
    }

    @Test fun everyRecognizedMovingActivityStartsImmediatelyButCannotRemainFastOvernight() {
        for (state in listOf(MotionState.WALKING, MotionState.RUNNING, MotionState.BICYCLE, MotionState.VEHICLE)) {
            val cadence = AdaptiveTrackingCadence()
            cadence.onActivity(state, 1_000, true, 1_000)
            moving(cadence, 1_000)
            moving(cadence, 360_999)
            idle(cadence, 361_000)
            idle(cadence, 12 * 60 * 60_000L)
        }
    }

    @Test fun periodicFiveMinuteActivitySamplesRenewSmoothVehicleTravelWithoutAccelerometerMotion() {
        val cadence = AdaptiveTrackingCadence()
        cadence.onActivity(MotionState.VEHICLE, 0, true, 0)
        for (at in listOf(300_000L, 600_000L, 900_000L)) {
            moving(cadence, at)
            cadence.onActivity(MotionState.VEHICLE, at, false, at)
        }
        moving(cadence, 1_259_999)
        idle(cadence, 1_260_000)
    }

    @Test fun positiveStillShortensMovingLeaseAfterTwoMinutesAndRepeatedStillDoesNotExtendIt() {
        val cadence = AdaptiveTrackingCadence()
        cadence.onActivity(MotionState.WALKING, 0, true, 0)
        cadence.onActivity(MotionState.STILL, 10_000, true, 10_000)
        moving(cadence, 10_000)
        cadence.onActivity(MotionState.STILL, 100_000, false, 100_000)
        moving(cadence, 129_999)
        idle(cadence, 130_000)
        cadence.onActivity(MotionState.STILL, 300_000, true, 300_000)
        idle(cadence, 300_000)
    }

    @Test fun exitKeepsARecoveryWindowAndNewMovingEnterAtSameTimestampWins() {
        val cadence = AdaptiveTrackingCadence()
        cadence.onActivity(MotionState.VEHICLE, 0, true, 0)
        cadence.onActivity(MotionState.UNKNOWN, 10_000, false, 10_000)
        moving(cadence, 10_000)
        cadence.onActivity(MotionState.WALKING, 10_000, true, 10_000)
        moving(cadence, 130_000)
        idle(cadence, 370_000)
    }

    @Test fun activityExitAloneReturnsToIdleAfterTwoMinutesWithoutClaimingStillness() {
        val cadence = AdaptiveTrackingCadence()
        cadence.onActivity(MotionState.RUNNING, 0, true, 0)
        cadence.onActivity(MotionState.UNKNOWN, 10_000, false, 10_000)
        moving(cadence, 129_999)
        idle(cadence, 130_000)
    }

    @Test fun simultaneousWeakStillSampleCannotEraseTheMovingEnter() {
        val cadence = AdaptiveTrackingCadence()
        cadence.onActivity(MotionState.VEHICLE, 1_000, true, 1_000)
        cadence.onActivity(MotionState.STILL, 1_000, false, 1_000)
        moving(cadence, 200_000)
        idle(cadence, 361_000)
    }

    @Test fun stepAndSignificantMotionWorkWithoutActivityPermissionAndKeepOnlyBoundedLeases() {
        val byStep = AdaptiveTrackingCadence()
        byStep.onStep(1_000, 1_000)
        moving(byStep, 1_000)
        moving(byStep, 120_999)
        idle(byStep, 121_000)
        val byTrigger = AdaptiveTrackingCadence()
        byTrigger.onSignificantMotion(1_000, 1_000)
        moving(byTrigger, 1_000)
        idle(byTrigger, 121_000)
    }

    @Test fun recentPhysicalMotionOutlivesAnEarlierStillObservation() {
        val cadence = AdaptiveTrackingCadence()
        cadence.onActivity(MotionState.WALKING, 0, true, 0)
        cadence.onActivity(MotionState.STILL, 10_000, true, 10_000)
        cadence.onStep(100_000, 100_000)
        moving(cadence, 130_000)
        moving(cadence, 219_999)
        idle(cadence, 220_000)
    }

    @Test fun singlePickupAndDenseSubSecondShakeNeverStartFastTracking() {
        val cadence = AdaptiveTrackingCadence()
        for (at in listOf(1_000L, 1_000L, 1_001L, 1_200L, 1_400L, 1_600L, 1_800L)) {
            cadence.onAccelerationMotion(at, at)
            idle(cadence, at)
        }
        idle(cadence, 60_000)
        cadence.onAccelerationMotion(60_001, 60_001)
        idle(cadence, 60_001)
        idle(cadence, 12 * 60 * 60_000L)
    }

    @Test fun independentAccelerationBurstsMustPersistForTwoSecondsBeforeStartingFastTracking() {
        val cadence = AdaptiveTrackingCadence()
        cadence.onAccelerationMotion(1_000, 1_000)
        cadence.onAccelerationMotion(2_000, 2_000)
        idle(cadence, 2_999)
        cadence.onAccelerationMotion(3_000, 3_000)
        moving(cadence, 3_000)
        moving(cadence, 122_999)
        idle(cadence, 123_000)
    }

    @Test fun isolatedBurstsOutsideConfirmationWindowCannotAccumulateIntoFalseMovement() {
        val cadence = AdaptiveTrackingCadence()
        for (at in listOf(0L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L)) {
            cadence.onAccelerationMotion(at, at)
            idle(cadence, at)
        }
    }

    @Test fun sustainedAccelerationRenewsItsLeaseAndStopsSoonAfterPhysicalEvidenceEnds() {
        val cadence = AdaptiveTrackingCadence()
        for (at in 0L..30_000L step 1_000L) cadence.onAccelerationMotion(at, at)
        moving(cadence, 149_999)
        idle(cadence, 150_000)
    }

    @Test fun staleFutureNegativeAndOutOfOrderObservationsCannotStartOrExtendFastTracking() {
        val cadence = AdaptiveTrackingCadence()
        cadence.onActivity(MotionState.RUNNING, 0, true, 30_001)
        cadence.onStep(0, 30_001)
        cadence.onSignificantMotion(0, 30_001)
        cadence.onAccelerationMotion(0, 30_001)
        cadence.onStep(30_002, 30_001)
        cadence.onSignificantMotion(-1, 30_001)
        idle(cadence, 30_001)
        cadence.onActivity(MotionState.WALKING, 31_000, true, 31_000)
        cadence.onActivity(MotionState.STILL, 32_000, true, 32_000)
        cadence.onActivity(MotionState.WALKING, 31_999, true, 40_000)
        moving(cadence, 151_999)
        idle(cadence, 152_000)
    }

    @Test fun batchedDeliveryUsesObservationTimeAndDuplicateEventsNeverRenewTheirLease() {
        val cadence = AdaptiveTrackingCadence()
        cadence.onStep(10_000, 40_000)
        moving(cadence, 40_000)
        cadence.onStep(10_000, 40_000)
        moving(cadence, 129_999)
        idle(cadence, 130_000)
        cadence.onStep(10_000, 130_000)
        idle(cadence, 130_000)
    }

    @Test fun delayedAccelerationBatchMustContainMultipleDistinctObservationsOverTime() {
        val cadence = AdaptiveTrackingCadence()
        repeat(20) { cadence.onAccelerationMotion(10_000, 15_000) }
        idle(cadence, 15_000)
        cadence.onAccelerationMotion(11_000, 15_000)
        cadence.onAccelerationMotion(12_000, 15_000)
        moving(cadence, 15_000)
        idle(cadence, 132_000)
    }

    @Test fun clockRegressionCannotResurrectAnExpiredLease() {
        val cadence = AdaptiveTrackingCadence()
        cadence.onStep(10_000, 10_000)
        idle(cadence, 130_000)
        idle(cadence, 10_001)
        cadence.onStep(10_002, 10_002)
        idle(cadence, 130_001)
    }
}
