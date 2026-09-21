package kr.family.homeway.data

import java.time.Instant

internal data class ChatHistoryWindow(val events: List<FamilyEvent>, val next: FamilyEvent?)

/** Keeps a user-expanded conversation stable while refreshed pages update delivery information. */
internal object ChatHistoryPaging {
    fun ordered(events: List<FamilyEvent>): List<FamilyEvent> = events.distinctBy { it.id }
        .map { event -> runCatching {
            val instant = Instant.parse(event.measuredAt)
            // The existing private-chat cursor uses milliseconds; use its exact tie-breaking order.
            if (event.kind == "chat" && event.roomId == null) Instant.ofEpochMilli(instant.toEpochMilli()) else instant
        }.getOrDefault(Instant.EPOCH) to event }
        .sortedWith(compareBy<Pair<Instant, FamilyEvent>> { it.first }.thenBy { it.second.id })
        .map { it.second }

    fun merge(previous: List<FamilyEvent>, loaded: List<FamilyEvent>, append: Boolean, moreAvailable: Boolean): ChatHistoryWindow {
        val chronological = ordered(loaded)
        val oldBoundary = previous.firstOrNull()?.id
        val boundary = if (!append && oldBoundary != null) chronological.indexOfFirst { it.id == oldBoundary } else -1
        // A refresh may need one overlapping SQL page. Keep its newer records, not its extra older rows.
        val events = when {
            append -> ordered(loaded + previous)
            boundary > 0 -> chronological.drop(boundary)
            else -> chronological
        }
        return ChatHistoryWindow(events, events.firstOrNull().takeIf { moreAvailable || boundary > 0 })
    }
}
