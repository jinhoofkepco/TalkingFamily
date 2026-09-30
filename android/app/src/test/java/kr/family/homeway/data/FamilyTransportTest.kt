package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class FamilyTransportTest {
    @Test fun `large backlog uses one bounded document and acknowledges every original`() {
        val h = Harness(); h.confirmFiles()
        val messages = (1..128).map { h.enqueue("압축 파일 기록 $it") }
        h.sync(101); h.sync(202)
        assertEquals(1, h.telegram.documentRequests.count { it.sender == 101L })
        val document = h.telegram.documentRequests.single { it.sender == 101L }
        assertEquals(128, JSONObject(document.caption).getInt("count"))
        assertEquals(messages.map { it.id }.toSet(), h.stores.getValue(202).chatHistory(h.room.id, limit = 200).messages.map { it.id }.toSet())
        repeat(12) { h.advance(); h.sync(101); h.sync(202) }
        assertTrue(h.stores.getValue(101).pendingChatDeliveries(h.room.id).isEmpty())
        assertEquals(128, h.notifications.size)
        assertTrue(h.telegram.sendRequests.none { it.sender == 101L && JSONObject(it.text).optString("type") == "chat" })
    }

    @Test fun `downgraded document peer falls back to original texts after bounded missing ACKs`() {
        val h = Harness(); h.confirmFiles()
        val messages = (1..40).map { h.enqueue("파일을 모르는 구버전 $it") }
        h.oldPeer = true
        repeat(200) { h.sync(101); h.sync(202); h.advance() }
        assertEquals(2, h.telegram.documentRequests.count { it.sender == 101L })
        assertTrue(h.stores.getValue(101).pendingChatDeliveries(h.room.id).isEmpty())
        assertEquals(messages.map { it.id }.toSet(), h.stores.getValue(202).chatHistory(h.room.id).messages.map { it.id }.toSet())
        assertEquals(messages.map { it.digest }, h.telegram.sendRequests.filter {
            it.sender == 101L && JSONObject(it.text).optString("type") == "chat"
        }.map { FamilyChatMessage.parse(JSONObject(it.text).getJSONObject("message")).digest })
    }

    @Test fun `document fallback survives per-original bookkeeping eviction`() {
        val h = Harness(); h.confirmFiles()
        val member = h.room.members.single { it.botId == 202L }
        val texts = (1..128).map { FamilyChatProtocol.message(h.enqueue("원본 슬롯 $it")) }
        assertEquals(128, h.transport(101).send(member, texts))
        h.time += 31_000
        assertEquals(128, h.transport(101).send(member, texts))
        val transport = h.transport(101)
        repeat(128) { transport.noteReplayedPacket(202, UUID.randomUUID().toString()) }
        h.time += 31_000
        val recovered = h.transport(101)
        assertTrue(recovered.send(member, texts) in 1..16)
        assertFalse(recovered.supportsFiles(202))
        assertEquals(2, h.telegram.documentRequests.count { it.sender == 101L })
    }

    @Test fun `healthy incoming files cannot cancel missing outbound ACK fallback`() {
        val h = Harness(); h.confirmFiles()
        val member = h.room.members.single { it.botId == 202L }
        val texts = (1..40).map { FamilyChatProtocol.message(h.enqueue("외부 수신 확인 $it")) }
        assertEquals(40, h.transport(101).send(member, texts))
        h.time += 31_000
        assertEquals(40, h.transport(101).send(member, texts))
        val incoming = FamilyDocumentProtocol.encode(List(17) { FamilyChatProtocol.message(FamilyChatMessage(
            UUID.randomUUID().toString(), h.room.id, 202, "반대 방향 파일 $it", "2026-09-29T12:00:00Z")) }, h.room.id, 202, 101)!!
        val update = update(202, "").also { it.getJSONObject("message").apply {
            remove("text"); put("caption", incoming.caption); put("document", JSONObject().put("file_id", "inbound_file").put("file_size", incoming.bytes.size))
        } }
        fun receiveProof() {
            val transport = h.transport(101)
            val prepared = transport.prepareDocument(update)!!
            val originals = FamilyDocumentProtocol.decode(prepared, incoming.bytes, h.room, 101)
            h.stores.getValue(101).transaction { transport.noteDownloadedDocument(prepared, originals) }
        }
        receiveProof()
        h.time += 31_000
        val transport = h.transport(101)
        assertTrue(transport.send(member, texts) in 1..16)
        assertFalse(transport.supportsFiles(202))
        receiveProof()
        assertFalse(h.transport(101).supportsFiles(202))
        assertEquals(2, h.telegram.documentRequests.count { it.sender == 101L })
    }

    @Test fun `backwards wall clock cannot strand unacknowledged document fallback`() {
        val h = Harness(); h.confirmFiles()
        val member = h.room.members.single { it.botId == 202L }
        val texts = (1..17).map { FamilyChatProtocol.message(h.enqueue("시계 변경 뒤에도 $it")) }
        assertEquals(17, h.transport(101).send(member, texts))
        h.time -= 10_000
        assertEquals(17, h.transport(101).send(member, texts))
        h.advance()
        val transport = h.transport(101)
        assertTrue(transport.send(member, texts) in 1..16)
        assertFalse(transport.supportsFiles(202))
        assertEquals(2, h.telegram.documentRequests.count { it.sender == 101L })
    }

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
        repeat(90) { h.sync(101); h.sync(202); h.advance() }
        assertTrue(h.stores.getValue(101).pendingChatDeliveries(h.room.id).isEmpty())
        assertEquals(messages.map { it.id }.toSet(), h.stores.getValue(202).chatHistory(h.room.id).messages.map { it.id }.toSet())
        val retries = h.telegram.sendRequests.filter { it.sender == 101L && JSONObject(it.text).optString("type") == "chat" }
        assertEquals(messages.map { it.digest }, retries.map { FamilyChatMessage.parse(JSONObject(it.text).getJSONObject("message")).digest })
    }

    @Test fun `attempted backlog from previous app batches again without replacing identities`() {
        val h = Harness(); h.confirm()
        val messages = (1..12).map { h.enqueue("이전 발신 시도 $it") }
        messages.forEach { h.stores.getValue(101).markChatSent(h.room.id, it.id, 202, h.time - 31_000) }
        h.sync(101); h.sync(202); h.advance(); h.sync(101)
        val data = h.telegram.sendRequests.single { it.sender == 101L && JSONObject(it.text).optString("type") == "batch" }
        assertEquals(messages.map { it.id }, FamilyTransportProtocol.unpack(JSONObject(data.text))
            .map { JSONObject(it).getJSONObject("message").getString("id") })
        assertTrue(h.stores.getValue(101).pendingChatDeliveries(h.room.id).isEmpty())
        assertEquals(messages.size, h.notifications.size)
    }

    @Test fun `ordinary repeated batch duplicates keep receipts batched`() {
        val h = Harness(); h.confirm()
        val messages = (1..5).map { h.enqueue("반복 묶음 $it") }
        h.sync(101)
        val batch = h.telegram.sendRequests.single { it.sender == 101L && JSONObject(it.text).optString("type") == "batch" }.text
        h.sync(202)
        repeat(3) { h.telegram.inject(101, 202, batch); h.advance(); h.sync(202) }
        assertEquals(messages.size, h.notifications.size)
        assertTrue(h.telegram.sendRequests.none { it.sender == 202L && JSONObject(it.text).optString("type") == "chat_ack" })
        assertTrue(h.transport(202).canBatchAcknowledgements(101, messages.map { it.id }))
    }

    @Test fun `downgraded sender recovers its replay ACK without disabling unrelated receipt batches`() {
        val h = Harness(); h.confirm()
        val messages = (1..3).map { h.enqueue("발신앱 구버전 $it") }
        h.sync(101)
        h.telegram.dropBatchFrom = 202
        h.sync(202)
        h.oldSender = true
        repeat(12) { h.time += 31_000; h.sync(101); h.sync(202) }
        assertTrue(h.stores.getValue(101).pendingChatDeliveries(h.room.id).isEmpty())
        assertEquals(messages.size, h.notifications.size)
        val transport = h.transport(202)
        assertTrue(transport.supportsBatch(101))
        assertTrue(transport.canBatchAcknowledgements(101, listOf(UUID.randomUUID().toString())))
        assertTrue(h.telegram.sendRequests.any { it.sender == 202L && JSONObject(it.text).optString("type") == "chat_ack" })
    }

    @Test fun `continuous old backlog gives probes a turn then resumes the same FIFO`() {
        val h = Harness(); h.oldPeer = true
        val messages = (1..8).map { h.enqueue("협상 중에도 $it") }
        messages.forEach { h.stores.getValue(101).markChatSent(h.room.id, it.id, 202, h.time - 31_000) }
        repeat(90) { h.sync(101); h.sync(202); h.advance() }
        assertEquals(1, h.telegram.sendRequests.count { it.sender == 101L && JSONObject(it.text).optString("type") == "capabilities" })
        assertTrue(h.stores.getValue(101).pendingChatDeliveries(h.room.id).isEmpty())
        assertEquals(messages.size, h.notifications.size)
    }

    @Test fun `failed capability HTTP retries shortly while old packets continue`() {
        val h = Harness(); h.oldPeer = true
        h.telegram.failCapabilityFrom = 101
        h.enqueue("협상 실패 후에도")
        repeat(20) { h.sync(101); h.sync(202); h.advance() }
        assertEquals(2, h.telegram.sendRequests.count { it.sender == 101L && JSONObject(it.text).optString("type") == "capabilities" })
        assertTrue(h.stores.getValue(101).pendingChatDeliveries(h.room.id).isEmpty())
        assertEquals(1, h.notifications.size)
    }

    @Test fun `failed capability reply respects its deadline while the other feature reply progresses`() {
        val h = Harness(); val transport = h.transport(101)
        transport.incoming(update(202, FamilyTransportProtocol.capability(42, false)))
        transport.incoming(update(202, FamilyTransportProtocol.careCapability(43, false)))
        h.telegram.failCapabilityFrom = 101
        transport.flushControls(true)
        assertEquals(1, h.telegram.sendRequests.size)
        h.advance(); transport.flushControls(true)
        assertEquals(2, h.telegram.sendRequests.size)
        assertEquals("care_capabilities", JSONObject(h.telegram.sendRequests.last().text).getString("type"))
        repeat(5) { h.advance(); transport.flushControls(true) }
        assertEquals(2, h.telegram.sendRequests.size)
        // The batch reply remains durable across exchange reconstruction and retries at its 15s deadline.
        h.time += 8_000
        h.transport(101).flushControls(true)
        assertEquals(3, h.telegram.sendRequests.size)
        val retry = JSONObject(h.telegram.sendRequests.last().text)
        assertEquals("capabilities", retry.getString("type"))
        assertEquals(42L, retry.getLong("nonce"))
        assertTrue(retry.getBoolean("reply"))
        h.advance(); h.transport(101).flushControls(true)
        assertEquals(3, h.telegram.sendRequests.size)
    }

    @Test fun `care capability is separately authenticated and does not extend old strict batch controls`() {
        val h = Harness(); val transport = h.transport(101)
        assertNull(transport.incoming(update(202, FamilyTransportProtocol.careCapability(42, true))))
        assertNull(transport.incoming(update(999, FamilyTransportProtocol.careCapability(42, false))))
        assertFalse(transport.supportsCareDeltas(202))
        assertEquals(emptyList<JSONObject>(), transport.incoming(update(202, FamilyTransportProtocol.careCapability(42, false))))
        assertTrue(transport.supportsCareDeltas(202))
        assertTrue(FamilyTransport.supportsCareDeltas(h.stores.getValue(101), h.room.id, 202, h.time))
        assertFalse(transport.supportsBatch(202))
        val old = JSONObject(FamilyTransportProtocol.capability(42, false)).put("delta", 1)
        assertNull(transport.incoming(update(202, old.toString())))
        assertFalse(transport.supportsBatch(202))
    }

    @Test fun `individual replays fall back only the repeated original ACK and expire promptly`() {
        val h = Harness(); h.confirm()
        val messages = (1..3).map { h.enqueue("개별 확인응답 $it") }
        val transport = h.transport(202)
        val channel = h.channel(202, transport)
        messages.forEach { channel.processUpdate(update(101, FamilyChatProtocol.message(it))) }
        channel.processUpdate(update(101, FamilyChatProtocol.message(messages[0])))
        assertTrue(transport.canBatchAcknowledgements(101, messages.map { it.id }))
        channel.processUpdate(update(101, FamilyChatProtocol.message(messages[0])))
        assertFalse(transport.canBatchAcknowledgements(101, listOf(messages[0].id)))
        assertTrue(transport.canBatchAcknowledgements(101, messages.drop(1).map { it.id }))
        assertTrue(transport.supportsBatch(101))
        h.time += 121_000
        assertTrue(transport.canBatchAcknowledgements(101, messages.map { it.id }))
    }

    @Test fun `one compatibility ACK at the end leaves the preceding receipt batch intact`() {
        val h = Harness(); h.confirm()
        val messages = (1..15).map { h.enqueue("섞인 확인응답 $it") }
        val transport = h.transport(202)
        val channel = h.channel(202, transport)
        messages.forEach { channel.processUpdate(update(101, FamilyChatProtocol.message(it))) }
        repeat(2) { channel.processUpdate(update(101, FamilyChatProtocol.message(messages.last()))) }
        channel.flush()
        val batch = h.telegram.sendRequests.single { it.sender == 202L && JSONObject(it.text).optString("type") == "batch" }
        val ids = FamilyTransportProtocol.unpack(JSONObject(batch.text)).map { JSONObject(it).getString("id") }
        assertEquals(messages.dropLast(1).map { it.id }, ids)
        assertEquals(listOf(messages.last().id), h.stores.getValue(202).chatReceipts(h.room.id).map { it.messageId })
    }

    @Test fun `unacknowledged delta attempts invalidate care lease only after a bounded retry window`() {
        val h = Harness(); val transport = h.transport(101)
        transport.incoming(update(202, FamilyTransportProtocol.careCapability(42, false)))
        val member = h.room.members.single { it.botId == 202L }
        val outgoing = FamilyCareProtocol.outgoing(h.room, 101, 101, 202, "care_delta", JSONObject())
        assertEquals(1, transport.send(member, listOf(outgoing.text)))
        assertFalse(transport.shouldFallbackCarePacket(202, outgoing.packetId))
        h.time += 31_000
        transport.flushControls(false); h.advance()
        assertEquals(1, transport.send(member, listOf(outgoing.text)))
        assertFalse(transport.shouldFallbackCarePacket(202, outgoing.packetId))
        h.time += 31_000
        assertTrue(transport.shouldFallbackCarePacket(202, outgoing.packetId))
        transport.noteCareDeltaFallback(202)
        assertFalse(transport.supportsCareDeltas(202))
        transport.noteAcknowledgedPacket(202, outgoing.packetId)
        assertFalse(transport.shouldFallbackCarePacket(202, outgoing.packetId))
    }

    @Test fun `data gets a turn when receipt traffic would otherwise occupy every permit`() {
        val h = Harness(); h.confirm()
        val transport = h.transport(101)
        val member = h.room.members.single { it.botId == 202L }
        val message = h.enqueue("확인응답 사이의 메시지")
        val acknowledgement = FamilyChatProtocol.receipt(FamilyChatReceipt(h.room.id, UUID.randomUUID().toString(), 202, "0".repeat(64)))
        assertEquals(1, transport.send(member, listOf(acknowledgement)))
        assertEquals(0, transport.send(member, listOf(FamilyChatProtocol.message(message))))
        h.advance()
        // An optional capability gets one turn; then ACKs yield to the waiting data lane.
        transport.flushControls(false); h.advance()
        assertEquals(0, transport.send(member, listOf(acknowledgement)))
        assertEquals(1, transport.send(member, listOf(FamilyChatProtocol.message(message))))
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
        var oldSender = false
        fun advance() { time += 1_200 }
        fun client(id: Long) = TelegramClient("$id:${"a".repeat(32)}", telegram)
        fun transport(id: Long) = FamilyTransport(client(id), stores.getValue(id), room, id, now = { time })
        fun channel(id: Long, transport: FamilyTransport? = null) = FamilyChatExchange(client(id), stores.getValue(id), room, id,
            now = { time }, onReceived = { notifications += it.id }, transport = transport)
        fun enqueue(text: String) = FamilyChatMessage(UUID.randomUUID().toString(), room.id, 101, text, "2026-09-29T12:00:00Z")
            .also { channel(101).enqueue(it) }
        fun sync(id: Long) {
            val transport = if (oldPeer && id == 202L || oldSender && id == 101L) null else transport(id)
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
        fun confirmFiles() {
            for ((own, peer) in listOf(101L to 202L, 202L to 101L)) {
                for (control in listOf(FamilyTransportProtocol.capability(42, false), FamilyTransportProtocol.careCapability(43, false),
                    FamilyTransportProtocol.fileCapability(44, false), FamilyTransportProtocol.latestCapability(45, false))) {
                    val transport = transport(own)
                    transport.incoming(update(peer, control)); transport.flushControls(true); advance()
                }
            }
            sync(101); sync(202); advance(); telegram.sendRequests.clear(); telegram.documentRequests.clear()
        }
    }
    companion object {
        private fun update(sender: Long, text: String) = JSONObject().put("update_id", 1).put("message", JSONObject().put("text", text)
            .put("from", JSONObject().put("id", sender).put("is_bot", true))
            .put("chat", JSONObject().put("id", sender).put("type", "private")))
    }
}
