package kr.family.homeway.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class EmbeddedMapPolicyTest {
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
