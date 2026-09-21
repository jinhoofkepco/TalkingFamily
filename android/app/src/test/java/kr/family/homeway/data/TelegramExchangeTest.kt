package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

/** Exercises the production exchange and HTTP client together, with Telegram delivery/commit faults. */
class TelegramExchangeTest {
    private val rewardId = "11111111-1111-4111-8111-111111111111"
    private fun event(kind: String, payload: JSONObject, role: String = "guardian") = FamilyEvent(
        UUID.randomUUID().toString(), kind, payload, role, "2026-09-21T06:00:00Z", "pending")
    private fun award() = event("sticker_award", JSONObject().put("count", 1).put("reason", "잘했어요"))
    private fun chat(text: String, role: String) = event("chat", JSONObject().put("text", text), role)

    @Test fun `lost event response survives sender restart and peer ACK completes the original attempt`() {
        val family = Family()
        val award = award()
        family.guardian.enqueue(award)
        family.telegram.loseEventResponseFrom = 202
        assertThrows(TelegramException::class.java) { family.syncGuardian() }
        assertEquals(family.time, family.guardian.meta("sentAt"))
        family.syncChild()
        assertEquals(1, family.child.cached()!!.getInt("stickerBalance"))
        family.guardian = MemoryStore(family.guardian.saved())
        assertFalse(family.syncGuardian()) // Backoff survives restart.
        family.time += 15_001
        family.syncGuardian()
        assertTrue(family.guardian.pending().isEmpty())
        assertEquals("relayed", FamilySnapshot.parse(family.guardian.cached()!!).events.single().delivery)
        assertEquals(1, family.telegram.sent.count { it.type == "event" })
    }

    @Test fun `lost ACK delivery resends only the oldest event and duplicate cannot grant twice`() {
        val family = Family()
        val first = award()
        val second = award()
        family.guardian.enqueue(first)
        family.guardian.enqueue(second)
        family.syncGuardian()
        family.telegram.dropAckFrom = 101
        family.syncChild()
        family.syncGuardian()
        assertEquals(listOf(first.id), family.telegram.sent.filter { it.type == "event" }.map { it.id })
        family.time += 30_001
        family.guardian = MemoryStore(family.guardian.saved())
        family.child = MemoryStore(family.child.saved())
        family.syncGuardian()
        family.syncChild()
        assertEquals(1, family.child.cached()!!.getInt("stickerBalance"))
        assertEquals(1, family.notifications.count { it == first.id })
        family.syncGuardian() // First ACK now allows the second event to be sent.
        family.drain()
        assertEquals(listOf(first.id, first.id, second.id), family.telegram.sent.filter { it.type == "event" }.map { it.id })
        assertEquals(2, family.child.cached()!!.getInt("stickerBalance"))
        assertTrue(family.guardian.pending().isEmpty())
    }

    @Test fun `ambiguous ACK response retains receipt across restart without producing ACK loops`() {
        val family = Family()
        val award = award()
        family.guardian.enqueue(award)
        family.syncGuardian()
        family.telegram.loseAckResponseFrom = 101
        assertThrows(TelegramException::class.java) { family.syncChild() }
        assertEquals(listOf(award.id), family.child.receipts())
        family.syncGuardian() // ACK itself reached the peer despite the response failure.
        assertTrue(family.guardian.pending().isEmpty())
        family.child = MemoryStore(family.child.saved())
        family.time += 15_001
        family.syncChild()
        family.syncGuardian()
        assertTrue(family.child.receipts().isEmpty())
        assertTrue(family.guardian.receipts().isEmpty())
        assertEquals(2, family.telegram.sent.count { it.type == "ack" })
        assertEquals(1, family.telegram.sent.count { it.type == "event" })
        assertEquals(1, family.notifications.count { it == award.id })
    }

    @Test fun `failed commit rolls back offset ledger and receipt then restart receives event once`() {
        val family = Family()
        val award = award()
        family.guardian.enqueue(award)
        family.syncGuardian()
        family.child.failCommit = true
        assertThrows(IOException::class.java) { family.syncChild() }
        assertEquals(0, family.child.meta("offset"))
        assertEquals(0, family.child.cached()!!.getInt("stickerBalance"))
        assertTrue(family.child.receipts().isEmpty())
        assertTrue(family.notifications.isEmpty())
        family.child = MemoryStore(family.child.saved())
        family.syncChild()
        assertEquals(2, family.child.meta("offset"))
        assertEquals(1, family.child.cached()!!.getInt("stickerBalance"))
        family.child = MemoryStore(family.child.saved())
        family.syncChild()
        family.syncGuardian()
        assertTrue(family.guardian.pending().isEmpty())
        assertEquals(1, family.notifications.count { it == award.id })
    }

