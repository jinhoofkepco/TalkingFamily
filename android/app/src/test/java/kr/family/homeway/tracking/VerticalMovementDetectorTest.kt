package kr.family.homeway.tracking

import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalMovementDetectorTest {
    private fun pressure(height: Double) = 1013.25 * (1.0 - height / 44330.0).pow(1.0 / 0.19029495)
    private class Trace {
        val detector = VerticalMovementDetector()
        val events = mutableListOf<VerticalMovementDetector.Event>()
        var time = 0L
        fun height(meters: Double, moving: Boolean = true, stepping: Boolean = false, intervalMillis: Long = 1000) {
            if (stepping) detector.noteStep(time)
            events += detector.addPressure(1013.25 * (1.0 - meters / 44330.0).pow(1.0 / 0.19029495), time, moving)
            time += intervalMillis
        }
    }

    @Test fun walkingWithSmallPressureNoiseDoesNotInventFloors() {
        val trace = Trace()
        repeat(180) { trace.height(sin(it.toDouble()) * 0.3) }
        assertTrue(trace.events.isEmpty())
    }

    @Test fun stationaryWeatherDriftDoesNotStartJourney() {
        val trace = Trace()
        repeat(180) { trace.height(it * 0.05, moving = false) }
        assertTrue(trace.events.isEmpty())
    }

    @Test fun stairAscentProducesBackdatedStartAndFinishInOrder() {
        val trace = Trace()
        repeat(10) { trace.height(0.0) }
        repeat(20) { trace.height((it + 1) * 0.3) }
        repeat(15) { trace.height(6.0) }
        assertEquals(listOf("ascent_started", "ascent_finished"), trace.events.map { it.phase })
        assertTrue(trace.events[0].measuredAtMillis in 9_000..14_000)
        assertTrue(trace.events[1].measuredAtMillis in 29_000..35_000)
        assertTrue(trace.events[1].relativeMeters in 5.0..6.2)
        assertFalse(trace.detector.hasActiveMovement)
    }

    @Test fun descentReportsNegativeRelativeHeightAndBothTimes() {
        val trace = Trace()
        repeat(8) { trace.height(12.0) }
        repeat(20) { trace.height(12.0 - (it + 1) * 0.3) }
        repeat(15) { trace.height(6.0) }
        assertEquals(listOf("descent_started", "descent_finished"), trace.events.map { it.phase })
        assertTrue(trace.events[1].relativeMeters in -6.2..-5.0)
        assertTrue(trace.events[0].measuredAtMillis < trace.events[1].measuredAtMillis)
    }

    @Test fun reversalClosesFirstDirectionBeforeOpeningSecond() {
        val trace = Trace()
        repeat(8) { trace.height(0.0) }
        repeat(15) { trace.height((it + 1) * 0.4) }
        repeat(15) { trace.height(6.0 - (it + 1) * 0.4) }
        repeat(15) { trace.height(0.0) }
        assertEquals(listOf("ascent_started", "ascent_finished", "descent_started", "descent_finished"), trace.events.map { it.phase })
        assertEquals(trace.events[1].measuredAtMillis, trace.events[2].measuredAtMillis)
    }

    @Test fun longSensorGapDoesNotInventFinishOrBridgeSessions() {
        val trace = Trace()
        repeat(8) { trace.height(0.0) }
        repeat(15) { trace.height((it + 1) * 0.4) }
        assertTrue(trace.detector.hasActiveMovement)
        trace.time += 60_000
        repeat(20) { trace.height(20.0, moving = false) }
        assertEquals(listOf("ascent_started"), trace.events.map { it.phase })
        assertFalse(trace.detector.hasActiveMovement)
    }

    @Test fun isolatedPressureSpikeIsSuppressedByMedian() {
        val trace = Trace()
        repeat(10) { trace.height(0.0) }
        trace.height(8.0)
        repeat(20) { trace.height(0.0) }
        assertTrue(trace.events.isEmpty())
    }

    @Test fun duplicateAndOutOfOrderSamplesAreIgnored() {
        val detector = VerticalMovementDetector()
        detector.addPressure(pressure(0.0), 10_000, true)
        assertTrue(detector.addPressure(pressure(20.0), 10_000, true).isEmpty())
        assertTrue(detector.addPressure(pressure(20.0), 9_000, true).isEmpty())
        assertFalse(detector.hasActiveMovement)
    }

    @Test fun abruptPressureZoneChangeWhileWalkingDoesNotBecomeStairsFromFilterTail() {
        val trace = Trace()
        repeat(10) { trace.height(0.0, stepping = true) }
        repeat(40) { trace.height(4.0, stepping = true) }
        repeat(30) { trace.height(0.0, stepping = true) }
        assertTrue(trace.events.isEmpty())
        assertFalse(trace.detector.hasActiveMovement)
    }

    @Test fun aShortPressurePulseWithMotionDoesNotCreateAnUpAndDownJourney() {
        val trace = Trace()
        repeat(10) { trace.height(0.0) }
        repeat(2) { trace.height(5.0) }
        repeat(30) { trace.height(0.0) }
        assertTrue(trace.events.isEmpty())
    }

    @Test fun slowClimbUsesRepeatedRecentStepsInsteadOfLoweringWeatherDriftThreshold() {
        val trace = Trace()
        repeat(10) { trace.height(0.0, moving = false) }
        repeat(90) { trace.height((it + 1) * 0.04, moving = false, stepping = true) }
        repeat(20) { trace.height(3.6, moving = false) }
        assertEquals(listOf("ascent_started", "ascent_finished"), trace.events.map { it.phase })
        assertTrue(trace.events.all { it.evidence == "barometer_steps" })
        assertTrue(trace.events.last().relativeMeters in 2.8..3.8)
        assertFalse(trace.detector.hasActiveMovement)
    }

    @Test fun slowDescentWithStepsPreservesDirectionAndNegativeHeight() {
        val trace = Trace()
        repeat(10) { trace.height(8.0, moving = false) }
        repeat(90) { trace.height(8.0 - (it + 1) * 0.04, moving = false, stepping = true) }
        repeat(20) { trace.height(4.4, moving = false) }
        assertEquals(listOf("descent_started", "descent_finished"), trace.events.map { it.phase })
        assertTrue(trace.events.last().relativeMeters in -3.8..-2.8)
        assertEquals("barometer_steps", trace.events.last().evidence)
    }

    @Test fun oldOrFutureStepsNeverUpgradeStationaryPressureDrift() {
        val trace = Trace()
        repeat(8) { trace.height(0.0, moving = false, stepping = true) }
        repeat(90) { trace.height((it + 1) * 0.04, moving = false) }
        repeat(6) { trace.detector.noteStep(trace.time + 120_000 + it * 1000) }
        repeat(90) { trace.height(3.6 + (it + 1) * 0.04, moving = false) }
        assertTrue(trace.events.isEmpty())
    }

    @Test fun duplicateStepCallbacksCannotFabricateSustainedWalking() {
        val trace = Trace()
        repeat(10) { trace.height(0.0, moving = false) }
        repeat(90) {
            repeat(10) { trace.detector.noteStep(10_000) }
            trace.height((it + 1) * 0.04, moving = false)
        }
        assertTrue(trace.events.isEmpty())
    }

    @Test fun stationaryDriftAfterClimbingDoesNotKeepJourneyAliveForMinutes() {
        val trace = Trace()
        repeat(10) { trace.height(0.0) }
        repeat(20) { trace.height((it + 1) * 0.3, stepping = true) }
        repeat(180) { trace.height(6.0 + (it + 1) * 0.04, moving = false) }
        assertEquals(listOf("ascent_started", "ascent_finished"), trace.events.map { it.phase })
        assertTrue(trace.events.last().relativeMeters < 6.5)
        assertTrue(trace.events.last().measuredAtMillis < 40_000)
        assertFalse(trace.detector.hasActiveMovement)
    }

    @Test fun sustainedVerticalMotionWithoutStepsStaysUnclassifiedInsteadOfClaimingStairs() {
        val trace = Trace()
        repeat(8) { trace.height(0.0, moving = false) }
        repeat(30) { trace.height((it + 1) * 0.8, moving = it < 5) }
        repeat(20) { trace.height(24.0, moving = false) }
        assertEquals(listOf("ascent_started", "ascent_finished"), trace.events.map { it.phase })
        assertTrue(trace.events.all { it.evidence == "barometer_motion" })
        assertTrue(trace.events.last().relativeMeters > 21.0)
        assertFalse(trace.detector.hasActiveMovement)
    }

    @Test fun pressureCallbackFrequencyDoesNotChangeDirectionOrProduceExtraJourneys() {
        fun run(interval: Long): Trace {
            val trace = Trace()
            while (trace.time <= 50_000) {
                val seconds = trace.time / 1000.0
                val height = ((seconds - 10.0) * 0.3).coerceIn(0.0, 6.0)
                trace.height(height, intervalMillis = interval)
            }
            return trace
        }
        val slow = run(1000)
        val fast = run(100)
        assertEquals(listOf("ascent_started", "ascent_finished"), slow.events.map { it.phase })
        assertEquals(slow.events.map { it.phase }, fast.events.map { it.phase })
        assertTrue(kotlin.math.abs(slow.events.last().relativeMeters - fast.events.last().relativeMeters) < 0.5)
        assertTrue(kotlin.math.abs(slow.events.last().measuredAtMillis - fast.events.last().measuredAtMillis) <= 2500)
    }

    @Test fun longLandingSeparatesFlightsAndPressureResetDoesNotBridgeThem() {
        val trace = Trace()
        repeat(10) { trace.height(0.0) }
        repeat(15) { trace.height((it + 1) * 0.3, stepping = true) }
        repeat(90) { trace.height(4.5, moving = false) }
        repeat(15) { trace.height(4.5 + (it + 1) * 0.3, stepping = true) }
        repeat(20) { trace.height(9.0, moving = false) }
        assertEquals(listOf("ascent_started", "ascent_finished", "ascent_started", "ascent_finished"), trace.events.map { it.phase })
        assertTrue(trace.events[2].measuredAtMillis >= 110_000)
        assertFalse(trace.detector.hasActiveMovement)
    }

    @Test fun slowReversalRetainsPivotUntilShortDescentIsConfirmed() {
        for (speed in listOf(0.1, 0.04)) {
            val trace = Trace()
            repeat(10) { trace.height(0.0, moving = false) }
            repeat(20) { trace.height((it + 1) * 0.3, moving = false, stepping = true) }
            repeat((2.4 / speed).toInt()) { trace.height(6.0 - (it + 1) * speed, moving = false, stepping = true) }
            repeat(25) { trace.height(3.6, moving = false) }
            assertEquals("Missing slow reversal at $speed m/s", listOf("ascent_started", "ascent_finished", "descent_started", "descent_finished"), trace.events.map { it.phase })
            assertEquals(trace.events[1].measuredAtMillis, trace.events[2].measuredAtMillis)
            assertTrue(trace.events.last().relativeMeters in -2.6..-1.8)
            assertFalse(trace.detector.hasActiveMovement)
        }
    }
}
