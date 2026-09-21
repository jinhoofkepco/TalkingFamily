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
        fun height(meters: Double, moving: Boolean = true) {
            events += detector.addPressure(1013.25 * (1.0 - meters / 44330.0).pow(1.0 / 0.19029495), time, moving)
            time += 1000
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
}
