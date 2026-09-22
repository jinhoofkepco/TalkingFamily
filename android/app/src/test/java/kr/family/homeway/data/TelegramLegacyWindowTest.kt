package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID

class TelegramLegacyWindowTest {
    @Test fun `twenty second locations keep up with a parent polling once per minute`() {
        val family = Family()
        val ids = mutableListOf<String>()
        var maximumPending = 0
        repeat(31) { index ->
            family.time = 1_000_000L + index * 20_000L
            val capturedAt = Instant.parse("2026-09-22T00:00:00Z").plusSeconds(index * 20L).toString()
            val event = FamilyEvent(UUID.randomUUID().toString(), "location", JSONObject()
                .put("latitude", 37.5).put("longitude", 127.0).put("accuracy", 12)
                .put("capturedAt", capturedAt).put("source", "automatic"), "child", capturedAt, "pending")
            ids += event.id
            family.child.enqueue(event)
            family.sendChild()
            maximumPending = maxOf(maximumPending, family.child.pending().size)
            if (index % 3 == 0) {
                family.receiveParent()
                family.receiveChild()
                assertTrue("Every minute must finish the previous minute's locations", family.child.pending().isEmpty())
            }
        }
        assertTrue(maximumPending <= 3)
        assertEquals(ids.toSet(), FamilySnapshot.parse(family.parent.cached()).events.filter { it.kind == "location" }.map { it.id }.toSet())
        assertEquals(31, family.received.distinct().size)
        assertTrue(family.parent.receipts().isEmpty())
    }

    @Test fun `eight sends per flush and sixteen unacknowledged events bound the pipeline`() {
        val family = Family()
        val events = (0 until 20).map { family.enqueue("대기 $it") }
        family.sendChild()
        assertEquals(events.take(8).map { it.id }, family.telegram.eventIds())
        family.sendChild()
        assertEquals(events.take(16).map { it.id }, family.telegram.eventIds())
        family.sendChild()
        assertEquals(16, family.telegram.eventIds().size)

        family.telegram.ack(202, 101, events[15].id)
        family.receiveChild()
        assertFalse(family.child.pending().any { it.id == events[15].id })
        assertTrue(family.child.pending().any { it.id == events[0].id })
        assertEquals(events.take(17).map { it.id }, family.telegram.eventIds())
        assertEquals(0L, family.child.meta(TelegramLegacyWindow.sentAtKey(events[15].id)))
        assertEquals(0L, family.child.meta(TelegramLegacyWindow.confirmedKey(events[15].id)))
    }

    @Test fun `out of order ACK requires the authenticated peer and an attempted pending ID`() {
        val family = Family()
        val events = (0 until 20).map { family.enqueue("확인 $it") }
        family.sendChild()
        family.telegram.ack(202, 101, events[8].id) // Within the window, but never attempted.
        family.telegram.ack(202, 101, events[19].id) // Outside the current window.
        family.telegram.ack(999, 101, events[0].id) // Not the authenticated paired bot.
        family.telegram.ack(202, 101, UUID.randomUUID().toString())
        family.telegram.ack(202, 101, events[7].id)
        family.receiveChild()
        val pending = family.child.pending().map { it.id }.toSet()
        assertFalse(events[7].id in pending)
        assertTrue(events[0].id in pending)
        assertTrue(events[8].id in pending)
        assertTrue(events[19].id in pending)
        assertEquals(19, pending.size)
        assertEquals("relayed", FamilySnapshot.parse(family.child.cached()).events.single { it.id == events[7].id }.delivery)
    }

