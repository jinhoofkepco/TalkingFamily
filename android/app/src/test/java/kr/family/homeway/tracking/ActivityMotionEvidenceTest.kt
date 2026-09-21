package kr.family.homeway.tracking

import org.junit.Assert.*
import org.junit.Test

class ActivityMotionEvidenceTest {
    private fun fix(at: Long, northMeters: Double = 0.0) = LocationFixSample(
        37.5 + Math.toDegrees(northMeters / 6_371_000.0), 127.0, 10.0, 1_700_000_000_000L + at, at)

    private fun StationaryLocationFilter.accept(observation: ActivityMotionEvidence.Observation) =
        updateMotion(observation.state, observation.atElapsedMillis, observation.persistentUntilExit)

    @Test fun oldSessionFutureStaleAndOutOfOrderEvidenceIsRejected() {
        val evidence = ActivityMotionEvidence(1_000)
        assertNull(evidence.sample(MotionState.STILL, 100, 999, 1_000))
        assertNull(evidence.sample(MotionState.STILL, 100, 1_001, 1_000))
        assertNull(evidence.sample(MotionState.STILL, 100, 1_000, 902_000))
        assertEquals(MotionState.STILL, evidence.sample(MotionState.STILL, 100, 2_000, 2_000)?.state)
        assertNull(evidence.transition(MotionState.WALKING, true, 1_999, 2_000))
    }

    @Test fun lowConfidenceDoesNotEraseOrRefreshClearState() {
        val evidence = ActivityMotionEvidence(0)
        assertEquals(MotionState.STILL, evidence.transition(MotionState.STILL, true, 1_000, 1_000)?.state)
        assertNull(evidence.sample(MotionState.WALKING, 74, 2_000, 2_000))
        assertNull(evidence.sample(MotionState.UNKNOWN, 20, 3_000, 3_000))
        assertEquals(MotionState.UNKNOWN, evidence.transition(MotionState.STILL, false, 4_000, 4_000)?.state)
    }

    @Test fun stillExitDoesNotInventWalking() {
        val evidence = ActivityMotionEvidence(0)
        evidence.sample(MotionState.STILL, 90, 1_000, 1_000)
        assertEquals(MotionState.UNKNOWN, evidence.transition(MotionState.STILL, false, 2_000, 2_000)?.state)
        assertEquals(MotionState.WALKING, evidence.transition(MotionState.WALKING, true, 2_000, 2_000)?.state)
    }

    @Test fun simultaneousEnterAndUnrelatedExitKeepNewState() {
        val evidence = ActivityMotionEvidence(0)
        evidence.transition(MotionState.STILL, true, 1_000, 1_000)
        assertEquals(MotionState.WALKING, evidence.transition(MotionState.WALKING, true, 2_000, 2_000)?.state)
        assertNull(evidence.transition(MotionState.STILL, false, 2_000, 2_000))
        assertEquals(MotionState.UNKNOWN, evidence.transition(MotionState.WALKING, false, 3_000, 3_000)?.state)
    }

    @Test fun highConfidenceSamplesBootstrapAndRefreshWithoutRequiringTransition() {
        val evidence = ActivityMotionEvidence(0)
        assertEquals(ActivityMotionEvidence.Observation(MotionState.STILL, 300_000),
            evidence.sample(MotionState.STILL, 75, 300_000, 300_100))
        assertEquals(ActivityMotionEvidence.Observation(MotionState.STILL, 600_000),
            evidence.sample(MotionState.STILL, 100, 600_000, 600_100))
        assertEquals(MotionState.VEHICLE, evidence.sample(MotionState.VEHICLE, 90, 900_000, 900_000)?.state)
    }

    @Test fun transitionStatesAreExplicitlyLatchedButSamplesAndExitsAreNot() {
        val evidence = ActivityMotionEvidence(0)
        assertEquals(true, evidence.transition(MotionState.STILL, true, 1_000, 1_000)?.persistentUntilExit)
        assertEquals(false, evidence.sample(MotionState.STILL, 95, 300_000, 300_000)?.persistentUntilExit)
        assertEquals(false, evidence.transition(MotionState.STILL, false, 1_800_000, 1_800_000)?.persistentUntilExit)
        assertEquals(true, evidence.transition(MotionState.WALKING, true, 1_800_000, 1_800_000)?.persistentUntilExit)
    }

    @Test fun ignoredWeakSampleDoesNotHideEarlierClearExit() {
        val evidence = ActivityMotionEvidence(0)
        evidence.transition(MotionState.STILL, true, 1_000, 1_000)
        assertNull(evidence.sample(MotionState.STILL, 30, 3_000, 3_000))
        assertEquals(MotionState.UNKNOWN, evidence.transition(MotionState.STILL, false, 2_500, 3_100)?.state)
    }

    @Test fun delayedExitDisprovesLatchedStillButOldStillCannotEstablishNewEpisode() {
        val evidence = ActivityMotionEvidence(0)
        evidence.transition(MotionState.STILL, true, 1_000, 1_000)
        assertEquals(ActivityMotionEvidence.Observation(MotionState.UNKNOWN, 2_000),
            evidence.transition(MotionState.STILL, false, 2_000, 2_000_000))
        assertNull(evidence.transition(MotionState.STILL, true, 3_000, 2_000_000))
    }

