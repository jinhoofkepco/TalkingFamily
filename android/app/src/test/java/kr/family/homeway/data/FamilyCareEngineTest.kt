package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPOutputStream

class FamilyCareEngineTest {
    @Test fun `both parents receive independent son and daughter boards without sibling disclosure`() {
        val f = Family()
        f.drain()
        for (parent in listOf(101L, 202L)) {
            assertEquals(5, f.engine(parent).currentSnapshot(303)!!.snapshot.stickerBalance)
            assertEquals(0, f.engine(parent).currentSnapshot(404)!!.snapshot.stickerBalance)
        }
        assertNull(f.engine(303).currentSnapshot(404))
        assertNull(f.engine(404).currentSnapshot(303))
        assertTrue(f.http.sent.all { (from, to, _) -> (from in listOf(101L, 202L)) != (to in listOf(101L, 202L)) })
    }

    @Test fun `simultaneous parent approvals debit once and publish the same terminal decision`() {
        val f = Family(); f.drain()
        val request = f.engine(303).enqueueCommand(303, "sticker_redeem_request", request(f.rewardId))
        f.drain()
        val first = f.engine(101).enqueueCommand(303, "sticker_redeem_approve", approval(request, true))
        val second = f.engine(202).enqueueCommand(303, "sticker_redeem_approve", approval(request, true))
        f.drain()
        for (id in listOf(101L, 202L, 303L)) {
            assertEquals(2, f.engine(id).currentSnapshot(303)!!.snapshot.stickerBalance)
            assertEquals("approved", f.engine(id).currentSnapshot(303)!!.snapshot.redemptions.single().status)
        }
        assertTrue(f.stores.getValue(303).outcome(f.room.id, first)!!.accepted)
        assertFalse(f.stores.getValue(303).outcome(f.room.id, second)!!.accepted)
        assertTrue(f.engine(202).status(303)!!.contains("이미"))
        assertFalse(f.engine(101).hasPending(303))
        assertFalse(f.engine(202).hasPending(303))
    }

    @Test fun `opposite parent decisions never reopen a request`() {
        val f = Family(); f.drain()
        val request = f.engine(303).enqueueCommand(303, "sticker_redeem_request", request(f.rewardId))
        f.drain()
        f.engine(101).enqueueCommand(303, "sticker_redeem_approve", approval(request, false))
        f.engine(202).enqueueCommand(303, "sticker_redeem_approve", approval(request, true))
        f.drain()
        assertEquals(5, f.engine(303).currentSnapshot(303)!!.snapshot.stickerBalance)
        assertEquals("rejected", f.engine(202).currentSnapshot(303)!!.snapshot.redemptions.single().status)
    }

    @Test fun `stale reward edit cannot overwrite another parent and deletion keeps a version tombstone`() {
        val f = Family(); f.drain()
        f.engine(101).enqueueCommand(303, "reward_upsert", reward(f.rewardId, "영화", 4))
        val stale = f.engine(202).enqueueCommand(303, "reward_upsert", reward(f.rewardId, "게임", 2))
        f.drain()
        assertEquals("영화", f.engine(202).currentSnapshot(303)!!.snapshot.rewards.single().name)
        assertFalse(f.stores.getValue(202).outcome(f.room.id, stale)!!.accepted)
        val version = f.engine(101).currentSnapshot(303)!!.rewardVersion(f.rewardId)
        f.engine(101).enqueueCommand(303, "reward_delete", JSONObject().put("rewardId", f.rewardId))
        f.drain()
        val resurrection = f.engine(202).enqueueCommand(303, "reward_upsert", reward(f.rewardId, "오래된 화면", 1), expectedRewardVersion = version)
        f.drain()
        assertFalse(f.stores.getValue(202).outcome(f.room.id, resurrection)!!.accepted)
        assertTrue(f.engine(303).currentSnapshot(303)!!.snapshot.rewards.isEmpty())
        assertEquals(version + 1, f.engine(303).currentSnapshot(303)!!.rewardVersion(f.rewardId))
    }

