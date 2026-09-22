package kr.family.homeway.tracking

import org.junit.Assert.*
import org.junit.Test

class StationaryLocationFilterTest {
    private fun fix(at: Long, northMeters: Double = 0.0, accuracy: Double = 10.0) = LocationFixSample(
        latitude = 37.5 + Math.toDegrees(northMeters / 6_371_000.0),
        longitude = 127.0,
        accuracyMeters = accuracy,
        capturedAtMillis = 1_700_000_000_000L + at,
        elapsedRealtimeMillis = at,
    )

    private fun established(): StationaryLocationFilter = StationaryLocationFilter().apply {
        updateMotion(MotionState.STILL, 0)
        filter(fix(0), 0)
        val held = filter(fix(300_000, 15.0), 300_000)
        assertEquals(0L, held.stationarySinceElapsedMillis)
        assertTrue(held.adjusted)
    }

    @Test fun noActivityEvidenceNeverTurnsRepeatedCoordinatesIntoStationaryEvidence() {
        val filter = StationaryLocationFilter()
        listOf(0L, 300_000L, 600_000L).forEach { at ->
            val raw = fix(at, at / 60_000.0)
            val result = filter.filter(raw, at)
            assertEquals(raw.latitude, result.displayLatitude, 0.0)
            assertEquals(MotionState.UNKNOWN, result.motionState)
            assertNull(result.stationarySinceElapsedMillis)
            assertFalse(result.adjusted)
        }
    }

    @Test fun stillNeedsTimeAndTwoPlausibleFixesAndDoesNotBackdateBeforeFirstLocation() {
        val filter = StationaryLocationFilter()
        filter.updateMotion(MotionState.STILL, 0)
        assertNull(filter.filter(fix(60_000), 60_000).stationarySinceElapsedMillis)
        assertNull(filter.filter(fix(120_000, 8.0), 120_000).stationarySinceElapsedMillis)
        val result = filter.filter(fix(180_000, 15.0), 180_000)
        assertEquals(60_000L, result.stationarySinceElapsedMillis)
        assertEquals(fix(60_000).latitude, result.displayLatitude, 0.0)
    }

    @Test fun rawProviderSampleAndEveryFiveMinuteRecordRemainAvailable() {
        val filter = StationaryLocationFilter()
        filter.updateMotion(MotionState.STILL, 0)
        val inputs = listOf(fix(0), fix(300_000, 20.0), fix(600_000, -10.0))
        val original = inputs.map { it.copy() }
        val outputs = inputs.map { filter.filter(it, it.elapsedRealtimeMillis) }
        assertEquals(3, outputs.size)
        assertEquals(original, inputs)
        assertEquals(listOf(0L, 300_000L, 600_000L), inputs.map { it.elapsedRealtimeMillis })
        assertEquals(inputs.first().latitude, outputs.last().displayLatitude, 0.0)
    }

    @Test fun fixedAnchorDoesNotWalkAlongAChainOfNearbyGpsFixes() {
        val filter = established()
        val held = filter.filter(fix(600_000, 50.0), 600_000)
        assertEquals(fix(0).latitude, held.displayLatitude, 0.0)
        val released = filter.filter(fix(900_000, 80.0), 900_000)
        assertEquals(fix(900_000, 80.0).latitude, released.displayLatitude, 0.0)
        assertEquals(MotionState.UNKNOWN, released.motionState)
        assertNull(released.stationarySinceElapsedMillis)
    }

    @Test fun oneNearbyOutlierKeepsAnchorButReportsLargerUncertaintyAndNextNearbyFixRecovers() {
        val filter = established()
        val outlier = filter.filter(fix(600_000, 150.0, 20.0), 600_000)
        assertEquals(fix(0).latitude, outlier.displayLatitude, 0.0)
        assertTrue(checkNotNull(outlier.displayAccuracyMeters) >= 170.0)
        assertEquals(0L, outlier.stationarySinceElapsedMillis)
        val recovered = filter.filter(fix(900_000, 5.0), 900_000)
        assertEquals(0L, recovered.stationarySinceElapsedMillis)
        assertTrue(checkNotNull(recovered.displayAccuracyMeters) < 30.0)
    }

    @Test fun displacementBeyond250MetersReleasesImmediatelyEvenWhenSensorSaysStill() {
        val filter = established()
        filter.updateMotion(MotionState.STILL, 600_000)
        val sample = fix(600_000, 251.0)
        val result = filter.filter(sample, 600_000)
        assertEquals(sample.latitude, result.displayLatitude, 0.0)
        assertEquals(MotionState.UNKNOWN, result.motionState)
        assertNull(result.stationarySinceElapsedMillis)
        assertFalse(result.adjusted)
        assertNull(filter.filter(fix(900_000, 251.0), 900_000).stationarySinceElapsedMillis)
    }

