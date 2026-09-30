package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class TelegramExchangeLaneFairnessTest {
    @Test fun careHistoryProgressesBeforeContinuouslyReadyLegacyBacklogDrains() {
        val h = Harness()
        h.enqueue(legacy = 100, care = 100)
        repeat(12) { h.round() }
        assertTrue(h.stores.getValue(101).pending().size >= 80)
        assertTrue(h.stores.getValue(202).care.archived.size >= 4)
        assertTrue(h.engine(202).currentSnapshot(101)!!.revision >= 4)
        assertTrue(h.childSends("event") >= 4)
        assertTrue(h.childSends("child_event") >= 4)
    }

    @Test fun competingChatLegacyCareAndLatestKeepAcknowledgementsAndAllDataLanesMoving() {
        val h = Harness(latest = true)
        h.enqueue(legacy = 40, care = 40, chats = 40)
        h.stores.getValue(202).queuePacket(FamilyCareProtocol.outgoing(h.room, 202, 101, 101, "sync_request", JSONObject()))
        repeat(90) { h.round() }
        assertTrue(h.childSends("event") > 0)
        assertTrue(h.childSends("chat") > 0)
        assertTrue(h.childSends("child_event") > 0)
        assertTrue(h.childSends("care_location_head") > 0)
        assertTrue(h.childSends("care_ack") > 0)
        assertTrue(h.telegram.sendRequests.any { it.sender == 202L && JSONObject(it.text).optString("type") == "chat_ack" })
        assertTrue(h.telegram.sendRequests.any { it.sender == 202L && JSONObject(it.text).optString("type") == "ack" })
        assertTrue(h.stores.getValue(202).chatHistory(h.room.id).messages.isNotEmpty())
        assertNotNull(h.engine(202).latestLocation(101))
        assertTrue(h.stores.getValue(202).care.archived.size >= 20)
    }

    @Test fun inactiveLanesDoNotDelayANormalChatOrItsAcknowledgement() {
        val h = Harness()
        h.stores.getValue(101).setMeta(TelegramExchange.NEXT_FLUSH_LANE, 2)
        h.enqueue(chats = 1)
        h.round()
        assertEquals(1, h.childSends("chat"))
        assertEquals(1, h.stores.getValue(202).chatHistory(h.room.id).messages.size)
        assertTrue(h.telegram.sendRequests.any { it.sender == 202L && JSONObject(it.text).optString("type") == "chat_ack" })
        h.sync(101)
        assertTrue(h.stores.getValue(101).pendingChatDeliveries(h.room.id).isEmpty())
    }

    private class Harness(private val latest: Boolean = false) {
        val room = FamilyChatRoom.create("가족", listOf(FamilyChatMember(101, "@member101_bot", "아들", "son"),
            FamilyChatMember(202, "@member202_bot", "아빠", "father")))
        val stores = listOf(101L, 202L).associateWith { CombinedStore() }
        val telegram = FamilyChatExchangeTest.FakeTelegram()
        var time = 1_000_000L
        init {
            val epoch = UUID.randomUUID().toString()
            for (id in listOf(101L, 202L)) {
                stores.getValue(id).saveState(FamilyCareState(room.id, 101, epoch, 0, id == 101L, TelegramLedger.emptyState()))
                val peer = if (id == 101L) 202 else 101
                // Keep this regression about lane ordering, independent of optional negotiation.
                for (prefix in listOf("batch", "care", "file", "latest", "history"))
                    stores.getValue(id).setMeta("transport:${room.id}:$peer:$prefix:probeAt", time)
                if (latest) stores.getValue(id).setMeta("transport:${room.id}:$peer:latestSupportedUntil", time + 1_000_000)
            }
        }
        private fun client(id: Long) = TelegramClient("$id:${"a".repeat(32)}", telegram)
        private fun transport(id: Long) = FamilyTransport(client(id), stores.getValue(id), room, id, now = { time })
        fun engine(id: Long, sender: FamilyTransport? = null) = FamilyCareEngine(client(id), stores.getValue(id), room, id,
            now = { time }, transport = sender, canSendLatestLocation = { latest })
        private fun chat(id: Long, sender: FamilyTransport? = null) = FamilyChatExchange(client(id), stores.getValue(id), room, id,
            now = { time }, transport = sender)
        fun enqueue(legacy: Int = 0, care: Int = 0, chats: Int = 0) {
            repeat(legacy) { index -> stores.getValue(101).chat.enqueueLegacy(FamilyEvent(UUID.randomUUID().toString(), "chat",
                JSONObject().put("text", "기존 메시지 $index"), "child", "2026-09-30T12:00:00Z", "pending")) }
            repeat(care) { index ->
                val measuredAt = Instant.parse("2026-09-30T12:00:00Z").plusSeconds(index.toLong()).toString()
                engine(101).emitChildEvent(FamilyEvent(UUID.randomUUID().toString(), "location", JSONObject()
                    .put("latitude", 37.0 + index * 0.00001).put("longitude", 127.0).put("accuracy", 12.0)
                    .put("capturedAt", measuredAt).put("source", "automatic"), "child", measuredAt, "relayed"))
            }
            repeat(chats) { index -> chat(101).enqueue(FamilyChatMessage(UUID.randomUUID().toString(), room.id, 101,
                "가족방 메시지 $index", "2026-09-30T12:00:00Z")) }
        }
        fun sync(id: Long) {
            val sender = transport(id)
            TelegramExchange(client(id), stores.getValue(id), if (id == 101L) 202 else 101,
                if (id == 101L) "guardian" else "child", now = { time }, transport = sender,
                familyChat = chat(id, sender), familyCare = engine(id, sender)).synchronize()
        }
        fun round() { sync(101); sync(202); time += 1_200 }
        fun childSends(type: String) = telegram.sendRequests.count { it.sender == 101L && JSONObject(it.text).optString("type") == type }
    }

    private class CombinedStore(val chat: FamilyChatExchangeTest.MemoryStore = FamilyChatExchangeTest.MemoryStore(),
        val care: FamilyCareEngineTest.MemoryStore = FamilyCareEngineTest.MemoryStore()) :
        TelegramExchangeStore by chat, FamilyChatStore by chat, FamilyCareStore by care {
        override fun <T> transaction(block: () -> T): T = chat.transaction { care.transaction(block) }
    }
}
