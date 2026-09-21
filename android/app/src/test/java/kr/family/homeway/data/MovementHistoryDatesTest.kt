package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class MovementHistoryDatesTest {
    private fun event(at: String, kind: String = "location") = FamilyEvent(UUID.randomUUID().toString(), kind,
        JSONObject().put(if (kind == "vertical") "measuredAt" else "capturedAt", at), "child", at, "relayed")

    @Test fun `local day uses captured time rather than delivery date and respects midnight`() {
        val before = event("2026-09-21T14:59:59Z").copy(createdAt = "2026-09-23T10:00:00Z")
        val after = event("2026-09-21T15:00:00Z", "vertical")
        val page = MovementHistoryDates.demoPage(listOf(before, after), "2026-09-22", zone = ZoneId.of("Asia/Seoul"))
        assertEquals(listOf("2026-09-22", "2026-09-21"), page.days)
        assertEquals(listOf(after.id), page.events.map { it.id })
    }

    @Test fun `initial selection finds newest historical day and explicit selection remains stable`() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.parse("2026-09-22")
        val days = listOf("2026-09-20", "2026-09-19")
        assertEquals("2026-09-20", MovementHistoryDates.selectDay(null, days, zone, today))
        assertEquals("2026-09-19", MovementHistoryDates.selectDay("2026-09-19", days, zone, today))
        assertEquals("2026-09-22", MovementHistoryDates.selectDay(null, emptyList(), zone, today))
    }

    @Test fun `demo date paging keeps equal-timestamp entries ordered and excludes telemetry`() {
        val events = List(4) { event("2026-09-21T10:00:00Z") } + event("2026-09-21T10:01:00Z", "heartbeat")
        val zone = ZoneId.of("UTC")
        val first = MovementHistoryDates.demoPage(events, "2026-09-21", zone = zone, limit = 2)
        val second = MovementHistoryDates.demoPage(events, first.day, first.next, zone, limit = 2)
        assertEquals(events.take(4).map { it.id }.sortedDescending(), (first.events + second.events).map { it.id })
        assertNotNull(first.next)
        assertNull(second.next)
    }

    @Test fun `DST fall-back repeated hour remains ordered by epoch on one local date`() {
        val early = event("2026-11-01T05:30:00Z")
        val late = event("2026-11-01T06:30:00Z")
        val page = MovementHistoryDates.demoPage(listOf(early, late), "2026-11-01", zone = ZoneId.of("America/New_York"))
        assertEquals(listOf(late.id, early.id), page.events.map { it.id })
        assertEquals(listOf("2026-11-01"), page.days)
    }
}
