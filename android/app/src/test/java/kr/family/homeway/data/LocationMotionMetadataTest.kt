package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocationMotionMetadataTest {
    private fun event(payload: JSONObject) = FamilyEvent("11111111-1111-4111-8111-111111111111", "location", payload,
        "child", "2026-09-22T01:00:00Z", "pending")
    private fun raw() = JSONObject().put("latitude", 37.0).put("longitude", 127.0).put("accuracy", 20)
        .put("capturedAt", "2026-09-22T01:00:00Z").put("source", "automatic")
    private fun estimated() = raw().put("displayLatitude", 37.0001).put("displayLongitude", 127.0)
        .put("displayAccuracy", 40).put("positionAdjusted", true).put("motion", "still")
        .put("stationarySince", "2026-09-22T00:40:00Z")

    @Test fun `fresh raw fix and bounded stationary estimate both survive validation`() {
        val p = TelegramLedger.validate(event(estimated())).payload
        assertEquals(37.0, p.getDouble("latitude"), 0.0)
        assertEquals(37.0001, p.getDouble("displayLatitude"), 0.0)
        assertEquals(20.0, p.getDouble("accuracy"), 0.0)
        assertEquals("2026-09-22T00:40:00Z", p.getString("stationarySince"))
    }

    @Test fun `old records keep their original fields without invented motion`() {
        val p = TelegramLedger.validate(event(raw())).payload
        assertEquals(5, p.length())
        assertFalse(p.has("motion"))
    }

    @Test fun `malformed estimates cannot discard valid GPS or become map coordinates`() {
        val cases = listOf(
            estimated().put("displayLatitude", 91), estimated().put("displayLongitude", "127.0"),
            estimated().put("displayLatitude", 38.0), estimated().put("displayAccuracy", 20),
            estimated().put("positionAdjusted", "true"), estimated().put("motion", "vehicle"),
            estimated().put("stationarySince", "2026-09-22T01:01:00Z"),
            estimated().put("stationarySince", "bad"), estimated().put("positionAdjusted", false),
        )
        cases.forEach {
            val p = TelegramLedger.validate(event(it)).payload
            assertEquals(37.0, p.getDouble("latitude"), 0.0)
            assertFalse(p.toString(), p.has("displayLatitude"))
            assertFalse(p.toString(), p.has("stationarySince"))
        }
    }

    @Test fun `stationary duration can exist when fresh coordinates already equal the anchor`() {
        val p = TelegramLedger.validate(event(estimated().put("displayLatitude", 37.0).put("positionAdjusted", false))).payload
        assertTrue(p.has("stationarySince"))
        assertFalse(p.getBoolean("positionAdjusted"))
    }

    @Test fun `manual shares do not inherit automatic stationary metadata`() {
        val p = TelegramLedger.validate(event(estimated().put("source", "manual"))).payload
        assertFalse(p.has("motion"))
        assertFalse(p.has("displayLatitude"))
    }

    @Test fun `old receiver retry after upgrade is idempotent without overwriting its record`() {
        val old = TelegramLedger.apply(TelegramLedger.emptyState(), event(raw()))
        val repeated = TelegramLedger.apply(old, event(estimated()))
        assertEquals(old.toString(), repeated.toString())
        assertEquals(1, repeated.getJSONArray("events").length())
        assertThrows(IllegalArgumentException::class.java) {
            TelegramLedger.apply(old, event(estimated().put("latitude", 37.01)))
        }
    }

    @Test fun `new receiver preserves estimates on a retry without the extension`() {
        val current = TelegramLedger.apply(TelegramLedger.emptyState(), event(estimated()))
        val repeated = TelegramLedger.apply(current, event(raw()))
        assertEquals(current.toString(), repeated.toString())
        assertTrue(repeated.getJSONObject("latestLocation").getJSONObject("payload").has("stationarySince"))
    }
}
