package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class FamilyTransportTest {
    @Test fun `ten queued chats use one batch and one ack batch with original identities`() {
        val h = Harness(); h.confirm()
        val messages = (1..10).map { h.enqueue("메시지 $it") }
        h.sync(101); h.sync(202); h.advance(); h.sync(101)
        val sends = h.telegram.sendRequests.filter { JSONObject(it.text).getString("type") == "batch" }
        assertEquals(2, sends.size)
        val sent = FamilyTransportProtocol.unpack(JSONObject(sends.first().text))
        assertEquals(messages.map { it.id }, sent.map { JSONObject(it).getJSONObject("message").getString("id") })
        assertEquals(messages.map { it.id }.toSet(), h.stores.getValue(202).chatHistory(h.room.id).messages.map { it.id }.toSet())
        assertTrue(h.stores.getValue(101).pendingChatDeliveries(h.room.id).isEmpty())
        assertEquals(10, h.notifications.size)
    }

    @Test fun `old peers receive normal chats while unanswered capability probes stay bounded`() {
        val h = Harness(); h.oldPeer = true
        val messages = (1..3).map { h.enqueue("구버전 $it") }
        repeat(80) { h.sync(101); h.sync(202); h.advance() }
        assertTrue(h.telegram.sendRequests.none { JSONObject(it.text).optString("type") == "batch" })
        assertEquals(messages.map { it.id }.toSet(), h.stores.getValue(202).chatHistory(h.room.id).messages.map { it.id }.toSet())
        assertTrue(h.stores.getValue(101).pendingChatDeliveries(h.room.id).isEmpty())
        assertTrue(h.telegram.sendRequests.count { JSONObject(it.text).optString("type") == "capabilities" } <= 1)
    }

    @Test fun `dropped batch retries individually after peer downgrade without losing any message`() {
        val h = Harness(); h.confirm()
        val messages = (1..10).map { h.enqueue("업데이트 전후 $it") }
        h.telegram.dropBatchFrom = 101
        h.sync(101)
        assertEquals(10, h.stores.getValue(101).pendingChatDeliveries(h.room.id).size)
        h.oldPeer = true; h.time += 31_000
        repeat(30) { h.sync(101); h.sync(202); h.advance() }
        assertTrue(h.stores.getValue(101).pendingChatDeliveries(h.room.id).isEmpty())
        assertEquals(messages.map { it.id }.toSet(), h.stores.getValue(202).chatHistory(h.room.id).messages.map { it.id }.toSet())
        val retries = h.telegram.sendRequests.filter { it.sender == 101L && JSONObject(it.text).optString("type") == "chat" }
        assertEquals(messages.map { it.digest }, retries.map { FamilyChatMessage.parse(JSONObject(it.text).getJSONObject("message")).digest })
    }

    @Test fun `ambiguous batch response and replay notify once and ack each original`() {
        val h = Harness(); h.confirm()
        val messages = (1..4).map { h.enqueue("중복 방지 $it") }
        h.telegram.loseBatchResponseFrom = 101
        h.sync(101)
        val wire = h.telegram.sendRequests.last().text
        h.sync(202)
        h.telegram.inject(101, 202, wire)
        h.advance(); h.sync(202)
        h.time += 31_000; h.sync(101)
        assertEquals(messages.size, h.notifications.size)
        assertTrue(h.stores.getValue(101).pendingChatDeliveries(h.room.id).isEmpty())
    }

    @Test fun `failed commit exposes no partial batch no notifications and no advanced offset`() {
        val h = Harness(); h.confirm()
        repeat(5) { h.enqueue("원자적 수신 $it") }
        h.sync(101)
        val store = h.stores.getValue(202)
        val offset = store.meta("offset")
        store.failCommit = true
        assertThrows(IOException::class.java) { h.sync(202) }
        assertTrue(store.chatHistory(h.room.id).messages.isEmpty())
        assertEquals(offset, store.meta("offset"))
        assertTrue(h.notifications.isEmpty())
        h.sync(202)
        assertEquals(5, store.chatHistory(h.room.id).messages.size)
    }

    @Test fun `batch receipts may arrive reordered but cannot acknowledge an unsent message`() {
        val h = Harness(); h.confirm()
        val sent = (1..3).map { h.enqueue("보냄 $it") }
        h.sync(101)
        val unsent = h.enqueue("아직 안 보냄")
        val acks = (sent.reversed() + unsent).map { FamilyChatProtocol.receipt(FamilyChatReceipt(h.room.id, it.id, 101, it.digest)) }
        h.telegram.inject(202, 101, FamilyTransportProtocol.batch(acks))
        h.sync(101)
        assertEquals(listOf(unsent.id), h.stores.getValue(101).pendingChatDeliveries(h.room.id).map { it.messageId })
    }

    @Test fun `wrong actor forwarded batch and sibling care packet are rejected before any inner processing`() {
        val h = Harness()
        val msg = h.enqueue("인증된 가족만")
        val batch = FamilyTransportProtocol.batch(listOf(FamilyChatProtocol.message(msg), FamilyChatProtocol.message(msg)))
        assertNull(h.transport(202).incoming(update(999, batch)))
        assertNull(h.transport(202).incoming(update(101, batch).also { it.getJSONObject("message").put("forward_origin", JSONObject()) }))
        val forged = msg.copy(senderId = 202)
        assertNull(h.transport(202).incoming(update(101, FamilyTransportProtocol.batch(listOf(FamilyChatProtocol.message(msg), FamilyChatProtocol.message(forged))))))
        val room = FamilyChatRoom.create("가족", h.room.members + FamilyChatMember(303, "@third_child_bot", "딸", "daughter"))
        val care = FamilyCareProtocol.outgoing(room, 101, 101, 202, "child_event", JSONObject()).text
        val sibling = FamilyTransport(h.client(303), h.stores.getValue(202), room, 303, now = { h.time })
        assertNull(sibling.incoming(update(101, FamilyTransportProtocol.batch(listOf(care, care)))))
    }

    @Test fun `capability requires authenticated peer matching reply challenge and cannot echo replies`() {
        val h = Harness()
        val transport = h.transport(101)
        assertNull(transport.incoming(update(202, FamilyTransportProtocol.capability(42, true))))
        assertFalse(transport.supportsBatch(202))
        assertNull(transport.incoming(update(999, FamilyTransportProtocol.capability(42, false))))
        assertFalse(transport.supportsBatch(202))
        assertEquals(emptyList<JSONObject>(), transport.incoming(update(202, FamilyTransportProtocol.capability(42, false))))
        assertTrue(transport.supportsBatch(202))
        transport.flushControls(true)
        assertEquals(1, h.telegram.sendRequests.size)
        h.advance(); transport.flushControls(true)
        assertEquals(1, h.telegram.sendRequests.size)
    }

    @Test fun `pacer returns a bounded wake deadline without marking skipped chats sent`() {
        val h = Harness(); h.enqueue("첫 번째")
        val transport = h.transport(101)
        val channel = h.channel(101, transport)
        channel.flush()
        val second = h.enqueue("두 번째")
        channel.flush()
        // Waiting for the head ACK is not eligible work and must not create a busy wake deadline.
        assertNull(transport.deferredUntilMillis)
        assertEquals(0L, h.stores.getValue(101).pendingChatDelivery(h.room.id, second.id, 202)!!.sentAt)
        val member = h.room.members.single { it.botId == 202L }
        assertEquals(0, transport.send(member, listOf(FamilyChatProtocol.message(second))))
        assertEquals(h.time + FamilyTransport.PEER_INTERVAL_MILLIS, transport.deferredUntilMillis)
    }

    @Test fun `oversized nested and corrupt batches cannot be decoded`() {
        val h = Harness()
        assertThrows(IllegalArgumentException::class.java) { FamilyTransportProtocol.batch(List(17) { "{}" }) }
        val json = JSONObject().put("app", "TalkingFamily").put("v", 5).put("type", "batch").put("codec", "gzip-base64").put("data", "invalid")
        assertNull(h.transport(202).incoming(update(101, json.toString())))
        val inner = FamilyTransportProtocol.batch(listOf("{}", "{}"))
        assertNull(h.transport(202).incoming(update(101, FamilyTransportProtocol.batch(listOf(inner, inner)))))
    }

    private class Harness {
        val room = FamilyChatRoom.create("가족", listOf(FamilyChatMember(101, "@member101_bot", "아들", "son"),
            FamilyChatMember(202, "@member202_bot", "아빠", "father")))
        val stores = listOf(101L, 202L).associateWith { FamilyChatExchangeTest.MemoryStore() }
        val telegram = FamilyChatExchangeTest.FakeTelegram()
        val notifications = mutableListOf<String>()
        var time = 1_000_000L
        var oldPeer = false
        fun advance() { time += 1_200 }
        fun client(id: Long) = TelegramClient("$id:${"a".repeat(32)}", telegram)
        fun transport(id: Long) = FamilyTransport(client(id), stores.getValue(id), room, id, now = { time })
        fun channel(id: Long, transport: FamilyTransport? = null) = FamilyChatExchange(client(id), stores.getValue(id), room, id,
            now = { time }, onReceived = { notifications += it.id }, transport = transport)
        fun enqueue(text: String) = FamilyChatMessage(UUID.randomUUID().toString(), room.id, 101, text, "2026-09-29T12:00:00Z")
            .also { channel(101).enqueue(it) }
        fun sync(id: Long) {
            val transport = if (oldPeer && id == 202L) null else transport(id)
            TelegramExchange(client(id), stores.getValue(id), 0, "child", now = { time }, familyChat = channel(id, transport),
                transport = transport).synchronize()
        }
        fun confirm() {
            for ((own, peer) in listOf(101L to 202L, 202L to 101L)) {
                val transport = transport(own)
                transport.incoming(update(peer, FamilyTransportProtocol.capability(42, false)))
                transport.flushControls(true)
            }
            advance(); sync(101); sync(202); advance(); telegram.sendRequests.clear()
        }
    }
    companion object {
        private fun update(sender: Long, text: String) = JSONObject().put("update_id", 1).put("message", JSONObject().put("text", text)
            .put("from", JSONObject().put("id", sender).put("is_bot", true))
            .put("chat", JSONObject().put("id", sender).put("type", "private")))
    }
}
