package kr.family.homeway.ui

import kr.family.homeway.data.FamilyEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class LocationMapDisplayTest {
    private val utc = ZoneId.of("UTC")
    private fun event(id: String, at: String) = FamilyEvent(id, "location", JSONObject()
        .put("latitude", 37.0).put("longitude", 127.0).put("accuracy", 12.0)
        .put("capturedAt", at), "child", at, "relayed")

    @Test fun currentHeadDisplaysBeforeAnyHistoryPageArrives() {
        val head = event("head", "2026-09-30T10:00:00Z")
        assertEquals(head, LocationMapDisplay.latest(emptyList(), head))
        assertEquals(listOf(head), LocationMapDisplay.history(emptyList(), head, true, "2026-09-30", utc))
    }

    @Test fun olderHistoryArrivalCannotReplaceTheCurrentLocation() {
        val head = event("head", "2026-09-30T10:00:00Z")
        val older = event("old", "2026-09-30T09:00:00Z")
        assertEquals(head, LocationMapDisplay.latest(listOf(older), head))
        assertEquals(older, LocationMapDisplay.latest(listOf(older), event("stale", "2026-09-30T08:00:00Z")))
    }

    @Test fun manuallySelectedHistoryDoesNotGainNewLivePoints() {
        val old = event("old", "2026-09-30T09:00:00Z")
        val head = event("head", "2026-09-30T10:00:00Z")
        assertEquals(listOf(old), LocationMapDisplay.history(listOf(old), head, false, "2026-09-30", utc))
    }

    @Test fun selectedLivePointStaysVisibleWithoutHistoryWhileTheNextLivePointArrives() {
        val pinned = event("selected-head", "2026-09-30T09:00:00Z")
        val newer = event("new-head", "2026-09-30T10:00:00Z")
        assertEquals(listOf(pinned), LocationMapDisplay.history(emptyList(), newer, false, "2026-09-30", utc, pinned))
        assertTrue(LocationMapDisplay.history(emptyList(), newer, false, "2026-09-30", utc, null).isEmpty())
    }

    @Test fun pinnedPointIsDeduplicatedWhenItsHistoricalOriginalArrives() {
        val pinned = event("selected-head", "2026-09-30T09:00:00Z")
        val archived = pinned.copy(senderName = "서아")
        val newer = event("new-head", "2026-09-30T10:00:00Z")
        val displayed = LocationMapDisplay.history(listOf(archived), newer, false, "2026-09-30", utc, pinned)
        assertEquals(1, displayed.size)
        assertSame(pinned, displayed.single())
    }

    @Test fun pinDoesNotDrawAcrossDatesDuringAnAutomaticPageTransition() {
        val pinned = event("selected-head", "2026-09-29T09:00:00Z")
        val nextDay = event("new-head", "2026-09-30T10:00:00Z")
        assertEquals(listOf(pinned), LocationMapDisplay.history(listOf(nextDay), nextDay, false, "2026-09-30", utc, pinned))
    }

    @Test fun latestModeNeverDrawsAPathBetweenDifferentDates() {
        val old = event("old", "2026-09-29T09:00:00Z")
        val head = event("head", "2026-09-30T10:00:00Z")
        assertEquals(listOf(head), LocationMapDisplay.history(listOf(old), head, true, "2026-09-29", utc))
    }

    @Test fun matchingHistoryIdIsNotDuplicatedAndHeadWinsEqualTime() {
        val archived = event("head", "2026-09-30T10:00:00Z")
        val head = archived.copy(senderName = "서아")
        assertSame(head, LocationMapDisplay.latest(listOf(archived), head))
        val displayed = LocationMapDisplay.history(listOf(archived), head, true, "2026-09-30", utc)
        assertEquals(1, displayed.size)
        assertSame(head, displayed.single())
    }

    @Test fun invalidCoordinatesAreIgnoredAndLocalDayUsesMeasurementTime() {
        val head = event("head", "2026-09-30T23:30:00Z")
        assertEquals("2026-10-01", LocationMapDisplay.latestDay(head, ZoneId.of("Asia/Seoul")))
        val bad = event("bad", "2026-10-01T00:00:00Z").also { it.payload.put("latitude", 100.0) }
        assertEquals(head, LocationMapDisplay.latest(listOf(head), bad))
        assertNull(LocationMapDisplay.latestDay(bad, utc))
    }
}