    @Test fun `transport ACK does not complete a command before committed state arrives`() {
        val f = Family(); f.drain()
        f.engine(101).enqueueCommand(303, "sticker_award", award())
        f.sync(101)
        f.sync(303)
        f.http.pauseBeforeType = 101L to "outcome"
        f.sync(101)
        assertTrue(f.stores.getValue(101).pendingPackets(f.room.id).isEmpty())
        assertTrue(f.engine(101).hasPending(303))
        assertEquals(5, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
        f.http.pauseBeforeType = null
        f.drain()
        assertFalse(f.engine(101).hasPending(303))
        assertEquals(6, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
    }

    @Test fun `one offline child never blocks the other child or the second parent`() {
        val f = Family(); f.drain()
        f.engine(101).enqueueCommand(303, "sticker_award", award())
        f.engine(101).enqueueCommand(404, "sticker_award", award())
        f.drain(listOf(101, 202, 404))
        assertTrue(f.engine(101).hasPending(303))
        assertFalse(f.engine(101).hasPending(404))
        assertEquals(1, f.engine(202).currentSnapshot(404)!!.snapshot.stickerBalance)
        f.time += 31_000
        f.drain()
        assertEquals(6, f.engine(202).currentSnapshot(303)!!.snapshot.stickerBalance)
    }

    @Test fun `duplicate command and lost HTTP response survive engine restart without double award`() {
        val f = Family(); f.drain()
        val id = f.engine(101).enqueueCommand(303, "sticker_award", award())
        val packet = f.stores.getValue(101).pendingPackets(f.room.id).single()
        f.http.loseResponseFrom = 101
        f.sync(101)
        f.time += 31_000
        f.drain()
        f.http.inject(101, 303, packet.text)
        f.drain()
        assertEquals(6, f.engine(303).currentSnapshot(303)!!.snapshot.stickerBalance)
        assertEquals(1, f.stores.getValue(303).outcomes(f.room.id, 303).count { it.commandId == id })
        assertFalse(f.engine(101).hasPending(303))
    }

    @Test fun `conflicting duplicate and forged acknowledgement do not change accepted state`() {
        val f = Family(); f.drain()
        f.engine(101).enqueueCommand(303, "sticker_award", award())
        val packet = f.stores.getValue(101).pendingPackets(f.room.id).single()
        val fakeAck = FamilyCareProtocol.envelope(f.room, 303, 303, "care_ack", JSONObject().put("digest", packet.digest), packet.packetId)
        f.http.inject(303, 101, fakeAck.toString())
        f.sync(101) // ACK predates durable sentAt, so it cannot discard the command.
        assertEquals(1, f.stores.getValue(101).pendingPackets(f.room.id).size)
        f.drain()
        val changed = JSONObject(packet.text)
        changed.getJSONObject("body").getJSONObject("payload").put("reason", "다른 내용")
        f.http.inject(101, 303, changed.toString())
        f.sync(303)
        assertEquals(6, f.engine(303).currentSnapshot(303)!!.snapshot.stickerBalance)
        assertTrue(f.stores.getValue(303).receipts(f.room.id).isEmpty())
    }

    @Test fun `failed receive transaction rolls back command balance queue and shared offset`() {
        val f = Family(); f.drain()
        val id = f.engine(101).enqueueCommand(303, "sticker_award", award())
        f.sync(101)
        val store = f.stores.getValue(303)
        val offset = store.meta("offset")
        store.failCommit = true
        assertThrows(IOException::class.java) { f.sync(303) }
        assertEquals(offset, store.meta("offset"))
        assertEquals(5, store.state(f.room.id, 303)!!.snapshot.stickerBalance)
        assertNull(store.outcome(f.room.id, id))
        f.drain()
        assertEquals(6, store.state(f.room.id, 303)!!.snapshot.stickerBalance)
    }

    @Test fun `failed ACK storage cannot advance the shared receive offset`() {
        val f = Family(); f.drain()
        f.engine(101).enqueueCommand(303, "sticker_award", award())
        f.sync(101); f.sync(303)
        val store = f.stores.getValue(101)
        val offset = store.meta("offset")
        store.failAcknowledge = true
        assertThrows(IOException::class.java) { f.sync(101) }
        assertEquals(offset, store.meta("offset"))
        assertEquals(1, store.pendingPackets(f.room.id).size)
        f.drain()
        assertFalse(f.engine(101).hasPending(303))
        assertEquals(6, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
    }

    @Test fun `child telemetry reaches both parents and is never sent to siblings`() {
        val f = Family(); f.drain()
        val event = location()
        f.engine(303).emitChildEvent(event)
        f.drain()
        for (parent in listOf(101L, 202L)) {
            assertEquals(event.id, f.engine(parent).currentSnapshot(303)!!.snapshot.events.last { it.kind == "location" }.id)
            assertTrue(f.stores.getValue(parent).archived.any { it.first == 303L && it.second.id == event.id })
        }
        assertTrue(f.stores.getValue(404).archived.none { it.second.id == event.id })
        assertTrue(f.http.sent.filter { JSONObject(it.third).optString("type") == "child_event" }.all { it.second in listOf(101L, 202L) })
        assertTrue(f.stores.getValue(303).pendingPackets(f.room.id).isEmpty())
    }

    @Test fun `vertical step evidence survives the care wire and both parent archives without inventing GPS`() {
        val f = Family(); f.drain()
        val vertical = FamilyEvent(UUID.randomUUID().toString(), "vertical", JSONObject()
            .put("phase", "ascent_finished").put("relativeMeters", 3.2)
            .put("measuredAt", "2026-09-22T12:00:00Z").put("confidence", "estimated")
            .put("evidence", "barometer_steps"), "child", "2026-09-22T12:00:05Z", "pending")
        f.engine(303).emitChildEvent(vertical)
        f.drain()
        for (parent in listOf(101L, 202L)) {
            val received = f.engine(parent).currentSnapshot(303)!!.snapshot.events.single { it.id == vertical.id }
            val archived = f.stores.getValue(parent).archived.single { it.first == 303L && it.second.id == vertical.id }.second
            for (record in listOf(received, archived)) {
                assertEquals("barometer_steps", record.payload.getString("evidence"))
                assertEquals("estimated", record.payload.getString("confidence"))
                assertEquals("2026-09-22T12:00:00Z", record.measuredAt)
                assertEquals(3.2, record.payload.getDouble("relativeMeters"), 0.0)
                assertFalse(record.payload.has("latitude"))
                assertFalse(record.payload.has("longitude"))
            }
        }
        assertTrue(f.stores.getValue(404).archived.none { it.second.id == vertical.id })
        assertTrue(f.stores.getValue(303).pendingPackets(f.room.id).isEmpty())
    }

    @Test fun `pipeline sends eight packets per flush and caps each parent at sixteen unacknowledged packets`() {
        val f = Family(); f.drain(); f.http.sent.clear()
        repeat(20) { f.engine(303).emitChildEvent(location()) }
        f.engine(303).flush()
        for (parent in listOf(101L, 202L)) assertEquals(8, f.http.sent.count { it.first == 303L && it.second == parent })
        f.engine(303).flush()
        f.engine(303).flush()
        for (parent in listOf(101L, 202L)) {
            val packets = f.stores.getValue(303).pendingPackets(f.room.id).filter { it.peerId == parent }
            assertEquals(16, packets.count { it.sendConfirmed })
            assertEquals(4, packets.count { it.sentAt == 0L })
            assertEquals(packets.take(16).map { it.packetId },
                f.http.sent.filter { it.first == 303L && it.second == parent }.map { JSONObject(it.third).getString("id") })
        }
        assertFalse(f.engine(303).hasReadyWork())
        f.http.sent.clear()
        f.sync(101)
        assertEquals(8, f.http.sent.count { it.first == 101L && JSONObject(it.third).optString("type") == "care_ack" })
        assertEquals(8, f.stores.getValue(101).receipts(f.room.id).size)
        f.engine(101).flush()
        assertEquals(16, f.http.sent.count { it.first == 101L && JSONObject(it.third).optString("type") == "care_ack" })
        assertTrue(f.stores.getValue(101).receipts(f.room.id).isEmpty())
    }

    @Test fun `successful HTTP longer than retry interval does not resend the same packet in one flush`() {
        val f = Family(); f.drain(); f.http.sent.clear()
        repeat(3) { f.engine(303).emitChildEvent(location()) }
        val expected = f.stores.getValue(303).pendingPackets(f.room.id).groupBy { it.peerId }
        f.http.onSuccessfulSend = { f.time += 31_000 }
        f.engine(303).flush()
        for (parent in listOf(101L, 202L)) {
            val sent = f.http.sent.filter { it.first == 303L && it.second == parent }
                .map { JSONObject(it.third).getString("id") }
            assertEquals(expected.getValue(parent).map { it.packetId }, sent)
            assertEquals(3, sent.distinct().size)
        }
        assertTrue(f.stores.getValue(303).pendingPackets(f.room.id).all { it.sendConfirmed })
    }

    @Test fun `out of order ACK releases only its attempted packet and rejects wrong digest child sender or replay`() {
        val f = Family(); f.drain()
        repeat(3) { f.engine(303).emitChildEvent(location()) }
        f.engine(303).flush()
        val store = f.stores.getValue(303)
        val packets = store.pendingPackets(f.room.id).filter { it.peerId == 101L }
        fun receive(sender: Long = 101, child: Long = 303, digest: String = packets[1].digest) {
            val text = FamilyCareProtocol.envelope(f.room, sender, child, "care_ack",
                JSONObject().put("digest", digest), packets[1].packetId).toString()
            store.transaction { f.engine(303).processUpdate(update(sender, text)) }
        }
        receive(digest = "0".repeat(64))
        receive(child = 404)
        receive(sender = 202)
        assertEquals(6, store.pendingPackets(f.room.id).size)
        receive()
        receive() // Replay cannot discard the neighboring packet or the other parent's copy.
        assertEquals(listOf(packets[0].packetId, packets[2].packetId),
            store.pendingPackets(f.room.id).filter { it.peerId == 101L }.map { it.packetId })
        assertEquals(3, store.pendingPackets(f.room.id).count { it.peerId == 202L })
    }

    @Test fun `ambiguous middle send blocks later packets across restart while the other parent advances`() {
        val f = Family(); f.drain(); f.http.sent.clear()
        repeat(3) { f.engine(303).emitChildEvent(location()) }
        val store = f.stores.getValue(303)
        val firstParent = store.pendingPackets(f.room.id).filter { it.peerId == 101L }
        f.http.loseResponsePacketId = firstParent[1].packetId
        f.engine(303).flush()
        val interrupted = store.pendingPackets(f.room.id).filter { it.peerId == 101L }
        assertTrue(interrupted[0].sendConfirmed)
        assertTrue(interrupted[1].sentAt > 0)
        assertFalse(interrupted[1].sendConfirmed)
        assertEquals(0L, interrupted[2].sentAt)
        assertEquals(3, store.pendingPackets(f.room.id).count { it.peerId == 202L && it.sendConfirmed })

        f.time += 16_000 // Peer backoff has expired, but the ambiguous attempt still bars later sends.
        f.engine(303).flush() // Every engine() call is a fresh instance using the same durable store.
        assertEquals(2, f.http.sent.count { it.first == 303L && it.second == 101L })
        f.time += 15_000
        f.engine(303).flush()
        assertEquals(listOf(firstParent[0].packetId, firstParent[1].packetId,
            firstParent[0].packetId, firstParent[1].packetId, firstParent[2].packetId),
            f.http.sent.filter { it.first == 303L && it.second == 101L }.map { JSONObject(it.third).getString("id") })
        f.drain()
        for (parent in listOf(101L, 202L)) assertEquals(3, f.stores.getValue(parent).archived.count { it.first == 303L })
        assertTrue(store.pendingPackets(f.room.id).isEmpty())
    }

    @Test fun `ACK of an ambiguous attempt unlocks its successor without waiting for retry`() {
        val f = Family(); f.drain()
        repeat(2) { f.engine(303).emitChildEvent(location()) }
        val store = f.stores.getValue(303)
        val packets = store.pendingPackets(f.room.id).filter { it.peerId == 101L }
        f.http.loseResponsePacketId = packets[0].packetId
        f.engine(303).flush()
        assertEquals(0L, store.pendingPackets(f.room.id).single { it.packetId == packets[1].packetId }.sentAt)
        f.sync(101) // Receiver got the first packet despite the lost HTTP response and sends its ACK.
        f.sync(303)
        assertTrue(store.pendingPackets(f.room.id).single { it.packetId == packets[1].packetId }.sendConfirmed)
    }

    @Test fun `global rate limit aborts the pipeline and preserves a durable send barrier`() {
        val f = Family(); f.drain(); f.http.sent.clear()
        repeat(3) { f.engine(303).emitChildEvent(location()) }
        f.http.nextSendErrorCode = 429
        val error = assertThrows(TelegramException::class.java) { f.engine(303).flush() }
        assertEquals(429, error.errorCode)
        assertEquals(60, error.retryAfterSeconds)
        assertEquals(1, f.http.sent.size)
        val packets = f.stores.getValue(303).pendingPackets(f.room.id)
        assertEquals(1, packets.count { it.sentAt > 0 })
        assertTrue(packets.none { it.sendConfirmed })
    }

    @Test fun `twenty second locations remain bounded during ten minutes of sixty second receive polls`() {
        val f = Family(); f.drain(); f.http.polls.clear()
        val generated = mutableListOf<String>()
        val startedAt = f.time
        for (second in 0..600 step 5) {
            f.time = startedAt + second * 1000L
            if (second % 20 == 0) {
                val at = Instant.ofEpochMilli(f.time).toString()
                val event = location().let { it.copy(createdAt = at, payload = JSONObject(it.payload.toString()).put("capturedAt", at)) }
                generated.add(event.id)
                f.engine(303).emitChildEvent(event)
            }
            f.engine(303).flush() // Outgoing work does not poll Telegram or accelerate the receive schedule.
            if (second % 60 == 30) { f.sync(101); f.sync(202) }
            if (second % 60 == 55) f.sync(303)
            for (parent in listOf(101L, 202L)) assertTrue("unbounded queue at $second seconds",
                f.stores.getValue(303).pendingPackets(f.room.id).count { it.peerId == parent } <= 8)
        }
        assertEquals(mapOf(101L to 10, 202L to 10, 303L to 10), f.http.polls.groupingBy { it }.eachCount())
        f.sync(101); f.sync(202); f.sync(303)
        for (parent in listOf(101L, 202L)) {
            assertEquals(generated, f.stores.getValue(parent).archived.filter { it.first == 303L }.map { it.second.id })
            assertEquals(generated.last(), f.engine(parent).currentSnapshot(303)!!.snapshot.events.last().id)
        }
        assertTrue(f.stores.getValue(303).pendingPackets(f.room.id).isEmpty())
    }

    @Test fun `room fingerprint roles and real Telegram sender are enforced`() {
        val f = Family(); f.drain()
        val command = FamilyCareCommand(UUID.randomUUID().toString(), f.room.id, 303, 101,
            f.engine(303).currentSnapshot(303)!!.epoch, "sticker_award", award(), "2026-09-22T12:00:00Z")
        val text = FamilyCareProtocol.envelope(f.room, 101, 303, "command", command.json()).toString()
        assertNull(FamilyCareProtocol.receive(update(404, text), f.room, 303))
        val changedRoom = f.room.copy(members = f.room.members.map { if (it.botId == 101L) it.copy(relationship = "family") else it })
        assertNull(FamilyCareProtocol.receive(update(101, text), changedRoom, 303))
        assertNull(FamilyCareProtocol.receive(update(101, text).apply { getJSONObject("message").put("forward_origin", JSONObject()) }, f.room, 303))
        val childAsParent = FamilyCareProtocol.envelope(f.room, 404, 303, "command", command.json()).toString()
        assertNull(FamilyCareProtocol.receive(update(404, childAsParent), f.room, 303))
    }

    @Test fun `new child epoch never silently overwrites a pinned parent board`() {
        val f = Family(); f.drain()
        val prior = f.engine(101).currentSnapshot(303)!!
        val replacement = prior.copy(epoch = UUID.randomUUID().toString(), revision = 999,
            state = JSONObject(prior.state.toString()).put("stickerBalance", 999))
        for (chunk in FamilyCareSnapshots.chunks(replacement)) f.http.inject(303, 101,
            FamilyCareProtocol.envelope(f.room, 303, 303, "snapshot_chunk", FamilyCareSnapshots.chunkJson(chunk)).toString())
        f.sync(101)
        assertEquals(prior.epoch, f.engine(101).currentSnapshot(303)!!.epoch)
        assertEquals(5, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
        assertTrue(f.engine(101).status(303)!!.contains("새 가족방"))
    }

    @Test fun `large snapshot is chunked verified and excludes private chat and replay identities`() {
        val f = Family(bootstrap = false)
        val seed = TelegramLedger.emptyState()
        repeat(300) { seed.getJSONArray("rewards").put(JSONObject().put("id", UUID.randomUUID().toString())
            .put("name", UUID.randomUUID().toString()).put("cost", it + 1)) }
        seed.getJSONObject("appliedEventIds").put(UUID.randomUUID().toString(), "0".repeat(64))
        seed.getJSONArray("events").put(FamilyEvent(UUID.randomUUID().toString(), "chat", JSONObject().put("text", "private secret"),
            "child", "2026-09-22T12:00:00Z", "relayed").json())
        f.engine(303).ensureAuthority(seed)
        assertTrue(f.stores.getValue(303).pendingPackets(f.room.id).size > 2)
        assertTrue(f.stores.getValue(303).pendingPackets(f.room.id).all { it.text.length <= 4096 })
        f.drain(rounds = 180)
        val state = f.engine(101).currentSnapshot(303)!!.state
        assertEquals(300, state.getJSONArray("rewards").length())
        assertFalse(state.has("appliedEventIds"))
        assertFalse(state.toString().contains("private secret"))
    }

    @Test fun `initial snapshot chunks fill the pipeline without an ACK round trip for every chunk`() {
        val f = Family(bootstrap = false)
        val seed = TelegramLedger.emptyState()
        repeat(120) { seed.getJSONArray("rewards").put(JSONObject().put("id", UUID.randomUUID().toString())
            .put("name", UUID.randomUUID().toString()).put("cost", it + 1)) }
        f.engine(303).ensureAuthority(seed)
        val chunks = f.stores.getValue(303).pendingPackets(f.room.id).filter { it.peerId == 101L }
        assertTrue(chunks.size in 2..8)
        f.engine(303).flush()
        assertEquals(chunks.map { it.packetId }, f.http.sent.filter { it.first == 303L && it.second == 101L }
            .map { JSONObject(it.third).getString("id") })
        assertEquals(chunks.size, f.stores.getValue(303).pendingPackets(f.room.id).count { it.peerId == 101L })
        f.sync(101)
        assertEquals(120, f.engine(101).currentSnapshot(303)!!.snapshot.rewards.size)
        f.sync(303)
        assertTrue(f.stores.getValue(303).pendingPackets(f.room.id).none { it.peerId == 101L })
    }

    @Test fun `compressed snapshot expansion is bounded before parsing`() {
        val text = " ".repeat(FamilyCareSnapshots.MAX_BYTES + 1)
        val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(text.toByteArray()) } }.toByteArray()
        val parts = Base64.getEncoder().encodeToString(compressed).chunked(FamilyCareSnapshots.CHUNK_SIZE)
        val room = UUID.randomUUID().toString(); val transfer = UUID.randomUUID().toString(); val epoch = UUID.randomUUID().toString()
        val chunks = parts.mapIndexed { index, encoded -> FamilyCareChunk(room, transfer, 303, epoch, 0, index, parts.size, FamilyChatValidation.digest(text), encoded) }
        assertThrows(IllegalArgumentException::class.java) { FamilyCareSnapshots.assemble(chunks) }
    }

    @Test fun `corrupt gzip from one child cannot block the shared receive offset or another child`() {
        val f = Family(); f.drain()
        val current = f.engine(101).currentSnapshot(303)!!
        val chunk = FamilyCareChunk(f.room.id, UUID.randomUUID().toString(), 303, current.epoch, current.revision + 1,
            0, 1, "0".repeat(64), Base64.getEncoder().encodeToString("broken gzip".toByteArray()))
        f.http.inject(303, 101, FamilyCareProtocol.envelope(f.room, 303, 303, "snapshot_chunk", FamilyCareSnapshots.chunkJson(chunk)).toString())
        val location = location()
        f.engine(404).emitChildEvent(location)
        f.sync(404)
        val before = f.stores.getValue(101).meta("offset")
        f.sync(101)
        assertTrue(f.stores.getValue(101).meta("offset") > before)
        assertEquals(location.id, f.engine(101).currentSnapshot(404)!!.snapshot.events.last().id)
        assertNotNull(f.engine(101).status(303))
    }

    @Test fun `migration preserves replay IDs and reward tombstones while accepting undelivered legacy awards`() {
        val f = Family(bootstrap = false)
        val oldAward = FamilyEvent(UUID.randomUUID().toString(), "sticker_award", award(), "guardian", "2026-09-22T12:00:00Z", "relayed")
        val tombstone = UUID.randomUUID().toString()
        val seed = TelegramLedger.apply(TelegramLedger.emptyState(), oldAward).put("careRewardVersions", JSONObject().put(tombstone, 7L))
        f.engine(303).ensureAuthority(seed)
        val engine = f.engine(303)
        engine.processLegacy(oldAward, 101)
        val pending = oldAward.copy(id = UUID.randomUUID().toString())
        engine.processLegacy(pending, 101)
        engine.processLegacy(pending, 101)
        f.drain()
        assertEquals(2, engine.currentSnapshot(303)!!.snapshot.stickerBalance)
        assertEquals(7, engine.currentSnapshot(303)!!.rewardVersion(tombstone))
        assertTrue(TelegramLedger.contains(engine.currentSnapshot(303)!!.state, oldAward.id))
        assertEquals(2, f.engine(202).currentSnapshot(303)!!.snapshot.stickerBalance)
    }

    @Test fun `malformed command produces terminal rejection and does not block the next command`() {
        val f = Family(); f.drain()
        val id = UUID.randomUUID().toString()
        f.http.inject(101, 303, FamilyCareProtocol.envelope(f.room, 101, 303, "command", JSONObject().put("id", id), id).toString())
        f.sync(303)
        assertFalse(f.stores.getValue(303).outcome(f.room.id, id)!!.accepted)
        f.engine(202).enqueueCommand(303, "sticker_award", award())
        f.drain()
        assertEquals(6, f.engine(303).currentSnapshot(303)!!.snapshot.stickerBalance)
    }

    @Test fun `stale legacy balance cannot acknowledge an approval rejected by the family authority`() {
        val f = Family(); f.drain()
        val requestId = f.engine(303).enqueueCommand(303, "sticker_redeem_request", request(f.rewardId))
        f.drain()
        val store = f.stores.getValue(303)
        val authority = store.state(f.room.id, 303)!!
        store.cache(JSONObject(authority.state.toString()).put("stickerBalance", 10))
        store.saveState(authority.copy(revision = authority.revision + 1, state = JSONObject(authority.state.toString()).put("stickerBalance", 0)))
        val event = FamilyEvent(UUID.randomUUID().toString(), "sticker_redeem_approve", approval(requestId, true),
            "guardian", "2026-09-22T12:00:00Z", "pending")
        f.http.inject(101, 303, TelegramProtocol.event(event))
        assertThrows(TelegramSyncException::class.java) { f.syncLegacy(303, 101) }
        assertEquals(10, FamilySnapshot.parse(store.cached()).stickerBalance)
        assertEquals("pending", FamilySnapshot.parse(store.cached()).redemptions.single().status)
        assertEquals(0, store.state(f.room.id, 303)!!.snapshot.stickerBalance)
        assertTrue(f.http.sent.none { JSONObject(it.third).let { json -> json.optInt("v") == 2 && json.optString("type") == "ack" && json.optString("id") == event.id } })
    }

    @Test fun `legacy second decision cannot override a family terminal decision`() {
        val f = Family(); f.drain()
        val requestId = f.engine(303).enqueueCommand(303, "sticker_redeem_request", request(f.rewardId))
        f.drain()
        val original = f.engine(303).currentSnapshot(303)!!.state
        f.engine(101).enqueueCommand(303, "sticker_redeem_approve", approval(requestId, true))
        f.drain()
        val store = f.stores.getValue(303)
        store.cache(original)
        val oldDecision = FamilyEvent(UUID.randomUUID().toString(), "sticker_redeem_approve", approval(requestId, false),
            "guardian", "2026-09-22T12:00:00Z", "pending")
        f.http.inject(101, 303, TelegramProtocol.event(oldDecision))
        assertThrows(TelegramSyncException::class.java) { f.syncLegacy(303, 101) }
        assertEquals("approved", store.state(f.room.id, 303)!!.snapshot.redemptions.single().status)
        assertEquals("pending", FamilySnapshot.parse(store.cached()).redemptions.single().status)
    }

    private class Family(bootstrap: Boolean = true) {
        val ids = listOf(101L, 202L, 303L, 404L)
        val room = FamilyChatRoom.create("우리집", ids.mapIndexed { index, id -> FamilyChatMember(id, "@care${id}_bot",
            listOf("엄마", "아빠", "아들", "딸")[index], listOf("mother", "father", "son", "daughter")[index]) })
        val rewardId = UUID.randomUUID().toString()
        val stores = ids.associateWith { MemoryStore() }
        val http = FakeTelegram()
        var time = Instant.parse("2026-09-22T12:00:00Z").toEpochMilli()
        init {
            if (bootstrap) {
                val seed = TelegramLedger.emptyState().put("stickerBalance", 5)
                seed.getJSONArray("rewards").put(JSONObject().put("id", rewardId).put("name", "간식").put("cost", 3))
                engine(303).ensureAuthority(seed)
                engine(404).ensureAuthority()
            }
        }
        private fun client(id: Long) = TelegramClient("$id:${"x".repeat(32)}", http)
        fun engine(id: Long) = FamilyCareEngine(client(id), stores.getValue(id), room, id, now = { time })
        fun sync(id: Long) = TelegramExchange(client(id), stores.getValue(id), 0, "child", now = { time }, familyCare = engine(id)).synchronize()
        fun syncLegacy(id: Long, peerId: Long) = TelegramExchange(client(id), stores.getValue(id), peerId, "guardian", now = { time }, familyCare = engine(id)).synchronize()
        fun drain(active: List<Long> = ids, rounds: Int = 70) { repeat(rounds) { active.forEach { sync(it) }; time += 1001 } }
    }

    private class FakeTelegram : TelegramHttpTransport {
        val sent = mutableListOf<Triple<Long, Long, String>>()
        private val inbox = mutableMapOf<Long, MutableList<JSONObject>>()
        private var sequence = 0L
        var loseResponseFrom: Long? = null
        var loseResponsePacketId: String? = null
        var nextSendErrorCode: Int? = null
        var pauseBeforeType: Pair<Long, String>? = null
        var onSuccessfulSend: () -> Unit = {}
        val polls = mutableListOf<Long>()
        fun inject(from: Long, to: Long, text: String) {
            inbox.getOrPut(to) { mutableListOf() }.add(update(from, text).put("update_id", ++sequence))
        }
        override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse {
            val own = token.substringBefore(':').toLong(); val body = JSONObject(json)
            val result: Any = when (method) {
                "getUpdates" -> {
                    polls.add(own)
                    JSONArray(inbox[own].orEmpty().filter { it.getLong("update_id") >= body.getLong("offset") }
                        .takeWhile { pauseBeforeType != (own to JSONObject(it.getJSONObject("message").getString("text")).optString("type")) }.take(100))
                }
                "sendMessage" -> {
                    val peer = body.getLong("chat_id"); val text = body.getString("text")
                    sent.add(Triple(own, peer, text))
                    nextSendErrorCode?.let { code ->
                        nextSendErrorCode = null
                        return TelegramHttpResponse(code, JSONObject().put("ok", false).put("error_code", code)
                            .put("parameters", JSONObject().put("retry_after", 60)).toString())
                    }
                    inject(own, peer, text)
                    if (loseResponseFrom == own || loseResponsePacketId == JSONObject(text).optString("id")) {
                        loseResponseFrom = null; loseResponsePacketId = null; throw IOException("ambiguous send")
                    }
                    onSuccessfulSend()
                    JSONObject().put("chat", JSONObject().put("id", peer).put("type", "private"))
                }
                else -> error("Unexpected method $method")
            }
            return TelegramHttpResponse(200, JSONObject().put("ok", true).put("result", result).toString())
        }
    }

    private class MemoryStore : FamilyCareStore, TelegramExchangeStore {
        private var states = linkedMapOf<Pair<String, Long>, FamilyCareState>()
        private var outcomes = linkedMapOf<Pair<String, String>, FamilyCareOutcome>()
        private var commands = linkedMapOf<Pair<String, String>, FamilyCareCommand>()
        private var received = linkedMapOf<Pair<String, String>, String>()
        private var outgoing = mutableListOf<FamilyCareOutgoing>()
        private var receipts = mutableListOf<FamilyCareReceipt>()
        private var retry = linkedMapOf<Pair<String, Long>, Long>()
        private var chunks = mutableListOf<FamilyCareChunk>()
        private var errors = linkedMapOf<Pair<String, Long>, String>()
        var archived = mutableListOf<Pair<Long, FamilyEvent>>()
        private var meta = linkedMapOf<String, Long>()
        private var legacy = TelegramLedger.emptyState()
        var failCommit = false
        var failAcknowledge = false
        override fun <T> transaction(block: () -> T): T {
            val s = LinkedHashMap(states); val o = LinkedHashMap(outcomes); val c = LinkedHashMap(commands)
            val r = LinkedHashMap(received); val q = outgoing.toMutableList(); val a = receipts.toMutableList()
            val retryCopy = LinkedHashMap(retry); val parts = chunks.toMutableList(); val e = LinkedHashMap(errors)
            val movement = archived.toMutableList(); val m = LinkedHashMap(meta); val l = JSONObject(legacy.toString())
            try {
                val value = block()
                if (failCommit) { failCommit = false; throw IOException("commit failed") }
                return value
            } catch (error: Throwable) {
                states = s; outcomes = o; commands = c; received = r; outgoing = q; receipts = a
                retry = retryCopy; chunks = parts; errors = e; archived = movement; meta = m; legacy = l
                throw error
            }
        }
        override fun state(roomId: String, childId: Long) = states[roomId to childId]?.let { it.copy(state = JSONObject(it.state.toString())) }
        override fun saveState(state: FamilyCareState) { states[state.roomId to state.childId] = state.copy(state = JSONObject(state.state.toString())) }
        override fun outcome(roomId: String, commandId: String) = outcomes[roomId to commandId]
        override fun saveOutcome(outcome: FamilyCareOutcome) { val key = outcome.roomId to outcome.commandId; require(outcomes[key] == null || outcomes[key] == outcome); outcomes[key] = outcome }
        override fun outcomes(roomId: String, childId: Long) = outcomes.values.filter { it.roomId == roomId && it.childId == childId }
        override fun saveCommand(command: FamilyCareCommand) { val key = command.roomId to command.id; require(commands[key] == null || commands[key]!!.digest == command.digest); commands[key] = command }
        override fun commands(roomId: String, childId: Long) = commands.values.filter { it.roomId == roomId && it.childId == childId }
        override fun receivedDigest(roomId: String, packetId: String) = received[roomId to packetId]
        override fun recordReceived(roomId: String, packetId: String, digest: String) { received[roomId to packetId] = digest }
        override fun queuePacket(packet: FamilyCareOutgoing) {
            val prior = outgoing.firstOrNull { it.roomId == packet.roomId && it.packetId == packet.packetId && it.peerId == packet.peerId }
            require(prior == null || prior.digest == packet.digest)
            if (prior == null) outgoing.add(packet)
        }
        override fun pendingPackets(roomId: String) = outgoing.filter { it.roomId == roomId }
        override fun markSent(roomId: String, packetId: String, peerId: Long, sentAt: Long) {
            outgoing.replaceAll { if (it.roomId == roomId && it.packetId == packetId && it.peerId == peerId) it.copy(sentAt = sentAt, sendConfirmed = false) else it }
        }
        override fun markSendConfirmed(roomId: String, packetId: String, peerId: Long) {
            outgoing.replaceAll { if (it.roomId == roomId && it.packetId == packetId && it.peerId == peerId && it.sentAt > 0) it.copy(sendConfirmed = true) else it }
        }
        override fun acknowledge(roomId: String, packetId: String, peerId: Long, digest: String) {
            if (failAcknowledge) { failAcknowledge = false; throw IOException("acknowledgement storage failed") }
            outgoing.removeAll { it.roomId == roomId && it.packetId == packetId && it.peerId == peerId && it.digest == digest && it.sentAt > 0 }
        }
        override fun queueReceipt(receipt: FamilyCareReceipt) { if (receipt !in receipts) receipts.add(receipt) }
        override fun receipts(roomId: String) = receipts.filter { it.roomId == roomId }
        override fun removeReceipt(receipt: FamilyCareReceipt) { receipts.remove(receipt) }
        override fun retryAfter(roomId: String, peerId: Long) = retry[roomId to peerId] ?: 0
        override fun setRetryAfter(roomId: String, peerId: Long, until: Long) { retry[roomId to peerId] = until }
        override fun snapshotChunks(roomId: String, transferId: String) = chunks.filter { it.roomId == roomId && it.transferId == transferId }
        override fun putSnapshotChunk(chunk: FamilyCareChunk) {
            val prior = chunks.firstOrNull { it.roomId == chunk.roomId && it.transferId == chunk.transferId && it.index == chunk.index }
            require(prior == null || prior == chunk)
            if (prior == null) chunks.add(chunk)
        }
        override fun removeSnapshotChunks(roomId: String, transferId: String) { chunks.removeAll { it.roomId == roomId && it.transferId == transferId } }
        override fun setError(roomId: String, childId: Long, error: String?) { if (error == null) errors.remove(roomId to childId) else errors[roomId to childId] = error }
        override fun error(roomId: String, childId: Long) = errors[roomId to childId]
        override fun archiveEvent(roomId: String, childId: Long, event: FamilyEvent) { if (event.kind in setOf("location", "vertical") && archived.none { it.first == childId && it.second.id == event.id }) archived.add(childId to event) }
        override fun meta(name: String) = meta[name] ?: 0
        override fun setMeta(name: String, value: Long) { meta[name] = value }
        override fun cached() = JSONObject(legacy.toString())
        override fun cache(state: JSONObject) { legacy = JSONObject(state.toString()) }
        override fun pending() = emptyList<FamilyEvent>()
        override fun remove(id: String) = Unit
        override fun queueReceipt(id: String) = Unit
        override fun receipts() = emptyList<String>()
        override fun removeReceipt(id: String) = Unit
    }

    companion object {
        private fun award() = JSONObject().put("count", 1).put("reason", "잘했어")
        private fun approval(id: String, accepted: Boolean) = JSONObject().put("requestId", id).put("accepted", accepted)
        private fun reward(id: String, name: String, cost: Int) = JSONObject().put("rewardId", id).put("name", name).put("cost", cost)
        private fun request(id: String) = JSONObject().put("rewardId", id).put("reward", "간식").put("cost", 3)
        private fun location() = FamilyEvent(UUID.randomUUID().toString(), "location", JSONObject().put("latitude", 37.0)
            .put("longitude", 127.0).put("accuracy", 12.0).put("capturedAt", "2026-09-22T12:00:00Z").put("source", "automatic"),
            "child", "2026-09-22T12:00:00Z", "pending")
        private fun update(from: Long, text: String) = JSONObject().put("update_id", 1).put("message", JSONObject()
            .put("from", JSONObject().put("id", from).put("is_bot", true)).put("chat", JSONObject().put("id", from).put("type", "private")).put("text", text))
    }
}