    @Test fun `ambiguous send survives restart and blocks later events until its actual ACK`() {
        val family = Family()
        val events = (0 until 3).map { family.enqueue("응답 유실 $it") }
        family.telegram.loseEventResponse = events[0].id
        assertThrows(TelegramException::class.java) { family.sendChild() }
        assertEquals(listOf(events[0].id), family.telegram.eventIds())
        assertEquals(0L, family.child.meta(TelegramLegacyWindow.confirmedKey(events[0].id)))
        family.restartChild()
        family.time += 15_001
        family.sendChild() // Global failure backoff expired, but its ambiguous attempt is not due.
        assertEquals(listOf(events[0].id), family.telegram.eventIds())
        family.receiveParent()
        family.receiveChild()
        assertEquals(events.map { it.id }, family.telegram.eventIds())
        assertFalse(family.child.pending().any { it.id == events[0].id })
        family.receiveParent()
        family.receiveChild()
        assertTrue(family.child.pending().isEmpty())
        assertEquals(events.map { it.id }.toSet(), family.received.toSet())
    }

    @Test fun `explicit send failure is retried before any following event`() {
        val family = Family()
        val events = (0 until 3).map { family.enqueue("실패 후 순서 $it") }
        family.telegram.rejectEvent = events[0].id
        assertThrows(TelegramException::class.java) { family.sendChild() }
        family.restartChild()
        family.time += 15_001
        family.sendChild()
        assertEquals(listOf(events[0].id), family.telegram.eventIds())
        family.time += 15_000
        family.sendChild()
        assertEquals(listOf(events[0].id, events[0].id, events[1].id, events[2].id), family.telegram.eventIds())
        family.receiveParent()
        family.receiveChild()
        assertTrue(family.child.pending().isEmpty())
        assertEquals(3, family.received.size)
    }

    @Test fun `confirmed attempts survive restart and permit the next queued event without an ACK`() {
        val family = Family()
        val first = family.enqueue("먼저 보냄")
        family.sendChild()
        family.restartChild()
        val second = family.enqueue("응답 기다리며 다음 전송")
        family.sendChild()
        assertEquals(listOf(first.id, second.id), family.telegram.eventIds())
        assertEquals(2, family.child.pending().size)
        assertEquals(1L, family.child.meta(TelegramLegacyWindow.confirmedKey(first.id)))
        assertEquals(1L, family.child.meta(TelegramLegacyWindow.confirmedKey(second.id)))
    }

    @Test fun `upgrade preserves only the old head attempt and never transfers its proof to the next ID`() {
        val family = Family()
        val first = family.enqueue("업데이트 전 보냄")
        val second = family.enqueue("아직 안 보냄")
        family.child.setMeta("sentAt", family.time - 1)
        family.restartChild()
        family.telegram.ack(202, 101, first.id)
        family.telegram.ack(202, 101, second.id)
        family.receiveChild()
        assertEquals(listOf(second.id), family.child.pending().map { it.id })
        assertEquals(listOf(second.id), family.telegram.eventIds())
        assertEquals("relayed", FamilySnapshot.parse(family.child.cached()).events.single { it.id == first.id }.delivery)
        assertEquals(0L, family.child.meta(TelegramLegacyWindow.sentAtKey(first.id)))
        assertEquals(family.time, family.child.meta("sentAt"))
        family.restartChild()
        family.telegram.ack(202, 101, first.id) // A replay cannot affect the new head.
        family.receiveChild()
        assertEquals(listOf(second.id), family.child.pending().map { it.id })
    }

    private class Family {
        val telegram = FakeTelegram()
        var child = MemoryStore()
        val parent = MemoryStore()
        var time = 1_000_000L
        val received = mutableListOf<String>()
        private fun client(id: Long) = TelegramClient("$id:${"test_credentials_".repeat(2)}", telegram)
        private fun childExchange() = TelegramExchange(client(101), child, 202, "guardian", now = { time })
        fun enqueue(text: String) = FamilyEvent(UUID.randomUUID().toString(), "chat", JSONObject().put("text", text),
            "child", "2026-09-22T00:00:00Z", "pending").also(child::enqueue)
        fun sendChild() = childExchange().flushOutgoing()
        fun receiveChild() = childExchange().synchronize()
        fun receiveParent() = TelegramExchange(client(202), parent, 101, "child", now = { time },
            onReceived = { received += it.id }).synchronize()
        fun restartChild() { child = MemoryStore(child.saved()) }
    }