    @Test fun stillSnapshotsCannotEraseVehicleEpisodeEvenAfterOtherSnapshots() {
        val evidence = ActivityMotionEvidence(0)
        evidence.transition(MotionState.VEHICLE, true, 1_000, 1_000)
        assertEquals(MotionState.VEHICLE, evidence.sample(MotionState.VEHICLE, 95, 2_000, 2_000)?.state)
        assertNull(evidence.sample(MotionState.STILL, 100, 3_000, 3_000))
        assertEquals(MotionState.UNKNOWN, evidence.sample(MotionState.UNKNOWN, 95, 4_000, 4_000)?.state)
        assertNull(evidence.sample(MotionState.STILL, 100, 5_000, 5_000))
        assertEquals(MotionState.UNKNOWN, evidence.transition(MotionState.VEHICLE, false, 6_000, 6_000)?.state)
        assertEquals(MotionState.STILL, evidence.sample(MotionState.STILL, 100, 7_000, 7_000)?.state)
    }

    @Test fun explicitStillEnterCanReplaceAnOngoingMovingEpisode() {
        for (state in listOf(MotionState.WALKING, MotionState.RUNNING, MotionState.BICYCLE, MotionState.VEHICLE)) {
            val evidence = ActivityMotionEvidence(0)
            evidence.transition(state, true, 1_000, 1_000)
            assertNull(evidence.sample(MotionState.STILL, 100, 2_000, 2_000))
            assertEquals(ActivityMotionEvidence.Observation(MotionState.STILL, 3_000, true),
                evidence.transition(MotionState.STILL, true, 3_000, 3_000))
            assertNull(evidence.transition(state, false, 3_000, 3_000))
            assertEquals(MotionState.STILL, evidence.sample(MotionState.STILL, 100, 4_000, 4_000)?.state)
        }
    }

    @Test fun matchingStillExitClearsTransitionEvenAfterUnknownSnapshot() {
        val evidence = ActivityMotionEvidence(0)
        evidence.transition(MotionState.STILL, true, 1_000, 1_000)
        evidence.sample(MotionState.UNKNOWN, 95, 2_000, 2_000)
        assertEquals(MotionState.UNKNOWN, evidence.transition(MotionState.STILL, false, 3_000, 3_000)?.state)
    }

    @Test fun freshStillAfterTransitionRegistrationBootstrapsLongQuietStationaryEpisode() {
        val evidence = ActivityMotionEvidence(0)
        val filter = StationaryLocationFilter()
        val initial = checkNotNull(evidence.sample(MotionState.STILL, 95, 1_000, 1_000,
            transitionWatchStartedAtMillis = 500))
        assertTrue(initial.persistentUntilExit)
        filter.accept(initial)
        filter.filter(fix(1_000), 1_000)
        // No new activity event for thirty minutes; regular fresh GPS records still arrive.
        for (slot in 1..6) {
            val at = 1_000L + slot * 300_000L
            val display = filter.filter(fix(at, 10.0), at)
            assertEquals(MotionState.STILL, display.motionState)
            assertEquals(1_000L, display.stationarySinceElapsedMillis)
            assertTrue(display.adjusted)
        }
    }

    @Test fun cachedStillMeasuredBeforeTransitionRegistrationStillExpires() {
        val evidence = ActivityMotionEvidence(0)
        val filter = StationaryLocationFilter()
        val cached = checkNotNull(evidence.sample(MotionState.STILL, 95, 1_000, 2_000,
            transitionWatchStartedAtMillis = 2_000))
        assertFalse(cached.persistentUntilExit)
        filter.accept(cached)
        filter.filter(fix(2_000), 2_000)
        for (slot in 1..3) {
            val at = 2_000L + slot * 300_000L
            filter.filter(fix(at, 10.0), at)
        }
        val expired = filter.filter(fix(1_202_000, 10.0), 1_202_000)
        assertEquals(MotionState.UNKNOWN, expired.motionState)
        assertNull(expired.stationarySinceElapsedMillis)
        assertFalse(expired.adjusted)
    }

    @Test fun laterExitReleasesBootstrappedStillWithoutAnInitialEnter() {
        val evidence = ActivityMotionEvidence(0)
        val filter = StationaryLocationFilter()
        filter.accept(checkNotNull(evidence.sample(MotionState.STILL, 95, 1_000, 1_000,
            transitionWatchStartedAtMillis = 500)))
        filter.filter(fix(1_000), 1_000)
        assertTrue(filter.filter(fix(301_000, 10.0), 301_000).adjusted)
        filter.accept(checkNotNull(evidence.transition(MotionState.STILL, false, 302_000, 302_000)))
        val afterExit = filter.filter(fix(601_000, 15.0), 601_000)
        assertEquals(MotionState.UNKNOWN, afterExit.motionState)
        assertNull(afterExit.stationarySinceElapsedMillis)
        assertFalse(afterExit.adjusted)
    }

    @Test fun bootstrapStillStillRequiresActiveWatchAndCannotEraseMovingTransition() {
        val evidence = ActivityMotionEvidence(0)
        assertFalse(checkNotNull(evidence.sample(MotionState.STILL, 95, 1_000, 1_000)).persistentUntilExit)
        evidence.transition(MotionState.VEHICLE, true, 2_000, 2_000)
        assertNull(evidence.sample(MotionState.STILL, 100, 3_000, 3_000, transitionWatchStartedAtMillis = 500))
        assertNull(evidence.sample(MotionState.STILL, 74, 4_000, 4_000, transitionWatchStartedAtMillis = 500))
    }

    @Test fun bootstrapSnapshotCannotUndoAnExitAtTheSameTimestamp() {
        val evidence = ActivityMotionEvidence(0)
        evidence.sample(MotionState.STILL, 95, 1_000, 1_000, transitionWatchStartedAtMillis = 500)
        assertEquals(MotionState.UNKNOWN, evidence.transition(MotionState.STILL, false, 2_000, 2_000)?.state)
        assertNull(evidence.sample(MotionState.STILL, 100, 2_000, 2_000, transitionWatchStartedAtMillis = 500))
        assertEquals(MotionState.STILL, evidence.transition(MotionState.STILL, true, 2_000, 2_000)?.state)
    }
}
