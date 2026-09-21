package kr.family.homeway.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {
    @Test fun physicalStarUsesFiveMillimetresOnS23LikeDensity() {
        assertEquals(425f * 5f / 25.4f, OverlayGeometry.starPixels(425f, 3f), 0.01f)
        assertEquals(500f * 5f / 25.4f, OverlayGeometry.starPixels(500f, 3f), 0.01f)
    }

    @Test fun brokenPhysicalDpiHasFiniteBoundedFallback() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -100f, 10000f).forEach { dpi ->
            val pixels = OverlayGeometry.starPixels(dpi, 3f)
            assertTrue(pixels.isFinite() && pixels in 72f..120f)
        }
        assertTrue(OverlayGeometry.starPixels(Float.NaN, Float.NaN).isFinite())
    }

    @Test fun savedPositionReflowsInsideRotatedSafeBounds() {
        val fraction = OverlayGeometry.fraction(790, 100, 2500, 120)
        val landscapeY = OverlayGeometry.coordinate(60, 1050, 120, fraction)
        assertTrue(landscapeY in 60..930)
        assertEquals(fraction, OverlayGeometry.fraction(landscapeY, 60, 1050, 120), 0.002f)
        assertEquals(1936, OverlayGeometry.coordinate(20, 2056, 120, 1f))
    }

    @Test fun narrowOrInvalidBoundsNeverThrow() {
        assertEquals(20, OverlayGeometry.coordinate(20, 50, 120, Float.NaN))
        assertEquals(20, OverlayGeometry.clamp(-999, 20, 50, 120))
        assertEquals(0.5f, OverlayGeometry.fraction(400, 20, 50, 120), 0f)
        assertEquals(10, OverlayGeometry.coordinate(10, 1010, 100, -1f))
        assertEquals(910, OverlayGeometry.coordinate(10, 1010, 100, 2f))
    }
}