    @Test fun repeatedGpsDisplacementOverridesMisclassifiedStillAndRequiresNewEvidence() {
        val filter = established()
        filter.updateMotion(MotionState.STILL, 600_000)
        assertTrue(filter.filter(fix(600_000, 150.0), 600_000).adjusted)
        filter.updateMotion(MotionState.STILL, 900_000)
        val moving = filter.filter(fix(900_000, 200.0), 900_000)
        assertFalse(moving.adjusted)
        assertEquals(MotionState.UNKNOWN, moving.motionState)
        assertNull(filter.filter(fix(1_200_000, 200.0), 1_200_000).stationarySinceElapsedMillis)
        filter.updateMotion(MotionState.STILL, 1_500_000)
        assertNull(filter.filter(fix(1_500_000, 200.0), 1_500_000).stationarySinceElapsedMillis)
        val rested = filter.filter(fix(1_800_000, 210.0), 1_800_000)
        assertEquals(1_500_000L, rested.stationarySinceElapsedMillis)
    }

    @Test fun everyMovingStateAndUnknownImmediatelyReleaseTheAnchor() {
        listOf(MotionState.UNKNOWN, MotionState.WALKING, MotionState.RUNNING, MotionState.BICYCLE, MotionState.VEHICLE).forEach { state ->
            val filter = established()
            filter.updateMotion(state, 400_000)
            val sample = fix(600_000, 15.0)
            val result = filter.filter(sample, 600_000)
            assertEquals(state, result.motionState)
            assertEquals(sample.latitude, result.displayLatitude, 0.0)
            assertNull(result.stationarySinceElapsedMillis)
            assertFalse(result.adjusted)
        }
    }

    @Test fun physicalMovementReleasesStillAnchorAndUpdatesUnknownBarrierAgainstDelayedStill() {
        val filter = established()
        filter.notePhysicalMovement(350_000)
        val moving = filter.filter(fix(360_000, 20.0), 360_000)
        assertEquals(MotionState.UNKNOWN, moving.motionState)
        assertFalse(moving.adjusted)
        assertNull(moving.stationarySinceElapsedMillis)
        filter.notePhysicalMovement(400_000) // Already UNKNOWN: this newer step must still set a barrier.
        filter.updateMotion(MotionState.STILL, 375_000, persistentUntilExit = true)
        filter.updateMotion(MotionState.STILL, 400_000, persistentUntilExit = true)
        assertEquals(MotionState.UNKNOWN, filter.filter(fix(420_000, 30.0), 420_000).motionState)
        filter.updateMotion(MotionState.STILL, 450_000, persistentUntilExit = true)
        assertNull(filter.filter(fix(450_000, 30.0), 450_000).stationarySinceElapsedMillis)
        assertEquals(450_000L, filter.filter(fix(570_000, 35.0), 570_000).stationarySinceElapsedMillis)
    }

    @Test fun physicalSignalsPreserveMovingActivityButDoNotRenewItsAge() {
        for (state in listOf(MotionState.WALKING, MotionState.RUNNING, MotionState.BICYCLE, MotionState.VEHICLE)) {
            val filter = StationaryLocationFilter()
            filter.updateMotion(state, 0, persistentUntilExit = true)
            filter.filter(fix(0), 0)
            for (at in listOf(300_000L, 600_000L)) {
                filter.notePhysicalMovement(at)
                assertEquals(state, filter.filter(fix(at, 20.0), at).motionState)
            }
            filter.notePhysicalMovement(900_001)
            assertEquals(MotionState.UNKNOWN, filter.filter(fix(900_001, 30.0), 900_001).motionState)
        }
    }

    @Test fun futureAndOlderPhysicalEventsCannotEraseNewerStillEvidenceOrPoisonItsTimestamp() {
        val filter = established()
        filter.updateMotion(MotionState.STILL, 400_000, persistentUntilExit = true)
        filter.notePhysicalMovement(350_000)
        filter.notePhysicalMovement(Long.MAX_VALUE)
        val held = filter.filter(fix(600_000, 15.0), 600_000)
        assertEquals(MotionState.STILL, held.motionState)
        assertEquals(0L, held.stationarySinceElapsedMillis)
        filter.notePhysicalMovement(700_000)
        val released = filter.filter(fix(900_000, 20.0), 900_000)
        assertEquals(MotionState.UNKNOWN, released.motionState)
        assertNull(released.stationarySinceElapsedMillis)
    }

