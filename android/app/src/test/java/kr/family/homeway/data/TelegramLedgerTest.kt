package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TelegramLedgerTest {
    private val rewardId = "11111111-1111-4111-8111-111111111111"
    private val at = "2026-09-21T06:00:00Z"
    private fun event(kind: String, payload: JSONObject, sender: String = "guardian", id: String = UUID.randomUUID().toString()) =
        FamilyEvent(id, kind, payload, sender, at, "pending")
    private fun reward(name: String = "함께 산책", cost: Int = 2) = event("reward_upsert",
        JSONObject().put("rewardId", rewardId).put("name", name).put("cost", cost))
    private fun award() = event("sticker_award", JSONObject().put("count", 1).put("reason", "약속을 지켰어요"))
    private fun request(name: String = "함께 산책", cost: Int = 2) = event("sticker_redeem_request",
        JSONObject().put("rewardId", rewardId).put("reward", name).put("cost", cost), "child")
    private fun decision(request: FamilyEvent, accepted: Boolean = true) = event("sticker_redeem_approve",
        JSONObject().put("requestId", request.id).put("accepted", accepted))
    private fun stateWithStickers(count: Int): JSONObject {
        var state = TelegramLedger.emptyState()
        repeat(count) { state = TelegramLedger.apply(state, award()) }
        return state
    }

    @Test fun `new family starts empty with direct Telegram transport`() {
        val snapshot = FamilySnapshot.parse(TelegramLedger.emptyState())
        assertEquals("telegram", snapshot.transport)
        assertTrue(snapshot.events.isEmpty())
        assertTrue(snapshot.rewards.isEmpty())
        assertTrue(snapshot.redemptions.isEmpty())
        assertEquals(0, snapshot.stickerBalance)
        assertFalse(snapshot.sharingEnabled)
    }

    @Test fun `repeated award cannot grant twice even after its delivery status changes`() {
        val event = award()
        val state = TelegramLedger.apply(TelegramLedger.emptyState(), event)
        val delivered = TelegramLedger.apply(JSONObject(state.toString()), event.copy(delivery = "relayed", deliveryError = "old error"))
        assertEquals(1, delivered.getInt("stickerBalance"))
        assertEquals(1, delivered.getJSONArray("events").length())
        assertTrue(TelegramLedger.contains(delivered, event.id.uppercase()))
        assertFalse(TelegramLedger.contains(delivered, UUID.randomUUID().toString()))
        val replacement = event.copy(payload = JSONObject().put("count", 1).put("reason", "다른 이유"))
        assertThrows(IllegalArgumentException::class.java) { TelegramLedger.apply(state, replacement) }
    }

    @Test fun `late request preserves its snapshot after guardian changes then deletes catalog entry`() {
        var guardian = TelegramLedger.apply(stateWithStickers(2), reward())
        var child = JSONObject(guardian.toString())
        val pending = request()
        child = TelegramLedger.apply(child, pending)
        val changed = reward("새 약속", 9)
        val removed = event("reward_delete", JSONObject().put("rewardId", rewardId))
        guardian = TelegramLedger.apply(TelegramLedger.apply(guardian, changed), removed)
        guardian = TelegramLedger.apply(guardian, pending)
        child = TelegramLedger.apply(TelegramLedger.apply(child, changed), removed)
        val approval = decision(pending)
        guardian = TelegramLedger.apply(guardian, approval)
        child = TelegramLedger.apply(child, approval)
        for (state in listOf(guardian, child)) {
            val snapshot = FamilySnapshot.parse(state)
            assertEquals(Redemption(pending.id, "함께 산책", 2, "approved"), snapshot.redemptions.single())
            assertEquals(0, snapshot.stickerBalance)
            assertTrue(snapshot.rewards.isEmpty())
        }
    }

    @Test fun `requests do not reserve or spend stickers and guardian rechecks every approval`() {
        var state = stateWithStickers(3)
        val first = request()
        val second = request()
        state = TelegramLedger.apply(TelegramLedger.apply(state, first), second)
        assertEquals(3, state.getInt("stickerBalance"))
        state = TelegramLedger.apply(state, decision(first))
        val before = state.toString()
        assertThrows(IllegalArgumentException::class.java) { TelegramLedger.apply(state, decision(second)) }
        assertEquals(before, state.toString())
        state = TelegramLedger.apply(state, decision(second, accepted = false))
        assertEquals(1, state.getInt("stickerBalance"))
        assertEquals(listOf("approved", "rejected"), FamilySnapshot.parse(state).redemptions.map { it.status })
    }

    @Test fun `a repeated decision under a new ID cannot debit again or reverse a rejection`() {
        val request = request()
        var state = TelegramLedger.apply(stateWithStickers(5), request)
        state = TelegramLedger.apply(state, decision(request))
        state = TelegramLedger.apply(state, decision(request))
        state = TelegramLedger.apply(state, decision(request, accepted = false))
        assertEquals(3, state.getInt("stickerBalance"))
        assertEquals("approved", FamilySnapshot.parse(state).redemptions.single().status)
        val rejected = request()
        state = TelegramLedger.apply(state, rejected)
        state = TelegramLedger.apply(state, decision(rejected, accepted = false))
        state = TelegramLedger.apply(state, decision(rejected))
        assertEquals(3, state.getInt("stickerBalance"))
        assertEquals("rejected", FamilySnapshot.parse(state).redemptions.last().status)
    }

    @Test fun `unfunded request can arrive asynchronously but cannot be approved or target replaced`() {
        val pending = request()
        val empty = TelegramLedger.emptyState()
        var state = TelegramLedger.apply(empty, pending)
        assertEquals("pending", FamilySnapshot.parse(state).redemptions.single().status)
        assertThrows(IllegalArgumentException::class.java) { TelegramLedger.apply(state, decision(pending)) }
        assertThrows(IllegalArgumentException::class.java) { TelegramLedger.apply(state, decision(request())) }
        val replacement = pending.copy(payload = JSONObject().put("rewardId", rewardId).put("reward", "변경된 약속").put("cost", 1))
        assertThrows(IllegalArgumentException::class.java) { TelegramLedger.apply(state, replacement) }
        state = TelegramLedger.apply(state, decision(pending, accepted = false))
        assertEquals(0, state.getInt("stickerBalance"))
        assertEquals(0, empty.getJSONArray("events").length())
    }

    @Test fun `permissions reject sender role confusion and unknown event types`() {
        for (event in listOf(award(), reward(), event("reward_delete", JSONObject().put("rewardId", rewardId)), decision(request()))) {
            assertThrows(IllegalArgumentException::class.java) { TelegramLedger.validate(event.copy(sender = "child")) }
        }
        assertThrows(IllegalArgumentException::class.java) { TelegramLedger.validate(request().copy(sender = "guardian")) }
        assertThrows(IllegalArgumentException::class.java) { TelegramLedger.validate(event("sharing_status", JSONObject().put("enabled", true))) }
        assertThrows(IllegalArgumentException::class.java) { TelegramLedger.validate(event("unknown", JSONObject())) }
        assertThrows(IllegalArgumentException::class.java) { TelegramLedger.validate(award().copy(sender = "stranger")) }
    }

    @Test fun `malformed payloads have strict types and transport limits`() {
        val malformed = listOf(
            reward(cost = 0), reward(cost = 1000), reward(" "), reward("a".repeat(61)),
            reward().copy(payload = reward().payload.put("cost", 1.5)),
            reward().copy(payload = reward().payload.put("cost", "2")),
            award().copy(payload = award().payload.put("count", 2)),
            event("sharing_status", JSONObject().put("enabled", "true"), "child"),
            event("chat", JSONObject().put("text", "a".repeat(1501))),
            event("chat", JSONObject().put("text", "ok").put("unknown", "a".repeat(4000))),
            award().copy(id = "not-a-uuid"), award().copy(createdAt = "2026-09-21T06:00:00"),
            event("heartbeat", JSONObject().put("recordedAt", at).put("batteryPercent", 101), "child"),
            event("location", JSONObject().put("latitude", 91).put("longitude", 10).put("accuracy", 2)
                .put("source", "manual").put("capturedAt", at), "child"),
            event("vertical", JSONObject().put("phase", "ascent_started").put("confidence", "estimated")
                .put("relativeMeters", 2).put("measuredAt", at).put("latitude", 10), "child"),
        )
        for (input in malformed) {
            assertThrows("Accepted ${input.kind}: ${input.payload}", IllegalArgumentException::class.java) { TelegramLedger.validate(input) }
        }
    }

    @Test fun `chat location heartbeat vertical and sharing survive JSON persistence without aliases`() {
        val chat = event("chat", JSONObject().put("text", "  안녕  ").put("extra", "discard"), "child")
        val location = event("location", JSONObject().put("latitude", 37.56).put("longitude", 126.97)
            .put("accuracy", 12).put("source", "manual").put("capturedAt", at), "child")
        val heartbeat = event("heartbeat", JSONObject().put("recordedAt", at).put("batteryPercent", 90), "child")
        val vertical = event("vertical", JSONObject().put("phase", "ascent_finished").put("confidence", "estimated")
            .put("relativeMeters", 6.3).put("measuredAt", at), "child")
        var state = TelegramLedger.emptyState()
        for (input in listOf(chat, location, heartbeat, vertical,
            event("sharing_status", JSONObject().put("enabled", true), "child"))) {
            state = TelegramLedger.apply(state, input)
        }
        chat.payload.put("text", "mutated")
        val snapshot = FamilySnapshot.parse(JSONObject(state.toString()))
        assertEquals(5, snapshot.events.size)
        assertEquals("안녕", snapshot.events.first().payload.getString("text"))
        assertFalse(snapshot.events.first().payload.has("extra"))
        assertTrue(snapshot.sharingEnabled)
        val oldLocation = location.copy(id = UUID.randomUUID().toString(), payload = JSONObject(location.payload.toString()).put("capturedAt", "2026-09-21T05:00:00Z"))
        state = TelegramLedger.apply(state, oldLocation)
        assertEquals(location.id, state.getJSONObject("latestLocation").getString("id"))
    }

    @Test fun `trimming visible history does not erase financial replay protection or latest readings`() {
        val original = award()
        var state = TelegramLedger.apply(TelegramLedger.emptyState(), original)
        val filler = event("chat", JSONObject().put("text", "sample"), "child")
        val history = state.getJSONArray("events")
        repeat(1000) { history.put(filler.copy(id = UUID.randomUUID().toString()).json()) }
        state = TelegramLedger.apply(state, event("heartbeat", JSONObject().put("recordedAt", at), "child"))
        assertEquals(1000, state.getJSONArray("events").length())
        assertFalse(FamilySnapshot.parse(state).events.any { it.id == original.id })
        state = TelegramLedger.apply(JSONObject(state.toString()), original)
        assertEquals(1, state.getInt("stickerBalance"))
        assertTrue(TelegramLedger.contains(state, original.id))
        assertTrue(state.has("latestHeartbeat"))
    }
}
