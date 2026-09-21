package kr.family.homeway.ui

import kr.family.homeway.data.FamilyEvent
import kr.family.homeway.data.LocationMotionMetadata
import org.json.JSONObject
import java.time.Instant

/** Keep raw measurements intact; optional display metadata is validated by the map policy. */
internal fun FamilyEvent.toEmbeddedMapHistoryPoint(): EmbeddedMapHistoryPoint? {
    if (kind != "location") return null
    val latitude = payload.optDouble("latitude", payload.optDouble("lat", Double.NaN))
    val longitude = payload.optDouble("longitude", payload.optDouble("lng", payload.optDouble("lon", Double.NaN)))
    val accuracy = payload.optDouble("accuracy", Double.NaN)
    if (EmbeddedMapPolicy.location(latitude, longitude, accuracy) == null) return null
    val measuredAt = runCatching { Instant.parse(payload.optString("capturedAt").ifBlank { createdAt }).toEpochMilli() }.getOrNull() ?: return null
    if (measuredAt !in 0L..253402300799999L) return null
    val metadata = JSONObject()
    if (accuracy.isFinite() && accuracy >= 0.0) {
        metadata.put("source", payload.optString("source"))
            .put("latitude", latitude).put("longitude", longitude).put("accuracy", accuracy)
            .put("capturedAt", Instant.ofEpochMilli(measuredAt).toString())
        LocationMotionMetadata.copyValidated(payload, metadata)
    }
    fun optionalNumber(key: String) = (metadata.opt(key) as? Number)?.toDouble()
    val stationarySince = (metadata.opt("stationarySince") as? String)?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
    }
    return EmbeddedMapHistoryPoint(
        latitude, longitude, accuracy, measuredAt,
        displayLatitude = optionalNumber("displayLatitude"),
        displayLongitude = optionalNumber("displayLongitude"),
        displayAccuracy = optionalNumber("displayAccuracy"),
        positionAdjusted = metadata.opt("positionAdjusted") == true,
        motion = metadata.opt("motion") as? String ?: "unknown",
        stationarySinceMillis = stationarySince,
    )
}
