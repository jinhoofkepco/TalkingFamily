package kr.family.homeway.data

import java.time.Instant
import kotlin.math.*
import org.json.JSONObject

/** Optional display estimates never replace, or invalidate, an otherwise valid GPS measurement. */
internal object LocationMotionMetadata {
    val keys = setOf("displayLatitude", "displayLongitude", "displayAccuracy", "positionAdjusted", "motion", "stationarySince")
    private val states = setOf("unknown", "still", "walking", "running", "bicycle", "vehicle")

    fun copyValidated(input: JSONObject, output: JSONObject) {
        if (output.optString("source") != "automatic") return
        val motion = input.opt("motion") as? String ?: return
        if (motion !in states) return
        output.put("motion", motion)
        val latitude = number(input, "displayLatitude", -90.0, 90.0) ?: return
        val longitude = number(input, "displayLongitude", -180.0, 180.0) ?: return
        val accuracy = number(input, "displayAccuracy", 0.0, 100_000.0) ?: return
        val adjusted = input.opt("positionAdjusted") as? Boolean ?: return
        val displacement = distance(output.getDouble("latitude"), output.getDouble("longitude"), latitude, longitude)
        // A bounded estimate must include the uncertainty of the fresh measurement around its anchor.
        if (displacement > 250.0 || accuracy + 0.01 < output.getDouble("accuracy") + displacement) return
        val capturedAt = runCatching { Instant.parse(output.getString("capturedAt")) }.getOrNull() ?: return
        val since = (input.opt("stationarySince") as? String)?.takeIf { it.length <= 40 }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?.takeIf { motion == "still" && !it.isBefore(Instant.EPOCH) && !it.isAfter(capturedAt) }
        if (adjusted && (motion != "still" || since == null)) return
        if (!adjusted && displacement > 0.01) return
        output.put("displayLatitude", latitude).put("displayLongitude", longitude)
            .put("displayAccuracy", accuracy).put("positionAdjusted", adjusted)
        since?.let { output.put("stationarySince", it.toString()) }
    }

    private fun number(p: JSONObject, key: String, min: Double, max: Double): Double? =
        (p.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it in min..max }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