    @Test fun staleActivityReleasesAnchorButPeriodicStillRefreshKeepsOriginalDwell() {
        val stale = established()
        stale.filter(fix(600_000), 600_000)
        val missing = stale.filter(fix(900_001, 12.0), 900_001)
        assertEquals(MotionState.UNKNOWN, missing.motionState)
        assertNull(missing.stationarySinceElapsedMillis)
        val refreshed = established()
        listOf(600_000L, 900_000L, 1_200_000L, 1_500_000L).forEach { at ->
            refreshed.updateMotion(MotionState.STILL, at)
            assertEquals(0L, refreshed.filter(fix(at, 12.0), at).stationarySinceElapsedMillis)
        }
    }

    @Test fun positiveStillTransitionRemainsLatchedDuringLongQuietPeriodUntilExit() {
        val filter = StationaryLocationFilter()
        filter.updateMotion(MotionState.STILL, 0, persistentUntilExit = true)
        filter.filter(fix(0), 0)
        for (at in 300_000L..3_600_000L step 300_000L) {
            if (at == 600_000L) filter.updateMotion(MotionState.STILL, at) // Snapshot cannot remove the latch.
            assertEquals(0L, filter.filter(fix(at, 15.0), at).stationarySinceElapsedMillis)
        }
        filter.updateMotion(MotionState.UNKNOWN, 3_650_000)
        val exited = filter.filter(fix(3_900_000, 15.0), 3_900_000)
        assertEquals(MotionState.UNKNOWN, exited.motionState)
        assertNull(exited.stationarySinceElapsedMillis)
        assertFalse(exited.adjusted)
    }

    @Test fun latchedStillDoesNotBridgeALongLocationGapOrIgnoreGpsContradiction() {
        val filter = StationaryLocationFilter()
        filter.updateMotion(MotionState.STILL, 0, persistentUntilExit = true)
        filter.filter(fix(0), 0)
        filter.filter(fix(300_000, 10.0), 300_000)
        assertNull(filter.filter(fix(1_200_000, 10.0), 1_200_000).stationarySinceElapsedMillis)
        assertEquals(1_200_000L, filter.filter(fix(1_500_000, 15.0), 1_500_000).stationarySinceElapsedMillis)
        val displaced = filter.filter(fix(1_800_000, 500.0), 1_800_000)
        assertEquals(MotionState.UNKNOWN, displaced.motionState)
        assertNull(displaced.stationarySinceElapsedMillis)
        assertNull(filter.filter(fix(2_100_000, 500.0), 2_100_000).stationarySinceElapsedMillis)
    }

    @Test fun aDelayedExitStillEndsLatchedDwellButStaleStillCannotStartOne() {
        val filter = StationaryLocationFilter()
        filter.updateMotion(MotionState.STILL, 0, persistentUntilExit = true)
        filter.filter(fix(0), 0)
        filter.filter(fix(300_000, 10.0), 300_000)
        filter.updateMotion(MotionState.UNKNOWN, 400_000)
        val exit = filter.filter(fix(1_500_000, 15.0), 1_500_000)
        assertEquals(MotionState.UNKNOWN, exit.motionState)
        filter.updateMotion(MotionState.STILL, 500_000, persistentUntilExit = true)
        assertEquals(MotionState.UNKNOWN, filter.filter(fix(1_800_000, 15.0), 1_800_000).motionState)
    }

    @Test fun sameTimestampExitIsNotDiscardedAndStillSnapshotCannotOverwriteIt() {
        val filter = established()
        filter.updateMotion(MotionState.STILL, 400_000)
        filter.updateMotion(MotionState.UNKNOWN, 400_000)
        filter.updateMotion(MotionState.STILL, 400_000)
        val result = filter.filter(fix(600_000, 15.0), 600_000)
        assertEquals(MotionState.UNKNOWN, result.motionState)
        assertNull(result.stationarySinceElapsedMillis)
        assertFalse(result.adjusted)
    }

    @Test fun orderedWalkingExitThenStillEnterAtSameTimestampStartsALatchedNewDwell() {
        val filter = StationaryLocationFilter()
        filter.updateMotion(MotionState.WALKING, 0, persistentUntilExit = true)
        filter.filter(fix(0), 0)
        filter.updateMotion(MotionState.UNKNOWN, 300_000) // EXIT(WALKING)
        filter.updateMotion(MotionState.STILL, 300_000, persistentUntilExit = true) // ENTER(STILL)
        val started = filter.filter(fix(300_000), 300_000)
        assertEquals(MotionState.STILL, started.motionState)
        assertNull(started.stationarySinceElapsedMillis)
        for (at in 600_000L..2_100_000L step 300_000L) {
            assertEquals(300_000L, filter.filter(fix(at, 15.0), at).stationarySinceElapsedMillis)
        }
    }