    @Test fun `guardian and child converge when reward edits cross a child request in flight`() {
        val family = Family()
        family.guardian.enqueue(event("reward_upsert", JSONObject().put("rewardId", rewardId).put("name", "함께 산책").put("cost", 2)))
        repeat(3) { family.guardian.enqueue(award()) }
        family.drain()
        val request = event("sticker_redeem_request", JSONObject().put("rewardId", rewardId).put("reward", "함께 산책").put("cost", 2), "child")
        family.child.enqueue(request)
        family.guardian.enqueue(event("reward_upsert", JSONObject().put("rewardId", rewardId).put("name", "새 약속").put("cost", 3)))
        family.guardian.enqueue(event("reward_delete", JSONObject().put("rewardId", rewardId)))
        family.syncChild()
        family.syncGuardian()
        val approval = event("sticker_redeem_approve", JSONObject().put("requestId", request.id).put("accepted", true))
        family.guardian.enqueue(approval)
        family.drain()
        for (store in listOf(family.child, family.guardian)) {
            val snapshot = FamilySnapshot.parse(store.cached()!!)
            assertEquals(1, snapshot.stickerBalance)
            assertEquals(Redemption(request.id, "함께 산책", 2, "approved"), snapshot.redemptions.single())
            assertTrue(snapshot.rewards.isEmpty())
            assertTrue(store.pending().isEmpty())
        }
    }

    @Test fun `invalid financial transition remains visible after restart but does not block opposite ACK lane`() {
        val family = Family()
        val first = chat("첫 인사", "child")
        val second = chat("다음 인사", "child")
        family.child.enqueue(first)
        family.child.enqueue(second)
        family.syncChild()
        val invalid = event("sticker_redeem_approve", JSONObject().put("requestId", UUID.randomUUID().toString()).put("accepted", true))
        family.guardianClient.send("101", TelegramProtocol.event(invalid))
        family.syncGuardian() // Its valid ACK arrives after the invalid financial event.
        val error = assertThrows(TelegramSyncException::class.java) { family.syncChild() }
        assertTrue(error.message!!.contains("두 휴대폰"))
        assertEquals(listOf(second.id), family.child.pending().map { it.id })
        assertEquals(1, family.child.meta("ledgerConflict"))
        assertEquals(3, family.child.meta("offset"))
        assertFalse(TelegramLedger.contains(family.child.cached()!!, invalid.id))
        assertFalse(family.telegram.sent.any { it.type == "ack" && it.id == invalid.id })
        assertTrue(family.telegram.sent.any { it.type == "event" && it.id == second.id })
        family.child = MemoryStore(family.child.saved())
        assertThrows(TelegramSyncException::class.java) { family.syncChild() }
    }

    @Test fun `Telegram retry after prevents all exchange traffic until persisted deadline`() {
        val family = Family()
        family.child.enqueue(chat("안녕", "child"))
        family.telegram.rateLimitNextPollFor = 101
        val error = assertThrows(TelegramException::class.java) { family.syncChild() }
        assertEquals(429, error.errorCode)
        val calls = family.telegram.calls
        family.child = MemoryStore(family.child.saved())
        family.time += 69_000
        assertFalse(family.syncChild())
        assertEquals(calls, family.telegram.calls)
        family.time += 1_001
        assertTrue(family.syncChild())
        assertEquals(1, family.telegram.sent.count { it.type == "event" })
    }

    private class Family {
        val telegram = FakeTelegram()
        val childClient = TelegramClient("101:${"c".repeat(32)}", telegram)
        val guardianClient = TelegramClient("202:${"g".repeat(32)}", telegram)
        var child = MemoryStore()
        var guardian = MemoryStore()
        var time = 1_000_000L
        val notifications = mutableListOf<String>()
        fun syncChild() = TelegramExchange(childClient, child, 202, "guardian", now = { time },
            onReceived = { notifications += it.id }).synchronize()
        fun syncGuardian() = TelegramExchange(guardianClient, guardian, 101, "child", now = { time },
            onReceived = { notifications += it.id }).synchronize()
        fun drain() { repeat(20) { syncChild(); syncGuardian(); time++ } }
    }

