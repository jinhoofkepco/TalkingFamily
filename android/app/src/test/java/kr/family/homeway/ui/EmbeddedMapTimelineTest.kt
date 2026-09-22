package kr.family.homeway.ui

import kr.family.homeway.data.FamilyEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EmbeddedMapTimelineTest {
    private fun gps(id: String, at: String) = FamilyEvent(id, "location", JSONObject()
        .put("latitude", 10.0).put("longitude", 20.0).put("accuracy", 12.0)
        .put("source", "automatic").put("capturedAt", at), "child", at, "relayed")

    private fun vertical(id: String, at: String, phase: String = "ascent_started", meters: Double = 2.4) =
        FamilyEvent(id, "vertical", JSONObject().put("measuredAt", at).put("phase", phase)
            .put("confidence", "estimated").put("relativeMeters", meters), "child", at, "relayed")

    @Test fun verticalEventsEnterTheTimeBarWithoutFabricatingGpsPathPoints() {
        val timeline = EmbeddedMapTimeline.from(listOf(
            gps("later", "2026-09-23T01:10:00Z"),
            vertical("stairs", "2026-09-23T01:06:00Z"),
            gps("earlier", "2026-09-23T01:05:00Z"),
        ))
        assertEquals(listOf("earlier", "stairs", "later"), timeline.records.map { it.event.id })
        assertEquals(2, timeline.locations.size)
        assertEquals(listOf(0, 0, 1), timeline.records.map { it.locationIndex })
        assertEquals("올라가기 시작 · 추정", timeline.records[1].vertical!!.label)
        assertEquals(2.4, timeline.records[1].vertical!!.relativeMeters, 0.0)
        assertEquals(timeline.records[0].measuredAtMillis, timeline.locations[0].measuredAtMillis)
    }

    @Test fun missingEarlierGpsNeverBorrowsAFuturePositionOrEventCoordinates() {
        val beforeGps = vertical("before", "2026-09-23T01:04:00Z")
        beforeGps.payload.put("latitude", 40.0).put("longitude", 50.0)
        val timeline = EmbeddedMapTimeline.from(listOf(beforeGps, gps("later", "2026-09-23T01:05:00Z")))
        assertNull(timeline.records.first().locationIndex)
        assertEquals(1, timeline.locations.size)
        assertEquals(10.0, timeline.locations.single().latitude, 0.0)
    }

    @Test fun gpsReferenceExpiresAfterFiveMinutesAndVerticalRecordsRemainSelectable() {
        val timeline = EmbeddedMapTimeline.from(listOf(
            gps("gps", "2026-09-23T01:00:00Z"),
            vertical("edge", "2026-09-23T01:05:00Z"),
            vertical("expired", "2026-09-23T01:05:00.001Z", "descent_finished", -3.0),
        ))
        assertEquals(0, timeline.records[1].locationIndex)
        assertNull(timeline.records[2].locationIndex)
        assertEquals("내려가기 종료 · 추정", timeline.records[2].vertical!!.label)
        assertEquals(3, timeline.records.size)
    }

    @Test fun equivalentTimestampsSortGpsFirstThenStableIdsRegardlessOfInputOrder() {
        val a = vertical("a", "2026-09-23T10:00:00+09:00")
        val b = vertical("b", "2026-09-23T01:00:00.000Z")
        val fix = gps("z", "2026-09-23T01:00:00Z")
        val first = EmbeddedMapTimeline.from(listOf(b, a, fix))
        val reversed = EmbeddedMapTimeline.from(listOf(fix, a, b))
        assertEquals(listOf("z", "a", "b"), first.records.map { it.event.id })
        assertEquals(first.records.map { it.event.id }, reversed.records.map { it.event.id })
        assertEquals(listOf(0, 0, 0), first.records.map { it.locationIndex })
    }

    @Test fun reversalAtOnePivotShowsTheFinishedDirectionBeforeTheNewStartRegardlessOfIds() {
        val at = "2026-09-23T01:00:00Z"
        val fix = gps("z-gps", at)
        val finished = vertical("z-ascent-finish", at, "ascent_finished", 4.0)
        val started = vertical("a-descent-start", at, "descent_started", 0.0)
        val first = EmbeddedMapTimeline.from(listOf(started, finished, fix))
        val reversed = EmbeddedMapTimeline.from(listOf(fix, finished, started))
        assertEquals(listOf("z-gps", "z-ascent-finish", "a-descent-start"), first.records.map { it.event.id })
        assertEquals(first.records.map { it.event.id }, reversed.records.map { it.event.id })
        assertEquals(listOf(0, 0, 0), first.records.map { it.locationIndex })
        assertEquals("올라가기 종료 · 추정", first.records[1].vertical!!.label)
        assertEquals("내려가기 시작 · 추정", first.records[2].vertical!!.label)
    }

    @Test fun malformedVerticalMetadataCannotBreakOrInventTheTimeline() {
        val invalid = listOf(
            vertical("time", "not-a-time"),
            vertical("phase", "2026-09-23T01:00:00Z", "stairs_confirmed"),
            vertical("confidence", "2026-09-23T01:00:00Z").also { it.payload.put("confidence", "confirmed") },
            vertical("meters", "2026-09-23T01:00:00Z").also { it.payload.put("relativeMeters", "3.0") },
            vertical("range", "2026-09-23T01:00:00Z", meters = 10_001.0),
        )
        assertTrue(EmbeddedMapTimeline.from(invalid).records.isEmpty())
        val retained = EmbeddedMapTimeline.from(invalid + gps("valid", "2026-09-23T01:00:00Z"))
        assertEquals(listOf("valid"), retained.records.map { it.event.id })
    }

    @Test fun sensorEvidenceIsOptionalAndNeverLabelsTheMovementAsConfirmedStairs() {
        val event = vertical("evidence", "2026-09-23T01:00:00Z")
        assertNull(event.verticalMapActivity()!!.evidenceLabel)
        event.payload.put("evidence", "barometer_steps")
        assertEquals("걸음·기압 근거", event.verticalMapActivity()!!.evidenceLabel)
        assertTrue(event.verticalMapActivity()!!.label.endsWith("추정"))
        event.payload.put("evidence", "barometer_motion")
        assertEquals("기압·움직임 근거", event.verticalMapActivity()!!.evidenceLabel)
        event.payload.put("evidence", "stairs_confirmed")
        assertNull(event.verticalMapActivity()!!.evidenceLabel)
    }
}
