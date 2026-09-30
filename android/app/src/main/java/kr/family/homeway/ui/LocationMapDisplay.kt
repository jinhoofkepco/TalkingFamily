package kr.family.homeway.ui

import kr.family.homeway.data.FamilyEvent
import java.time.Instant
import java.time.ZoneId

/** A current point can be displayed before the independent historical page finishes loading. */
internal object LocationMapDisplay {
    fun latest(events: List<FamilyEvent>, head: FamilyEvent? = null): FamilyEvent? =
        (listOfNotNull(head) + events).mapNotNull { event ->
            event.toEmbeddedMapHistoryPoint()?.let { event to it.measuredAtMillis }
        }.maxByOrNull { it.second }?.first

    fun latestDay(event: FamilyEvent?, zone: ZoneId = ZoneId.systemDefault()): String? =
        event?.toEmbeddedMapHistoryPoint()?.let {
            Instant.ofEpochMilli(it.measuredAtMillis).atZone(zone).toLocalDate().toString()
        }

    fun history(events: List<FamilyEvent>, head: FamilyEvent?, followingLatest: Boolean,
        selectedDay: String, zone: ZoneId = ZoneId.systemDefault(), pinned: FamilyEvent? = null): List<FamilyEvent> {
        val current = if (followingLatest) latest(events, head) else pinned
        if (current == null) return events
        val currentDay = if (followingLatest) latestDay(current, zone) else eventDay(current, zone)
        if (currentDay == null) return events
        // Never join different dates into a fictitious path while the new page is still loading.
        if (currentDay != selectedDay) return listOf(current)
        return events.filter { it.id != current.id && eventDay(it, zone) == currentDay } + current
    }

    private fun eventDay(event: FamilyEvent, zone: ZoneId): String? = runCatching {
        Instant.parse(event.measuredAt).atZone(zone).toLocalDate().toString()
    }.getOrNull()
}