    @Test fun orderedStillEnterThenExitAtSameTimestampEndsTheLatch() {
        val filter = established()
        filter.updateMotion(MotionState.STILL, 400_000, persistentUntilExit = true)
        filter.updateMotion(MotionState.UNKNOWN, 400_000) // EXIT after ENTER is authoritative.
        val result = filter.filter(fix(600_000, 15.0), 600_000)
        assertEquals(MotionState.UNKNOWN, result.motionState)
        assertNull(result.stationarySinceElapsedMillis)
        assertFalse(result.adjusted)
    }

    @Test fun sameTimestampTransitionCanUpgradeAStillSnapshotToALatch() {
        val filter = StationaryLocationFilter()
        filter.updateMotion(MotionState.STILL, 0)
        filter.updateMotion(MotionState.STILL, 0, persistentUntilExit = true)
        filter.filter(fix(0), 0)
        for (at in 300_000L..1_200_000L step 300_000L) {
            assertEquals(0L, filter.filter(fix(at, 15.0), at).stationarySinceElapsedMillis)
        }
    }

    @Test fun longLocationGapStartsNewEvidenceWithoutFillingTheMissingTime() {
        val filter = established()
        filter.updateMotion(MotionState.STILL, 900_001)
        assertNull(filter.filter(fix(900_001, 5.0), 900_001).stationarySinceElapsedMillis)
        val result = filter.filter(fix(1_200_001, 8.0), 1_200_001)
        assertEquals(900_001L, result.stationarySinceElapsedMillis)
    }

    @Test fun stillExitBetweenSamplesCannotBeErasedByANewStillEvent() {
        val filter = established()
        filter.updateMotion(MotionState.UNKNOWN, 400_000)
        filter.updateMotion(MotionState.STILL, 500_000)
        assertNull(filter.filter(fix(600_000), 600_000).stationarySinceElapsedMillis)
        assertEquals(600_000L, filter.filter(fix(900_000, 8.0), 900_000).stationarySinceElapsedMillis)
    }

    @Test fun futureAndOutOfOrderMotionCannotPoisonLaterValidEvidence() {
        val filter = StationaryLocationFilter()
        filter.updateMotion(MotionState.STILL, Long.MAX_VALUE)
        filter.updateMotion(MotionState.STILL, 0)
        filter.filter(fix(0), 0)
        filter.updateMotion(MotionState.WALKING, 100_000)
        filter.updateMotion(MotionState.STILL, 50_000)
        val moved = filter.filter(fix(300_000, 10.0), 300_000)
        assertEquals(MotionState.WALKING, moved.motionState)
        assertNull(moved.stationarySinceElapsedMillis)
        filter.updateMotion(MotionState.STILL, 400_000)
        assertNull(filter.filter(fix(600_000), 600_000).stationarySinceElapsedMillis)
        assertEquals(600_000L, filter.filter(fix(900_000), 900_000).stationarySinceElapsedMillis)
    }

    @Test fun duplicateLocationCannotCountTwiceAndBackwardsClockDoesNotDamageValidAnchor() {
        val filter = StationaryLocationFilter()
        filter.updateMotion(MotionState.STILL, 0)
        filter.filter(fix(0), 0)
        assertNull(filter.filter(fix(0), 10_000).stationarySinceElapsedMillis)
        assertEquals(0L, filter.filter(fix(300_000, 10.0), 300_000).stationarySinceElapsedMillis)
        assertNull(filter.filter(fix(200_000), 200_000).stationarySinceElapsedMillis)
        assertEquals(0L, filter.filter(fix(600_000, 5.0), 600_000).stationarySinceElapsedMillis)
    }

    @Test fun invalidFixBreaksDwellAndIsNeverUsedAsTheAnchor() {
        val filter = established()
        assertNull(filter.filter(fix(600_000).copy(accuracyMeters = 151.0), 600_000).stationarySinceElapsedMillis)
        filter.updateMotion(MotionState.STILL, 800_000) // Keep activity evidence fresh; this test isolates invalid GPS.
        assertNull(filter.filter(fix(900_000), 900_000).stationarySinceElapsedMillis)
        assertEquals(900_000L, filter.filter(fix(1_200_000, 10.0), 1_200_000).stationarySinceElapsedMillis)
    }

    @Test fun adaptiveNoiseRadiusIsBoundedAndDisplayAccuracyIncludesOffsetAndAge() {
        val filter = StationaryLocationFilter()
        filter.updateMotion(MotionState.STILL, 0)
        filter.filter(fix(0, accuracy = 50.0), 0)
        val held = filter.filter(fix(300_000, 90.0, 50.0), 300_000)
        assertEquals(0L, held.stationarySinceElapsedMillis)
        assertTrue(checkNotNull(held.displayAccuracyMeters) > 140.0)
        filter.filter(fix(600_000, 160.0, 150.0), 600_000)
        val released = filter.filter(fix(900_000, 180.0, 150.0), 900_000)
        assertNull(released.stationarySinceElapsedMillis)
        assertFalse(released.adjusted)
    }
}
