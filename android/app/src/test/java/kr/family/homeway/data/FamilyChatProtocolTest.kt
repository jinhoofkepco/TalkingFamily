package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class FamilyChatProtocolTest {
    private val room = FamilyChatRoom.create("우리집", listOf(
        FamilyChatMember(101, "@mother_bot", "엄마", "mother"),
        FamilyChatMember(202, "@father_bot", "아빠", "father"),
        FamilyChatMember(303, "@son_bot", "아들", "son"),
        FamilyChatMember(404, "@daughter_bot", "딸", "daughter")))
    private fun message() = FamilyChatMessage(UUID.randomUUID().toString(), room.id, 101, "오늘도 잘했어", "2026-09-22T12:00:00Z")
    private fun update(text: String, sender: Long = 101) = JSONObject().put("message", JSONObject().put("text", text)
        .put("from", JSONObject().put("id", sender).put("is_bot", true))
        .put("chat", JSONObject().put("id", sender).put("type", "private")))

    @Test fun `room code round trips four named members and contains no credentials`() {
        val code = room.toCode()
        assertTrue(code.startsWith("TFROOM1:"))
        assertEquals(room, FamilyChatRoom.fromCode(code))
        val json = JSONObject(String(Base64.getUrlDecoder().decode(code.substringAfter(':')), Charsets.UTF_8))
        assertEquals(setOf("id", "title", "members"), json.keys().asSequence().toSet())
        assertFalse(json.toString().contains("token"))
    }

    @Test fun `room rejects duplicate IDs usernames invalid labels and unsupported relationships`() {
        assertThrows(IllegalArgumentException::class.java) { FamilyChatValidation.room(room.copy(members = room.members + room.members.first())) }
        assertThrows(IllegalArgumentException::class.java) { FamilyChatValidation.room(room.copy(members = room.members.take(2).map { it.copy(username = "@Same_Bot") })) }
        assertThrows(IllegalArgumentException::class.java) { FamilyChatValidation.room(room.copy(title = "bad\nname")) }
        assertThrows(IllegalArgumentException::class.java) { FamilyChatValidation.room(room.copy(members = room.members.map { it.copy(relationship = "admin") })) }
        assertThrows(IllegalArgumentException::class.java) { FamilyChatValidation.room(room.copy(members = room.members.take(1))) }
        assertThrows(IllegalArgumentException::class.java) { FamilyChatRoom.fromCode("TFROOM1:" + "A".repeat(8200)) }
        assertThrows(IllegalArgumentException::class.java) { FamilyChatRoom.parse(room.json().put("token", "should-never-be-imported")) }
    }

    @Test fun `wire binds author to Telegram sender and pinned private chat`() {
        val msg = message()
        assertEquals(msg, FamilyChatProtocol.receive(update(FamilyChatProtocol.message(msg)), room, 202)?.message)
        assertNull(FamilyChatProtocol.receive(update(FamilyChatProtocol.message(msg), 303), room, 202))
        assertNull(FamilyChatProtocol.receive(update(FamilyChatProtocol.message(msg), 999), room, 202))
        assertNull(FamilyChatProtocol.receive(update(FamilyChatProtocol.message(msg)), room, 101))
        val group = update(FamilyChatProtocol.message(msg))
        group.getJSONObject("message").getJSONObject("chat").put("type", "group").put("id", -1)
        assertNull(FamilyChatProtocol.receive(group, room, 202))
        val nonBot = update(FamilyChatProtocol.message(msg))
        nonBot.getJSONObject("message").getJSONObject("from").put("is_bot", false)
        assertNull(FamilyChatProtocol.receive(nonBot, room, 202))
        val forwarded = update(FamilyChatProtocol.message(msg))
        forwarded.getJSONObject("message").put("forward_origin", JSONObject())
        assertNull(FamilyChatProtocol.receive(forwarded, room, 202))
    }

    @Test fun `wire cannot cross rooms introduce financial events or import local receipt counts`() {
        assertNull(FamilyChatProtocol.receive(update(FamilyChatProtocol.message(message().copy(roomId = UUID.randomUUID().toString()))), room, 202))
        val financial = FamilyChatProtocol.envelope("sticker_award").put("count", 1)
        assertNull(FamilyChatProtocol.receive(update(financial.toString()), room, 202))
        val counts = JSONObject(FamilyChatProtocol.message(message()))
        counts.getJSONObject("message").put("deliveredCount", 3)
        assertNull(FamilyChatProtocol.receive(update(counts.toString()), room, 202))
        val numericId = JSONObject(FamilyChatProtocol.message(message()))
        numericId.getJSONObject("message").put("senderId", "101")
        assertNull(FamilyChatProtocol.receive(update(numericId.toString()), room, 202))
    }

    @Test fun `message bounds and digest cover immutable contents but not delivery counts`() {
        val msg = message()
        assertEquals(msg.digest, msg.copy(recipientCount = 3, deliveredCount = 2).digest)
        assertNotEquals(msg.digest, msg.copy(text = "다른 말").digest)
        assertNotEquals(msg.digest, msg.copy(senderId = 202).digest)
        for (invalid in listOf(msg.copy(text = " "), msg.copy(text = "a".repeat(1501)), msg.copy(text = "\uD800"),
            msg.copy(createdAt = "not a date"), msg.copy(createdAt = "2200-01-01T00:00:00Z"), msg.copy(senderId = -1))) {
            assertThrows(IllegalArgumentException::class.java) { FamilyChatValidation.message(invalid) }
        }
    }
}
