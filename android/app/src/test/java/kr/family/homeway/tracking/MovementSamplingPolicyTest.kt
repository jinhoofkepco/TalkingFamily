package kr.family.homeway.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementSamplingPolicyTest {
    @Test fun stationaryIntervalsNeverRequestGps() {
        val policy = MovementSamplingPolicy(0)
        assertFalse(policy.consumeDue(299_999))
        assertFalse(policy.consumeDue(300_000))
        assertFalse(policy.consumeDue(600_000))
    }

    @Test fun movementIsRememberedUntilFiveMinuteDeadlineEvenAfterStopping() {
        val policy = MovementSamplingPolicy(0)
        policy.noteMovement()
        assertFalse(policy.consumeDue(30_000))
        assertFalse(policy.consumeDue(299_999))
        assertTrue(policy.consumeDue(300_000))
        assertFalse(policy.consumeDue(600_000))
    }

    @Test fun delayDoesNotCauseCatchupBurstAndMovementDuringCaptureIsRetained() {
        val policy = MovementSamplingPolicy(0)
        policy.noteMovement()
        assertTrue(policy.consumeDue(900_000))
        policy.noteMovement()
        assertFalse(policy.consumeDue(900_001))
        assertFalse(policy.consumeDue(1_199_999))
        assertTrue(policy.consumeDue(1_200_000))
    }

    @Test fun failedFixRetriesOnlyAtNextInterval() {
        val policy = MovementSamplingPolicy(0)
        policy.noteMovement()
        assertTrue(policy.consumeDue(300_000))
        policy.retryNextInterval()
        assertFalse(policy.consumeDue(320_000))
        assertTrue(policy.consumeDue(600_000))
    }
}
