package kr.family.homeway.overlay

import kotlin.math.roundToInt

/** Math is independent of Android so malformed metrics and edge placement can be tested. */
internal object OverlayGeometry {
    fun starPixels(dpi: Float, density: Float): Float {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val fallback = 31.5f * safeDensity
        return if (dpi.isFinite() && dpi in 100f..1000f) {
            (dpi * 5f / 25.4f).coerceIn(24f * safeDensity, 40f * safeDensity)
        } else fallback
    }

    fun coordinate(start: Int, end: Int, extent: Int, fraction: Float): Int {
        val travel = (end - start - extent).coerceAtLeast(0)
        val safeFraction = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.34f
        return start + (travel * safeFraction).roundToInt()
    }

    fun fraction(coordinate: Int, start: Int, end: Int, extent: Int): Float {
        val travel = end - start - extent
        return if (travel > 0) ((coordinate - start).toFloat() / travel).coerceIn(0f, 1f) else 0.5f
    }

    fun clamp(coordinate: Int, start: Int, end: Int, extent: Int): Int =
        coordinate.coerceIn(start, (end - extent).coerceAtLeast(start))
}
