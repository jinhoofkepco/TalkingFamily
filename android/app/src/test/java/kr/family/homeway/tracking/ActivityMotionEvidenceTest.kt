package kr.family.homeway.tracking

import org.junit.Assert.*
import org.junit.Test

class ActivityMotionEvidenceTest {
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
}
