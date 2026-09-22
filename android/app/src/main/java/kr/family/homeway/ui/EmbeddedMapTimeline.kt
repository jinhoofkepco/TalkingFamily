package kr.family.homeway.ui

import kr.family.homeway.data.FamilyEvent
import java.time.Instant

internal data class VerticalMapActivity(val label: String, val relativeMeters: Double, val evidenceLabel: String? = null)

internal data class EmbeddedTimelineRecord(
    val event: FamilyEvent,
    val measuredAtMillis: Long,
    val locationIndex: Int?,
    val vertical: VerticalMapActivity? = null,
)

/** Vertical readings have their own time, but never fabricate a GPS point or a path segment. */
internal data class EmbeddedMapTimeline(
    val records: List<EmbeddedTimelineRecord>,
    val locations: List<EmbeddedMapHistoryPoint>,
) {
    companion object {
        // A reference point older than the stationary location interval is not a usable map anchor.
        const val MAX_REFERENCE_AGE_MILLIS = 5 * 60_000L

        fun from(events: List<FamilyEvent>): EmbeddedMapTimeline {
            data class Candidate(val event: FamilyEvent, val at: Long, val point: EmbeddedMapHistoryPoint?, val vertical: VerticalMapActivity?)
            val candidates = events.mapNotNull { event ->
                if (event.kind == "location") {
                    val point = event.toEmbeddedMapHistoryPoint() ?: return@mapNotNull null
                    Candidate(event, point.measuredAtMillis, point, null)
                } else {
                    val vertical = event.verticalMapActivity() ?: return@mapNotNull null
                    val at = runCatching { Instant.parse(event.payload.optString("measuredAt").ifBlank { event.createdAt }).toEpochMilli() }
                        .getOrNull()?.takeIf { it in 0L..253402300799999L } ?: return@mapNotNull null
                    Candidate(event, at, null, vertical)
                }
            }.sortedWith(compareBy<Candidate> { it.at }.thenBy {
                when {
                    it.point != null -> 0
                    it.event.payload.optString("phase").endsWith("_finished") -> 1
                    else -> 2
                }
            }.thenBy { it.event.id })
            val locations = mutableListOf<EmbeddedMapHistoryPoint>()
            val records = candidates.map { candidate ->
                val index = if (candidate.point != null) {
                    locations.add(candidate.point)
                    locations.lastIndex
                } else locations.lastOrNull()?.takeIf {
                    candidate.at - it.measuredAtMillis in 0L..MAX_REFERENCE_AGE_MILLIS
                }?.let { locations.lastIndex }
                EmbeddedTimelineRecord(candidate.event, candidate.at, index, candidate.vertical)
            }
            return EmbeddedMapTimeline(records, locations)
        }
    }
}

internal fun FamilyEvent.verticalMapActivity(): VerticalMapActivity? {
    if (kind != "vertical" || payload.opt("confidence") != "estimated") return null
    val label = when (payload.optString("phase")) {
        "ascent_started" -> "올라가기 시작 · 추정"
        "ascent_finished" -> "올라가기 종료 · 추정"
        "descent_started" -> "내려가기 시작 · 추정"
        "descent_finished" -> "내려가기 종료 · 추정"
        else -> return null
    }
    val meters = (payload.opt("relativeMeters") as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it in -10_000.0..10_000.0 } ?: return null
    val evidenceLabel = when (payload.optString("evidence")) {
        "barometer_steps" -> "걸음·기압 근거"
        "barometer_motion" -> "기압·움직임 근거"
        else -> null
    }
    return VerticalMapActivity(label, meters, evidenceLabel)
}
