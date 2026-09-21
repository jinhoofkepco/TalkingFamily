package kr.family.homeway.ui

import kr.family.homeway.data.FamilyEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedMapRecordTest {
    private fun event(payload: JSONObject) = FamilyEvent("map-fixture", "location", payload, "child",
        "2026-09-22T01:10:00Z", "relayed")

    private fun payload() = JSONObject()
        .put("latitude", 37.5501).put("longitude", 126.98).put("accuracy", 10.0)
        .put("capturedAt", "2026-09-22T01:10:00Z").put("source", "automatic")
        .put("displayLatitude", 37.55).put("displayLongitude", 126.98).put("displayAccuracy", 30.0)
        .put("positionAdjusted", true).put("motion", "still").put("stationarySince", "2026-09-22T01:00:00Z")

    @Test fun mapsValidEstimatesAndRetainsTheRawMeasurement() {
        val source = payload()
        val raw = event(source).toEmbeddedMapHistoryPoint()!!
        val map = EmbeddedMapPolicy.historyPoint(raw)!!
        assertEquals(37.5501, raw.latitude, 0.0)
        assertEquals(37.5501, source.getDouble("latitude"), 0.0)
        assertEquals(37.55, map.location.latitude, 0.0)
        assertTrue(map.positionAdjusted)
        assertEquals("정지 추정 · 약 10분", map.activityLabel())
    }

    @Test fun oldRecordsRemainRawWithoutInferredMotionOrDwell() {
        val legacy = JSONObject().put("lat", 37.55).put("lng", 126.98).put("accuracy", 15.0)
        val map = EmbeddedMapPolicy.historyPoint(event(legacy).toEmbeddedMapHistoryPoint()!!)!!
        assertEquals(37.55, map.location.latitude, 0.0)
        assertEquals(15.0, map.location.accuracy!!, 0.0)
        assertFalse(map.positionAdjusted)
        assertNull(map.activityLabel())
        assertNull(map.stationarySinceMillis)
    }

    @Test fun manualAndMalformedMetadataCannotMoveTheRawMapPoint() {
        val invalid = listOf(
            payload().put("source", "manual"),
            payload().put("displayLatitude", "37.55"),
            payload().put("positionAdjusted", "true"),
            payload().put("stationarySince", "2026-09-22T02:00:00Z"),
            payload().put("stationarySince", "invalid timestamp"),
            payload().put("motion", "STILL"),
        )
        for (source in invalid) {
            val map = EmbeddedMapPolicy.historyPoint(event(source).toEmbeddedMapHistoryPoint()!!)!!
            assertEquals(37.5501, map.location.latitude, 0.0)
            assertEquals(10.0, map.location.accuracy!!, 0.0)
            assertFalse(map.positionAdjusted)
            assertNull(map.stationarySinceMillis)
        }
    }
}