    private class MemoryStore(saved: String? = null) : TelegramExchangeStore {
        private var data = saved?.let(::JSONObject) ?: JSONObject().put("state", TelegramLedger.emptyState())
            .put("meta", JSONObject()).put("pending", JSONArray()).put("receipts", JSONArray())
        var failCommit = false
        fun saved(): String = data.toString()
        override fun <T> transaction(block: () -> T): T {
            val before = saved()
            try {
                val result = block()
                if (failCommit) { failCommit = false; throw IOException("simulated commit failure") }
                return result
            } catch (error: Throwable) { data = JSONObject(before); throw error }
        }
        override fun meta(name: String) = data.getJSONObject("meta").optLong(name)
        override fun setMeta(name: String, value: Long) { data.getJSONObject("meta").put(name, value) }
        override fun cached() = JSONObject(data.getJSONObject("state").toString())
        override fun cache(state: JSONObject) { data.put("state", JSONObject(state.toString())) }
        override fun pending(): List<FamilyEvent> = data.getJSONArray("pending").let { rows ->
            (0 until rows.length()).map { FamilyEvent.parse(JSONObject(rows.getJSONObject(it).toString())) }
        }
        override fun remove(id: String) { data.put("pending", JSONArray(pending().filter { it.id != id }.map { it.json() })) }
        override fun receipts(): List<String> = data.getJSONArray("receipts").let { rows -> (0 until rows.length()).map { rows.getString(it) } }
        override fun queueReceipt(id: String) { if (id !in receipts()) data.getJSONArray("receipts").put(id) }
        override fun removeReceipt(id: String) { data.put("receipts", JSONArray(receipts().filter { it != id })) }
        fun enqueue(event: FamilyEvent) = transaction {
            cache(TelegramLedger.apply(cached(), event))
            data.getJSONArray("pending").put(event.json())
        }
    }

    private data class Send(val from: Long, val type: String, val id: String)
    private class FakeTelegram : TelegramHttpTransport {
        val sent = mutableListOf<Send>()
        var calls = 0
        var loseEventResponseFrom: Long? = null
        var loseAckResponseFrom: Long? = null
        var dropAckFrom: Long? = null
        var rateLimitNextPollFor: Long? = null
        private val inbox = mutableMapOf<Long, MutableList<JSONObject>>()
        private val nextUpdate = mutableMapOf<Long, Long>()
        override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse {
            calls++
            val botId = token.substringBefore(':').toLong()
            val body = JSONObject(json)
            when (method) {
                "getUpdates" -> {
                    if (rateLimitNextPollFor == botId) {
                        rateLimitNextPollFor = null
                        return TelegramHttpResponse(429, JSONObject().put("ok", false).put("error_code", 429)
                            .put("parameters", JSONObject().put("retry_after", 70)).toString())
                    }
                    val queue = inbox.getOrPut(botId) { mutableListOf() }
                    queue.removeAll { it.getLong("update_id") < body.getLong("offset") }
                    return ok(JSONArray(queue.take(100)))
                }
                "sendMessage" -> {
                    val destination = body.getLong("chat_id")
                    val packet = JSONObject(body.getString("text"))
                    val type = packet.getString("type")
                    val id = if (type == "event") packet.getJSONObject("event").getString("id") else packet.optString("id")
                    sent += Send(botId, type, id)
                    if (type == "ack" && dropAckFrom == botId) dropAckFrom = null
                    else {
                        val updateId = nextUpdate.getOrDefault(destination, 1L)
                        nextUpdate[destination] = updateId + 1
                        inbox.getOrPut(destination) { mutableListOf() }.add(JSONObject().put("update_id", updateId)
                            .put("message", JSONObject().put("text", body.getString("text"))
                                .put("from", JSONObject().put("id", botId).put("is_bot", true))
                                .put("chat", JSONObject().put("id", botId).put("type", "private"))))
                    }
                    if (type == "event" && loseEventResponseFrom == botId) { loseEventResponseFrom = null; throw IOException("lost response") }
                    if (type == "ack" && loseAckResponseFrom == botId) { loseAckResponseFrom = null; throw IOException("lost response") }
                    return ok(JSONObject().put("chat", JSONObject().put("id", destination).put("type", "private")))
                }
                else -> error("Unexpected method $method")
            }
        }
        private fun ok(result: Any) = TelegramHttpResponse(200, JSONObject().put("ok", true).put("result", result).toString())
    }
}
