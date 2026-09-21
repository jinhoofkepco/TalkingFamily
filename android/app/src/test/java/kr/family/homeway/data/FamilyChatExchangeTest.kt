package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class FamilyChatExchangeTest {
    @Test fun `four phones including two parents exchange and acknowledge one room`() {
        val family = Family()
        val messages = family.ids.map { family.enqueue(it, "안녕 $it") }
        family.drain()
        for (id in family.ids) {
            val rows = family.stores.getValue(id).chatHistory(family.room.id).messages
            assertEquals(messages.map { it.id }.toSet(), rows.map { it.id }.toSet())
            assertEquals(4, rows.map { it.senderId }.distinct().size)
            assertEquals(3, rows.single { it.senderId == id }.deliveredCount)
            assertTrue(family.stores.getValue(id).pendingChatDeliveries(family.room.id).isEmpty())
            assertTrue(family.stores.getValue(id).chatReceipts(family.room.id).isEmpty())
        }
        assertEquals(12, family.notifications.size)
    }

    @Test fun `one blocked recipient cannot block later messages to other people or legacy location`() {
        val family = Family()
        family.telegram.blockedRecipient = 404
        val first = family.enqueue(101, "첫 메시지")
        val second = family.enqueue(101, "두 번째")
        family.stores.getValue(101).enqueueLegacy(FamilyEvent(UUID.randomUUID().toString(), "location",
            JSONObject().put("latitude", 37.5).put("longitude", 127).put("accuracy", 12)
                .put("capturedAt", "2026-09-22T12:00:00Z").put("source", "automatic"),
            "child", "2026-09-22T12:00:00Z", "pending"))
        family.legacyPaired = true
        family.drain()
        for (id in listOf(202L, 303L)) assertEquals(setOf(first.id, second.id),
            family.stores.getValue(id).chatHistory(family.room.id).messages.map { it.id }.toSet())
        assertEquals(2, family.stores.getValue(101).pendingChatDeliveries(family.room.id).size)
        assertTrue(family.stores.getValue(101).pendingChatDeliveries(family.room.id).all { it.peerId == 404L })
        assertTrue(family.stores.getValue(101).pending().isEmpty())
        assertEquals("location", FamilySnapshot.parse(family.stores.getValue(202).cached()).events.single().kind)
        family.telegram.blockedRecipient = null
        family.time += 31_000
        family.drain()
        assertTrue(family.stores.getValue(101).pendingChatDeliveries(family.room.id).isEmpty())
    }

    @Test fun `ambiguous send response survives restart and ACK clears only its actual recipient`() {
        val family = Family()
        val sent = family.enqueue(101, "재시작해도 한 번")
        family.telegram.loseChatResponseFrom = 101
        family.sync(101) // Per-peer transport failure is isolated; Telegram accepted the first send.
        assertTrue(family.stores.getValue(101).pendingChatDeliveries(family.room.id).all { it.sentAt > 0 })
        family.restart(101)
        family.sync(202)
        family.sync(101)
        assertFalse(family.stores.getValue(101).pendingChatDeliveries(family.room.id).any { it.peerId == 202L })
        assertEquals(2, family.stores.getValue(101).pendingChatDeliveries(family.room.id).size)
        family.drain()
        assertEquals(3, family.stores.getValue(101).chatMessage(family.room.id, sent.id)?.deliveredCount)
        assertEquals(1, family.notifications.count { it == 202L to sent.id })
    }

    @Test fun `lost receipt retries immutable message without duplicate notification or ACK loops`() {
        val family = Family()
        val sent = family.enqueue(101, "중복 없이")
        family.sync(101)
        family.telegram.dropAckFrom = 202
        family.sync(202)
        family.sync(101)
        assertTrue(family.stores.getValue(101).pendingChatDeliveries(family.room.id).any { it.peerId == 202L })
        family.time += 31_000
        family.restart(202)
        family.drain()
        assertEquals(1, family.notifications.count { it == 202L to sent.id })
        assertEquals(1, family.stores.getValue(202).chatHistory(family.room.id).messages.size)
        assertTrue(family.stores.values.all { it.chatReceipts(family.room.id).isEmpty() })
    }

    @Test fun `failed commit rolls back room message receipt and common offset before notification`() {
        val family = Family()
        val sent = family.enqueue(101, "안전하게 저장")
        family.sync(101)
        family.stores.getValue(202).failCommit = true
        assertThrows(IOException::class.java) { family.sync(202) }
        assertNull(family.stores.getValue(202).chatMessage(family.room.id, sent.id))
        assertEquals(0, family.stores.getValue(202).meta("offset"))
        assertTrue(family.stores.getValue(202).chatReceipts(family.room.id).isEmpty())
        assertTrue(family.notifications.none { it.first == 202L })
        family.restart(202)
        family.drain()
        assertEquals(1, family.notifications.count { it == 202L to sent.id })
    }

    @Test fun `conflicting duplicate is not acknowledged and cannot alter accepted text`() {
        val family = Family()
        val sent = family.enqueue(101, "원래 말")
        family.drain()
        family.telegram.inject(101, 202, FamilyChatProtocol.message(sent.copy(text = "바뀐 말")))
        family.sync(202)
        assertEquals("원래 말", family.stores.getValue(202).chatMessage(family.room.id, sent.id)?.text)
        assertTrue(family.stores.getValue(202).chatReceipts(family.room.id).isEmpty())
        assertEquals(1, family.notifications.count { it == 202L to sent.id })
    }

    @Test fun `unsent forged wrong digest and wrong member ACK cannot complete a delivery`() {
        val family = Family()
        val sent = family.enqueue(101, "확인")
        val ack = FamilyChatProtocol.receipt(FamilyChatReceipt(family.room.id, sent.id, 101, sent.digest))
        family.telegram.inject(202, 101, ack) // ACK before this recipient has ever been sent the message.
        family.telegram.inject(999, 101, ack)
        family.sync(101)
        assertEquals(3, family.stores.getValue(101).pendingChatDeliveries(family.room.id).size)
        family.telegram.inject(202, 101, FamilyChatProtocol.receipt(FamilyChatReceipt(family.room.id, sent.id, 101, "0".repeat(64))))
        family.sync(101)
        assertEquals(3, family.stores.getValue(101).pendingChatDeliveries(family.room.id).size)
        family.telegram.inject(202, 101, ack)
        family.sync(101)
        assertEquals(setOf(303L, 404L), family.stores.getValue(101).pendingChatDeliveries(family.room.id).map { it.peerId }.toSet())
    }

    @Test fun `Telegram rate limit pauses every lane durably and room-only operation never sends to zero`() {
        val family = Family()
        family.enqueue(101, "기다렸다 보내기")
        family.telegram.rateLimitSendFrom = 101
        val error = assertThrows(TelegramException::class.java) { family.sync(101) }
        assertEquals(429, error.errorCode)
        val calls = family.telegram.calls
        family.restart(101)
        family.time += 29_000
        assertFalse(family.sync(101))
        assertEquals(calls, family.telegram.calls)
        family.time += 2_000
        family.drain()
        assertTrue(family.stores.getValue(101).pendingChatDeliveries(family.room.id).isEmpty())
        assertTrue(family.telegram.destinations.all { it > 0 })
    }

    @Test fun `legacy peer failure has separate backoff while family room keeps sending and receiving`() {
        val family = Family()
        family.legacyPaired = true
        family.telegram.blockedRecipient = 202
        family.stores.getValue(101).enqueueLegacy(FamilyEvent(UUID.randomUUID().toString(), "chat", JSONObject().put("text", "이전 연결"),
            "child", "2026-09-22T12:00:00Z", "pending"))
        val outgoing = family.enqueue(101, "다른 가족에게")
        val incoming = family.enqueue(303, "답장")
        family.drain()
        assertTrue(family.stores.getValue(101).meta("legacyRetryAfter") > 0)
        assertEquals(0, family.stores.getValue(101).meta("retryAfter"))
        assertEquals(incoming.text, family.stores.getValue(101).chatMessage(family.room.id, incoming.id)?.text)
        assertEquals(outgoing.text, family.stores.getValue(303).chatMessage(family.room.id, outgoing.id)?.text)
        assertEquals(outgoing.text, family.stores.getValue(404).chatMessage(family.room.id, outgoing.id)?.text)
        assertFalse(family.stores.getValue(101).pendingChatDeliveries(family.room.id).any { it.peerId == 303L || it.peerId == 404L })
    }

    @Test fun `legacy ledger conflict is reported after family room receives and sends`() {
        val family = Family()
        family.legacyPaired = true
        family.stores.getValue(101).setMeta("ledgerConflict", 1)
        val outgoing = family.enqueue(101, "원장과 독립 대화")
        val incoming = family.enqueue(303, "수신도 계속")
        family.sync(303)
        assertThrows(TelegramSyncException::class.java) { family.sync(101) }
        family.sync(303)
        assertThrows(TelegramSyncException::class.java) { family.sync(101) }
        assertEquals(incoming.text, family.stores.getValue(101).chatMessage(family.room.id, incoming.id)?.text)
        assertEquals(outgoing.text, family.stores.getValue(303).chatMessage(family.room.id, outgoing.id)?.text)
        assertFalse(family.stores.getValue(101).pendingChatDeliveries(family.room.id).any { it.peerId == 303L })
    }

    private class Family {
        val ids = listOf(101L, 202L, 303L, 404L)
        val room = FamilyChatRoom.create("우리집", ids.mapIndexed { index, id -> FamilyChatMember(id, "@member${id}_bot",
            listOf("엄마", "아빠", "아들", "딸")[index], listOf("mother", "father", "son", "daughter")[index]) })
        val stores = ids.associateWith { MemoryStore() }.toMutableMap()
        val telegram = FakeTelegram()
        val notifications = mutableListOf<Pair<Long, String>>()
        var time = 1_000_000L
        var legacyPaired = false
        private fun client(id: Long) = TelegramClient("$id:${"a".repeat(32)}", telegram)
        private fun channel(id: Long) = FamilyChatExchange(client(id), stores.getValue(id), room, id, now = { time },
            onReceived = { notifications += id to it.id })
        fun enqueue(id: Long, text: String): FamilyChatMessage = FamilyChatMessage(UUID.randomUUID().toString(), room.id, id,
            text, "2026-09-22T12:00:00Z").also { channel(id).enqueue(it) }
        fun sync(id: Long): Boolean = TelegramExchange(client(id), stores.getValue(id),
            if (legacyPaired) when (id) { 101L -> 202L; 202L -> 101L; else -> 0L } else 0L,
            if (id == 101L) "guardian" else "child", now = { time }, familyChat = channel(id)).synchronize()
        fun drain() { repeat(12) { ids.forEach { sync(it) }; time += 1001 } }
        fun restart(id: Long) { stores[id] = MemoryStore(stores.getValue(id).saved()) }
    }

    private class MemoryStore(saved: String? = null) : TelegramExchangeStore, FamilyChatStore {
        private var data = saved?.let(::JSONObject) ?: JSONObject().put("meta", JSONObject()).put("state", TelegramLedger.emptyState())
            .put("legacy", JSONArray()).put("legacyReceipts", JSONArray()).put("messages", JSONArray())
            .put("deliveries", JSONArray()).put("receipts", JSONArray()).put("retry", JSONObject())
        var failCommit = false
        fun saved() = data.toString()
        private fun rows(key: String) = data.getJSONArray(key).let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
        private fun replace(key: String, rows: List<JSONObject>) { data.put(key, JSONArray(rows)) }
        override fun <T> transaction(block: () -> T): T {
            val before = saved()
            try { val result = block(); if (failCommit) { failCommit = false; throw IOException("commit failed") }; return result }
            catch (error: Throwable) { data = JSONObject(before); throw error }
        }
        override fun meta(name: String) = data.getJSONObject("meta").optLong(name)
        override fun setMeta(name: String, value: Long) { data.getJSONObject("meta").put(name, value) }
        override fun cached() = JSONObject(data.getJSONObject("state").toString())
        override fun cache(state: JSONObject) { data.put("state", JSONObject(state.toString())) }
        override fun pending() = rows("legacy").map { FamilyEvent.parse(it) }
        override fun remove(id: String) { replace("legacy", rows("legacy").filter { it.getString("id") != id }) }
        override fun queueReceipt(id: String) { if (id !in receipts()) data.getJSONArray("legacyReceipts").put(id) }
        override fun receipts(): List<String> = data.getJSONArray("legacyReceipts").let { array -> (0 until array.length()).map { array.getString(it) } }
        override fun removeReceipt(id: String) { data.put("legacyReceipts", JSONArray(receipts().filter { it != id })) }
        fun enqueueLegacy(event: FamilyEvent) { cache(TelegramLedger.apply(cached(), event)); data.getJSONArray("legacy").put(event.json()) }
        override fun chatMessage(roomId: String, messageId: String): FamilyChatMessage? {
            val row = rows("messages").firstOrNull { it.getString("roomId") == roomId && it.getString("id") == messageId } ?: return null
            val recipients = rows("deliveries").filter { it.getString("roomId") == roomId && it.getString("messageId") == messageId }
            return FamilyChatMessage.parse(row).copy(recipientCount = recipients.size, deliveredCount = recipients.count { it.getBoolean("done") })
        }
        override fun insertChatMessage(message: FamilyChatMessage, recipients: List<Long>) {
            check(chatMessage(message.roomId, message.id) == null)
            data.getJSONArray("messages").put(message.json())
            recipients.forEach { peer -> data.getJSONArray("deliveries").put(JSONObject().put("roomId", message.roomId)
                .put("messageId", message.id).put("peerId", peer).put("sentAt", 0).put("done", false)) }
        }
        override fun pendingChatDeliveries(roomId: String) = rows("deliveries").filter { it.getString("roomId") == roomId && !it.getBoolean("done") }
            .map { FamilyChatDelivery(roomId, it.getString("messageId"), it.getLong("peerId"), it.getLong("sentAt")) }
        private fun delivery(roomId: String, messageId: String, peerId: Long) = rows("deliveries").single {
            it.getString("roomId") == roomId && it.getString("messageId") == messageId && it.getLong("peerId") == peerId }
        override fun markChatSent(roomId: String, messageId: String, peerId: Long, sentAt: Long) { delivery(roomId, messageId, peerId).put("sentAt", sentAt) }
        override fun acknowledgeChat(roomId: String, messageId: String, peerId: Long) { delivery(roomId, messageId, peerId).put("done", true) }
        override fun queueChatReceipt(receipt: FamilyChatReceipt) {
            if (receipt !in chatReceipts(receipt.roomId)) data.getJSONArray("receipts").put(JSONObject().put("roomId", receipt.roomId)
                .put("messageId", receipt.messageId).put("peerId", receipt.peerId).put("digest", receipt.digest))
        }
        override fun chatReceipts(roomId: String) = rows("receipts").filter { it.getString("roomId") == roomId }
            .map { FamilyChatReceipt(roomId, it.getString("messageId"), it.getLong("peerId"), it.getString("digest")) }
        override fun removeChatReceipt(receipt: FamilyChatReceipt) { replace("receipts", rows("receipts").filterNot {
            it.getString("roomId") == receipt.roomId && it.getString("messageId") == receipt.messageId && it.getLong("peerId") == receipt.peerId }) }
        override fun chatPeerRetryAfter(roomId: String, peerId: Long) = data.getJSONObject("retry").optLong("$roomId/$peerId")
        override fun setChatPeerRetryAfter(roomId: String, peerId: Long, retryAfter: Long) { data.getJSONObject("retry").put("$roomId/$peerId", retryAfter) }
        override fun chatHistory(roomId: String, before: FamilyChatCursor?, limit: Int): FamilyChatPage {
            val messages = rows("messages").filter { it.getString("roomId") == roomId }.map { chatMessage(roomId, it.getString("id"))!! }
                .sortedWith(compareByDescending<FamilyChatMessage> { it.createdAt }.thenByDescending { it.id })
                .filter { before == null || it.createdAt < before.createdAt || it.createdAt == before.createdAt && it.id < before.id }
            val page = messages.take(limit)
            return FamilyChatPage(page, page.lastOrNull()?.takeIf { messages.size > limit }?.let { FamilyChatCursor(it.createdAt, it.id) })
        }
    }

    private class FakeTelegram : TelegramHttpTransport {
        var calls = 0
        var blockedRecipient: Long? = null
        var loseChatResponseFrom: Long? = null
        var dropAckFrom: Long? = null
        var rateLimitSendFrom: Long? = null
        val destinations = mutableListOf<Long>()
        private val inbox = mutableMapOf<Long, MutableList<JSONObject>>()
        private val nextUpdate = mutableMapOf<Long, Long>()
        fun inject(from: Long, to: Long, text: String) {
            val id = nextUpdate.getOrDefault(to, 1L)
            nextUpdate[to] = id + 1
            inbox.getOrPut(to) { mutableListOf() }.add(JSONObject().put("update_id", id).put("message", JSONObject().put("text", text)
                .put("from", JSONObject().put("id", from).put("is_bot", true)).put("chat", JSONObject().put("id", from).put("type", "private"))))
        }
        override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse {
            calls++
            val from = token.substringBefore(':').toLong()
            val body = JSONObject(json)
            return when (method) {
                "getUpdates" -> {
                    val queue = inbox.getOrPut(from) { mutableListOf() }
                    queue.removeAll { it.getLong("update_id") < body.getLong("offset") }
                    ok(JSONArray(queue.take(100)))
                }
                "sendMessage" -> {
                    val to = body.getLong("chat_id")
                    destinations += to
                    if (rateLimitSendFrom == from) { rateLimitSendFrom = null; return TelegramHttpResponse(429,
                        JSONObject().put("ok", false).put("error_code", 429).put("parameters", JSONObject().put("retry_after", 30)).toString()) }
                    if (blockedRecipient == to) return TelegramHttpResponse(403, "{\"ok\":false,\"error_code\":403}")
                    val text = body.getString("text")
                    val type = JSONObject(text).getString("type")
                    if (type == "chat_ack" && dropAckFrom == from) dropAckFrom = null else inject(from, to, text)
                    if (type == "chat" && loseChatResponseFrom == from) { loseChatResponseFrom = null; throw IOException("lost response") }
                    ok(JSONObject().put("chat", JSONObject().put("id", to).put("type", "private")))
                }
                else -> error("Unexpected method $method")
            }
        }
        private fun ok(result: Any) = TelegramHttpResponse(200, JSONObject().put("ok", true).put("result", result).toString())
    }
}