    private class MemoryStore(saved: String? = null) : TelegramExchangeStore {
        private var data = saved?.let(::JSONObject) ?: JSONObject().put("state", TelegramLedger.emptyState())
            .put("meta", JSONObject()).put("pending", JSONArray()).put("receipts", JSONArray())
        fun saved() = data.toString()
        override fun <T> transaction(block: () -> T): T {
            val before = saved()
            try { return block() } catch (error: Throwable) { data = JSONObject(before); throw error }
        }
        override fun meta(name: String) = data.getJSONObject("meta").optLong(name)
        override fun setMeta(name: String, value: Long) { data.getJSONObject("meta").put(name, value) }
        override fun cached() = JSONObject(data.getJSONObject("state").toString())
        override fun cache(state: JSONObject) { data.put("state", JSONObject(state.toString())) }
        override fun pending() = data.getJSONArray("pending").let { rows ->
            (0 until rows.length()).map { FamilyEvent.parse(rows.getJSONObject(it)) }
        }
        override fun remove(id: String) { data.put("pending", JSONArray(pending().filter { it.id != id }.map { it.json() })) }
        override fun receipts() = data.getJSONArray("receipts").let { rows -> (0 until rows.length()).map { rows.getString(it) } }
        override fun queueReceipt(id: String) { if (id !in receipts()) data.getJSONArray("receipts").put(id) }
        override fun removeReceipt(id: String) { data.put("receipts", JSONArray(receipts().filter { it != id })) }
        fun enqueue(event: FamilyEvent) = transaction {
            cache(TelegramLedger.apply(cached(), event))
            data.getJSONArray("pending").put(event.json())
        }
    }

    private class FakeTelegram : TelegramHttpTransport {
        var loseEventResponse: String? = null
        var rejectEvent: String? = null
        private val sentEvents = mutableListOf<String>()
        private val inbox = mutableMapOf<Long, MutableList<JSONObject>>()
        private val nextUpdate = mutableMapOf<Long, Long>()
        fun eventIds() = sentEvents.toList()
        fun ack(from: Long, to: Long, id: String) = inject(from, to, TelegramProtocol.envelope("ack").put("id", id).toString())
        private fun inject(from: Long, to: Long, text: String) {
            val id = nextUpdate.getOrDefault(to, 1L)
            nextUpdate[to] = id + 1
            inbox.getOrPut(to) { mutableListOf() }.add(JSONObject().put("update_id", id).put("message", JSONObject()
                .put("text", text).put("from", JSONObject().put("id", from).put("is_bot", true))
                .put("chat", JSONObject().put("id", from).put("type", "private"))))
        }
        override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse {
            val from = token.substringBefore(':').toLong()
            val body = JSONObject(json)
            return when (method) {
                "getUpdates" -> {
                    val rows = inbox.getOrPut(from) { mutableListOf() }
                    rows.removeAll { it.getLong("update_id") < body.getLong("offset") }
                    ok(JSONArray(rows.take(100)))
                }
                "sendMessage" -> {
                    val to = body.getLong("chat_id")
                    val text = body.getString("text")
                    val packet = JSONObject(text)
                    val eventId = packet.optJSONObject("event")?.getString("id")
                    if (eventId != null) sentEvents += eventId
                    if (eventId != null && rejectEvent == eventId) {
                        rejectEvent = null
                        return TelegramHttpResponse(403, """{"ok":false,"error_code":403}""")
                    }
                    inject(from, to, text)
                    if (eventId != null && loseEventResponse == eventId) { loseEventResponse = null; throw IOException("lost response") }
                    ok(JSONObject().put("chat", JSONObject().put("id", to).put("type", "private")))
                }
                else -> error("Unexpected fake method $method")
            }
        }
        private fun ok(result: Any) = TelegramHttpResponse(200, JSONObject().put("ok", true).put("result", result).toString())
    }
}
