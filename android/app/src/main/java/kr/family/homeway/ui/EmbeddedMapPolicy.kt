package kr.family.homeway.ui

import java.net.URI

data class EmbeddedMapHistoryPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val measuredAtMillis: Long,
)

internal data class ValidatedMapHistoryPoint(val location: EmbeddedMapLocation, val measuredAtMillis: Long) {
    fun javascriptTuple(): String = "[${location.latitude},${location.longitude},${location.accuracy ?: "null"},$measuredAtMillis]"
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
            val location = location(point.latitude, point.longitude, point.accuracy) ?: return@mapIndexedNotNull null
            if (point.measuredAtMillis !in 0L..253402300799999L) return@mapIndexedNotNull null
            index to ValidatedMapHistoryPoint(location, point.measuredAtMillis)
        }
        return ValidatedMapHistory(valid.map { it.second }, valid.indexOfFirst { it.first == selectedIndex })
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
