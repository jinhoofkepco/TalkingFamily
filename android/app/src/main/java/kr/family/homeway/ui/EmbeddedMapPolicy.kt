package kr.family.homeway.ui

import java.net.URI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class EmbeddedMapHistoryPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val measuredAtMillis: Long,
    val displayLatitude: Double? = null,
    val displayLongitude: Double? = null,
    val displayAccuracy: Double? = null,
    val positionAdjusted: Boolean = false,
    val motion: String = "unknown",
    val stationarySinceMillis: Long? = null,
)

internal data class ValidatedMapHistoryPoint(
    val location: EmbeddedMapLocation,
    val measuredAtMillis: Long,
    val positionAdjusted: Boolean = false,
    val motion: String = "unknown",
    val stationarySinceMillis: Long? = null,
) {
    fun javascriptTuple(): String = "[${location.latitude},${location.longitude},${location.accuracy ?: "null"},$measuredAtMillis]"

    /** Historical estimates end at this measurement, never at the viewer's current time. */
    fun activityLabel(): String? = when (motion) {
        "still" -> stationarySinceMillis?.let {
            val minutes = (measuredAtMillis - it) / 60_000
            if (minutes == 0L) "정지 추정 · 1분 미만" else "정지 추정 · 약 ${minutes}분"
        } ?: "정지 추정"
        "walking" -> "걷는 중 추정"
        "running" -> "달리는 중 추정"
        "bicycle" -> "자전거 이동 추정"
        "vehicle" -> "차량 이동 추정"
        else -> null
    }
}

internal data class ValidatedMapHistory(val points: List<ValidatedMapHistoryPoint>, val selectedIndex: Int) {
    fun javascriptCall(): String = "window.FamilyMap.setHistory(${points.joinToString(prefix = "[", postfix = "]") { it.javascriptTuple() }});"
}

/** The only values exposed to the bundled map page are already validated numbers. */
internal data class EmbeddedMapLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double?,
) {
    fun javascriptCall(): String =
        "window.FamilyMap.setLocation($latitude,$longitude,${accuracy ?: "null"});"
}

internal object EmbeddedMapPolicy {
    const val DOCUMENT_URL = "https://appassets.androidplatform.net/assets/family-map/index.html"
    const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
    private const val MERCATOR_LIMIT = 85.05112878
    private val assetPath = Regex("/assets/family-map/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_.-]+")
    private val tilePath = Regex("/([0-9]{1,2})/([0-9]{1,10})/([0-9]{1,10})\\.png")

    enum class Resource { BUNDLED_ASSET, TILE, BLOCKED }

    fun history(points: List<EmbeddedMapHistoryPoint>, selectedIndex: Int): ValidatedMapHistory {
        val valid = points.mapIndexedNotNull { index, point ->
            index to (historyPoint(point) ?: return@mapIndexedNotNull null)
        }
        return ValidatedMapHistory(valid.map { it.second }, valid.indexOfFirst { it.first == selectedIndex })
    }

    fun historyPoint(point: EmbeddedMapHistoryPoint): ValidatedMapHistoryPoint? {
        val raw = location(point.latitude, point.longitude, point.accuracy) ?: return null
        if (point.measuredAtMillis !in 0L..253402300799999L) return null
        val motion = point.motion.takeIf { it in setOf("still", "walking", "running", "bicycle", "vehicle") } ?: "unknown"
        val stationarySince = point.stationarySinceMillis?.takeIf { motion == "still" && it in 0L..point.measuredAtMillis }
        // Optional metadata cannot invalidate a usable raw record. A partial or
        // contradictory adjustment falls back to the original GPS measurement.
        val candidate = if ((!point.positionAdjusted || (motion == "still" && stationarySince != null)) &&
            point.displayLatitude != null && point.displayLongitude != null &&
            point.displayAccuracy?.let { it.isFinite() && it in 0.0..100_000.0 } == true
        ) location(point.displayLatitude, point.displayLongitude, point.displayAccuracy!!) else null
        val display = candidate?.takeIf {
            val displacement = distanceMeters(point.latitude, point.longitude, point.displayLatitude!!, point.displayLongitude!!)
            point.accuracy.isFinite() && point.accuracy >= 0.0 && displacement <= 250.0 &&
                it.accuracy!! + 0.01 >= point.accuracy + displacement &&
                (point.positionAdjusted || displacement <= 0.01)
        }
        return ValidatedMapHistoryPoint(display ?: raw, point.measuredAtMillis, display != null && point.positionAdjusted,
            motion, stationarySince.takeIf { display != null })
    }

    private fun distanceMeters(latitude: Double, longitude: Double, otherLatitude: Double, otherLongitude: Double): Double {
        val latitudes = Math.toRadians(otherLatitude - latitude)
        val longitudes = Math.toRadians(otherLongitude - longitude)
        val a = sin(latitudes / 2) * sin(latitudes / 2) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(otherLatitude)) * sin(longitudes / 2) * sin(longitudes / 2)
        return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun location(latitude: Double, longitude: Double, accuracy: Double): EmbeddedMapLocation? {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0
        ) return null
        return EmbeddedMapLocation(
            latitude.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT),
            longitude,
            accuracy.takeIf { it.isFinite() && it >= 0.0 },
        )
    }

    fun isDocument(url: String): Boolean = url == DOCUMENT_URL

    fun isCopyright(url: String): Boolean = url == COPYRIGHT_URL

    fun resource(url: String): Resource {
        val uri = try { URI(url) } catch (_: Exception) { return Resource.BLOCKED }
        if (uri.scheme != "https" || uri.rawUserInfo != null ||
            uri.port !in listOf(-1, 443) || uri.rawQuery != null || uri.rawFragment != null
        ) return Resource.BLOCKED
        val path = uri.rawPath ?: return Resource.BLOCKED
        if (uri.host == "appassets.androidplatform.net" && assetPath.matches(path) &&
            path.split('/').none { it == "." || it == ".." }
        ) return Resource.BUNDLED_ASSET
        if (uri.host != "tile.openstreetmap.org") return Resource.BLOCKED
        val parts = tilePath.matchEntire(path)?.groupValues ?: return Resource.BLOCKED
        val zoom = parts[1].toIntOrNull() ?: return Resource.BLOCKED
        if (zoom !in 0..19) return Resource.BLOCKED
        val lastTile = (1L shl zoom) - 1
        val x = parts[2].toLongOrNull() ?: return Resource.BLOCKED
        val y = parts[3].toLongOrNull() ?: return Resource.BLOCKED
        return if (x in 0..lastTile && y in 0..lastTile) Resource.TILE else Resource.BLOCKED
    }
}
