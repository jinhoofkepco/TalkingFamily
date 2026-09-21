package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TelegramProtocolTest {
    private val peerId = 4000000000L
    private val eventId = "abcdef12-3456-4789-abcd-ef1234567890"
    private val createdAt = "2026-09-21T12:00:00Z"

    private fun familyEvent(kind: String = "chat", sender: String = "guardian", payload: JSONObject = JSONObject().put("text", "안녕")) =
        FamilyEvent(eventId, kind, payload, sender, createdAt, "queued", "untrusted delivery error")

    private fun update(text: String = TelegramProtocol.event(familyEvent())) = JSONObject()
        .put("update_id", 12345)
        .put("message", JSONObject()
            .put("from", JSONObject().put("id", peerId).put("is_bot", true))
            .put("chat", JSONObject().put("id", peerId).put("type", "private"))
            .put("text", text))

    @Test fun authenticatedPeerEventHasValidatedPayloadAndLocalDeliveryStatus() {
        val source = familyEvent(payload = JSONObject().put("text", "  안녕  ").put("unexpected", "discard"))
        val packet = TelegramProtocol.receive(update(TelegramProtocol.event(source)), peerId, "guardian")!!
        assertEquals("event", packet.type)
        assertEquals(eventId, packet.event!!.id)
        assertEquals("안녕", packet.event.payload.getString("text"))
        assertFalse(packet.event.payload.has("unexpected"))
        assertEquals("relayed", packet.event.delivery)
        assertNull(packet.event.deliveryError)
    }

    @Test fun rejectSpoofedSenderOrChatAndNonBotSender() {
        val wrongSender = update().apply { getJSONObject("message").getJSONObject("from").put("id", peerId + 1) }
        val wrongChat = update().apply { getJSONObject("message").getJSONObject("chat").put("id", peerId + 1) }
        val human = update().apply { getJSONObject("message").getJSONObject("from").put("is_bot", false) }
        val group = update().apply { getJSONObject("message").getJSONObject("chat").put("type", "group") }
        for (candidate in listOf(wrongSender, wrongChat, human, group)) {
            assertNull(TelegramProtocol.receive(candidate, peerId, "guardian"))
        }
        assertNull(TelegramProtocol.receive(update(), 0, "guardian"))
        assertNull(TelegramProtocol.receive(update(), -peerId, "guardian"))
    }

    @Test fun rejectForwardedAndUnrelatedMessagesWithoutReplying() {
        val forwarded = update().apply {
            getJSONObject("message").put("forward_origin", JSONObject().put("type", "user"))
        }
        val channel = JSONObject().put("channel_post", update().getJSONObject("message"))
        val hello = update(TelegramProtocol.envelope("hello").put("role", "guardian").toString())
        for (candidate in listOf(forwarded, channel, hello, update("/start"), JSONObject())) {
            assertNull(TelegramProtocol.receive(candidate, peerId, "guardian"))
        }
    }

    @Test fun rejectUnsupportedAndCoercedVersions() {
        for (version in listOf<Any>(1, 3, "2", 2.5, JSONObject.NULL)) {
            val envelope = JSONObject(TelegramProtocol.event(familyEvent())).put("v", version)
            assertNull(TelegramProtocol.receive(update(envelope.toString()), peerId, "guardian"))
        }
        val wrongApp = JSONObject(TelegramProtocol.event(familyEvent())).put("app", "AnotherApp")
        val missingVersion = JSONObject(TelegramProtocol.event(familyEvent())).apply { remove("v") }
        assertNull(TelegramProtocol.receive(update(wrongApp.toString()), peerId, "guardian"))
        assertNull(TelegramProtocol.receive(update(missingVersion.toString()), peerId, "guardian"))
    }

    @Test fun missingOrInvalidCreationTimeCannotAcquireCurrentTimeFromCacheParser() {
        for (value in listOf<Any?>(null, JSONObject.NULL, 12345, "invalid", "")) {
            val event = familyEvent().json().apply {
                if (value == null) remove("createdAt") else put("createdAt", value)
            }
            val envelope = TelegramProtocol.envelope("event").put("event", event)
            assertNull(TelegramProtocol.receive(update(envelope.toString()), peerId, "guardian"))
        }
    }

    @Test fun ackCanonicalizesUuidCaseAndRejectsMalformedIdentifiers() {
        val text = TelegramProtocol.envelope("ack").put("id", eventId.uppercase()).toString()
        val packet = TelegramProtocol.receive(update(text), peerId, "guardian")!!
        assertEquals("ack", packet.type)
        assertEquals(eventId, packet.id)
        assertNull(packet.event)
        for (id in listOf("not-an-id", "1-1-1-1-1", "", "$eventId/suffix")) {
            val bad = TelegramProtocol.envelope("ack").put("id", id).toString()
            assertNull(TelegramProtocol.receive(update(bad), peerId, "guardian"))
        }
    }

    @Test fun claimedRoleMustMatchPairedPeerAndItsPermittedEventKinds() {
        val childClaim = TelegramProtocol.event(familyEvent(sender = "child"))
        assertNull(TelegramProtocol.receive(update(childClaim), peerId, "guardian"))
        val childAward = TelegramProtocol.event(familyEvent("sticker_award", "child", JSONObject().put("count", 1).put("reason", "위조")))
        assertNull(TelegramProtocol.receive(update(childAward), peerId, "child"))
        val guardianLocation = TelegramProtocol.event(familyEvent("location", "guardian", locationPayload()))
        assertNull(TelegramProtocol.receive(update(guardianLocation), peerId, "guardian"))
    }

    @Test fun locationBoundsAndTypesAreValidatedBeforeAcceptance() {
        val valid = familyEvent("location", "child", locationPayload())
        assertNotNull(TelegramProtocol.receive(update(TelegramProtocol.event(valid)), peerId, "child"))
        for (payload in listOf(
            locationPayload().put("latitude", 91),
            locationPayload().put("longitude", "127.0"),
            locationPayload().put("accuracy", -1),
            locationPayload().put("source", "hidden"),
            locationPayload().apply { remove("capturedAt") }
        )) {
            val event = familyEvent("location", "child", payload)
            assertNull(TelegramProtocol.receive(update(TelegramProtocol.event(event)), peerId, "child"))
        }
    }

    @Test fun rejectOversizedTextAndEmptyOrUnknownPayload() {
        val emptyChat = familyEvent(payload = JSONObject().put("text", "  "))
        val unknown = familyEvent(kind = "execute_command")
        for (text in listOf(TelegramProtocol.event(emptyChat), TelegramProtocol.event(unknown), "a".repeat(4097))) {
            assertNull(TelegramProtocol.receive(update(text), peerId, "guardian"))
        }
    }

    private fun locationPayload() = JSONObject().put("latitude", 37.5).put("longitude", 127.0)
        .put("accuracy", 10).put("source", "manual").put("capturedAt", createdAt)
}
