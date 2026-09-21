package kr.family.homeway.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class EmbeddedMapPolicyTest {
    @Test fun stabilizedStationaryPointsShareAnAnchorWhileMovementUsesItsFreshPosition() {
        val points = listOf(
            EmbeddedMapHistoryPoint(37.5501, 126.98, 10.0, 300_000,
                37.55, 126.98, 30.0, true, "still", 0),
            EmbeddedMapHistoryPoint(37.5499, 126.98, 10.0, 600_000,
                37.55, 126.98, 30.0, true, "still", 0),
            EmbeddedMapHistoryPoint(37.552, 126.981, 12.0, 900_000,
                37.552, 126.981, 12.0, false, "walking"),
        )
        val history = EmbeddedMapPolicy.history(points, 1)
        assertEquals(1, history.selectedIndex)
        assertEquals(history.points[0].location, history.points[1].location)
        assertEquals(37.552, history.points[2].location.latitude, 0.0)
        assertEquals(30.0, history.points[1].location.accuracy!!, 0.0)
        assertEquals("정지 추정 · 약 10분", history.points[1].activityLabel())
        assertEquals("걷는 중 추정", history.points[2].activityLabel())
        assertEquals(37.5499, points[1].latitude, 0.0) // Raw evidence remains unchanged.
    }

    @Test fun malformedAdjustmentFallsBackToRawCoordinatesWithoutDiscardingTheRecord() {
        val valid = EmbeddedMapHistoryPoint(37.5501, 126.98, 10.0, 600_000,
            37.55, 126.98, 30.0, true, "still", 0)
        val malformed = listOf(
            valid.copy(displayLongitude = null),
            valid.copy(displayLatitude = Double.NaN),
            valid.copy(displayAccuracy = Double.POSITIVE_INFINITY),
            valid.copy(displayAccuracy = 10.0), // Uncertainty must cover the displaced raw fix.
            valid.copy(displayLatitude = 38.0, displayAccuracy = 100_000.0),
            valid.copy(motion = "vehicle"),
            valid.copy(stationarySinceMillis = 600_001),
            valid.copy(positionAdjusted = false),
        )
        for (point in malformed) {
            val actual = EmbeddedMapPolicy.historyPoint(point)!!
            assertEquals(37.5501, actual.location.latitude, 0.0)
            assertEquals(10.0, actual.location.accuracy!!, 0.0)
            assertFalse(actual.positionAdjusted)
            assertNull(actual.stationarySinceMillis)
        }
    }

    @Test fun stationaryDurationIsBoundToTheSelectedRecordEvenWhenCoordinatesWereNotAdjusted() {
        val point = EmbeddedMapHistoryPoint(37.55, 126.98, 10.0, 600_000,
            37.55, 126.98, 10.0, false, "still", 0)
        assertEquals("정지 추정 · 약 10분", EmbeddedMapPolicy.historyPoint(point)!!.activityLabel())
        assertEquals("정지 추정 · 약 5분", EmbeddedMapPolicy.historyPoint(point.copy(measuredAtMillis = 300_000))!!.activityLabel())
        assertEquals("정지 추정 · 1분 미만", EmbeddedMapPolicy.historyPoint(point.copy(measuredAtMillis = 30_000))!!.activityLabel())
        assertNull(EmbeddedMapPolicy.historyPoint(point.copy(motion = "unknown"))!!.activityLabel())
    }

    @Test fun historyKeepsSelectionAlignedWhenInvalidRecordsAreSkipped() {
        val points = listOf(
            EmbeddedMapHistoryPoint(Double.NaN, 127.0, 10.0, 1000),
            EmbeddedMapHistoryPoint(37.5, 127.0, 10.0, -1),
            EmbeddedMapHistoryPoint(37.55, 126.98, Double.NaN, 2000),
            EmbeddedMapHistoryPoint(37.56, 126.99, 12.0, 3000),
        )
        val history = EmbeddedMapPolicy.history(points, 3)
        assertEquals(2, history.points.size)
        assertEquals(1, history.selectedIndex)
        assertEquals("window.FamilyMap.setHistory([[37.55,126.98,null,2000], [37.56,126.99,12.0,3000]]);", history.javascriptCall())
        assertEquals(-1, EmbeddedMapPolicy.history(points, 0).selectedIndex)
        assertEquals(-1, EmbeddedMapPolicy.history(points, 99).selectedIndex)
    }

    @Test fun completeHistoryRemainsSelectableBeyondTheDrawnRouteLimit() {
        val points = List(2500) { EmbeddedMapHistoryPoint(37.5 + it / 100000.0, 127.0, 10.0, it.toLong()) }
        val history = EmbeddedMapPolicy.history(points, 2499)
        assertEquals(2500, history.points.size)
        assertEquals(2499, history.selectedIndex)
        assertEquals(2499L, history.points.last().measuredAtMillis)
    }

    @Test fun rejectsInvalidCoordinatesAndOmitsUnusableAccuracy() {
        for (latitude in listOf(Double.NaN, Double.POSITIVE_INFINITY, -90.001, 90.001)) {
            assertNull(EmbeddedMapPolicy.location(latitude, 127.0, 10.0))
        }
        for (longitude in listOf(Double.NaN, Double.NEGATIVE_INFINITY, -180.001, 180.001)) {
            assertNull(EmbeddedMapPolicy.location(37.0, longitude, 10.0))
        }
        for (accuracy in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(EmbeddedMapPolicy.location(37.0, 127.0, accuracy)!!.accuracy)
        }
        assertEquals(0.0, EmbeddedMapPolicy.location(37.0, 127.0, 0.0)!!.accuracy!!, 0.0)
    }

    @Test fun validPolarCoordinatesAreClampedToTheMapProjection() {
        assertEquals(85.05112878, EmbeddedMapPolicy.location(90.0, 180.0, 5.0)!!.latitude, 0.0)
        assertEquals(-85.05112878, EmbeddedMapPolicy.location(-90.0, -180.0, 5.0)!!.latitude, 0.0)
    }

    @Test fun javascriptPayloadStaysNumericWhenDeviceLocaleUsesDecimalCommas() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("window.FamilyMap.setLocation(37.55,126.98,12.5);",
                EmbeddedMapPolicy.location(37.55, 126.98, 12.5)!!.javascriptCall())
            assertEquals("window.FamilyMap.setLocation(37.55,126.98,null);",
                EmbeddedMapPolicy.location(37.55, 126.98, Double.NaN)!!.javascriptCall())
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun admitsOnlyBundledAssetsAndPublicMapTiles() {
        assertEquals(EmbeddedMapPolicy.Resource.BUNDLED_ASSET, EmbeddedMapPolicy.resource(EmbeddedMapPolicy.DOCUMENT_URL))
        assertEquals(EmbeddedMapPolicy.Resource.BUNDLED_ASSET,
            EmbeddedMapPolicy.resource("https://appassets.androidplatform.net/assets/family-map/images/marker-icon.png"))
        assertEquals(EmbeddedMapPolicy.Resource.TILE,
            EmbeddedMapPolicy.resource("https://tile.openstreetmap.org/15/27909/12667.png"))
        assertEquals(EmbeddedMapPolicy.Resource.TILE,
            EmbeddedMapPolicy.resource("https://tile.openstreetmap.org/0/0/0.png"))
        assertTrue(EmbeddedMapPolicy.isDocument(EmbeddedMapPolicy.DOCUMENT_URL))
        assertFalse(EmbeddedMapPolicy.isDocument("https://tile.openstreetmap.org/0/0/0.png"))
        assertTrue(EmbeddedMapPolicy.isCopyright(EmbeddedMapPolicy.COPYRIGHT_URL))
    }

    @Test fun rejectsTraversalAlternativeOriginsAndNonTileEndpoints() {
        val blocked = listOf(
            "http://tile.openstreetmap.org/0/0/0.png",
            "https://tile.openstreetmap.org.evil.example/0/0/0.png",
            "https://evil.example@tile.openstreetmap.org/0/0/0.png",
            "https://tile.openstreetmap.org:8443/0/0/0.png",
            "https://tile.openstreetmap.org/0/1/0.png",
            "https://tile.openstreetmap.org/20/0/0.png",
            "https://tile.openstreetmap.org/0/0/0.png?tracking=1",
            "https://tile.openstreetmap.org/0/0/0.png#fragment",
            "https://tile.openstreetmap.org/search",
            "https://a.tile.openstreetmap.org/0/0/0.png",
            "https://appassets.androidplatform.net/assets/secret.txt",
            "https://appassets.androidplatform.net/assets/family-map/../secret.txt",
            "https://appassets.androidplatform.net/assets/family-map/%2e%2e/secret.txt",
            "https://appassets.androidplatform.net/assets/family-map/%2fsecret.txt",
            "file:///android_asset/family-map/index.html",
            "content://kr.family.homeway/private",
            "javascript:alert(1)",
            "not a URI",
        )
        for (url in blocked) assertEquals(url, EmbeddedMapPolicy.Resource.BLOCKED, EmbeddedMapPolicy.resource(url))
        assertFalse(EmbeddedMapPolicy.isCopyright(EmbeddedMapPolicy.COPYRIGHT_URL + "?tracking=1"))
    }
}
