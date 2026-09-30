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

    @Test fun `negotiated telemetry batches preserve every revision and only reach both parents`() {
        val f = Family(); f.drain()
        f.batching = true
        for (parent in listOf(101L, 202L)) {
            f.http.inject(parent, 303, FamilyTransportProtocol.capability(42, false))
            f.http.inject(303, parent, FamilyTransportProtocol.capability(42, false))
        }
        f.drain(rounds = 10)
        f.http.sent.clear()
        val events = (1..10).map { location() }
        events.forEach { f.engine(303).emitChildEvent(it) }
        f.drain(rounds = 20)
        val batches = f.http.sent.filter { JSONObject(it.third).optString("type") == "batch" }
        assertEquals(4, batches.size) // one telemetry batch and one ACK batch per parent
        assertEquals(setOf(101L, 202L), batches.filter { it.first == 303L }.map { it.second }.toSet())
        for (parent in listOf(101L, 202L)) {
            assertEquals(f.engine(303).currentSnapshot(303)!!.revision, f.engine(parent).currentSnapshot(303)!!.revision)
            assertTrue(f.stores.getValue(parent).archived.map { it.second.id }.containsAll(events.map { it.id }))
        }
        assertTrue(f.stores.getValue(404).archived.none { it.first == 303L })
        assertTrue(f.stores.getValue(303).pendingPackets(f.room.id).isEmpty())
    }

    @Test fun `downgraded sender eventually receives individual ACKs after ignoring batch ACKs`() {
        val f = Family(); f.drain(); f.batching = true
        for (parent in listOf(101L, 202L)) {
            f.http.inject(parent, 303, FamilyTransportProtocol.capability(42, false))
            f.http.inject(303, parent, FamilyTransportProtocol.capability(42, false))
        }
        f.drain(rounds = 10)
        f.oldPeers += 303L
        f.http.sent.clear()
        repeat(10) { f.engine(303).emitChildEvent(location()) }
        f.drain(rounds = 150)
        assertTrue(f.http.sent.any { it.first != 303L && JSONObject(it.third).optString("type") == "batch" })
        assertTrue(f.http.sent.any { it.first != 303L && JSONObject(it.third).optString("type") == "care_ack" })
        assertTrue(f.stores.getValue(303).pendingPackets(f.room.id).isEmpty())
        for (parent in listOf(101L, 202L)) assertEquals(f.engine(303).currentSnapshot(303)!!.revision,
            f.engine(parent).currentSnapshot(303)!!.revision)
    }

    @Test fun `malformed authenticated conditional sync is rejected without throwing outside receive loop`() {
        val f = Family(); f.drain(); f.deltas = true
        for (field in listOf("epoch", "digest")) {
            val current = f.engine(303).currentSnapshot(303)!!
            val body = JSONObject().put("epoch", current.epoch).put("revision", current.revision)
                .put("digest", FamilyCareDelta.projectionDigest(current)).put(field, 123)
            val packet = FamilyCareProtocol.envelope(f.room, 101, 303, "care_sync", body)
            f.stores.getValue(303).transaction { f.engine(303).processUpdate(update(101, packet.toString())) }
            assertEquals(5, f.engine(303).currentSnapshot(303)!!.snapshot.stickerBalance)
            assertFalse(f.stores.getValue(303).receipts(f.room.id).any { it.packetId == packet.getString("id") })
        }
    }

    @Test fun `unchanged delta capable sync sends no whole board`() {
        val f = Family(); f.drain(); f.deltas = true; f.http.sent.clear()
        f.engine(101).requestSync(303); f.drain()
        assertTrue(f.http.sent.any { JSONObject(it.third).optString("type") == "care_sync" })
        assertFalse(f.http.sent.any { JSONObject(it.third).optString("type") in setOf("care_delta", "snapshot_chunk") })
        assertTrue(f.stores.getValue(101).pendingPackets(f.room.id).isEmpty())
    }

    @Test fun `new parents receive only committed financial changes and identical reward versions`() {
        val f = Family(); f.drain(); f.deltas = true; f.http.sent.clear()
        f.engine(101).enqueueCommand(303, "reward_upsert", reward(f.rewardId, "영화", 4)); f.drain()
        assertEquals(2, f.http.sent.count { JSONObject(it.third).optString("type") == "care_delta" })
        assertFalse(f.http.sent.any { JSONObject(it.third).optString("type") == "snapshot_chunk" })
        for (id in listOf(101L, 202L)) {
            assertEquals("영화", f.engine(id).currentSnapshot(303)!!.snapshot.rewards.single().name)
            assertEquals(f.engine(303).currentSnapshot(303)!!.rewardVersion(f.rewardId), f.engine(id).currentSnapshot(303)!!.rewardVersion(f.rewardId))
            assertEquals(FamilyCareDelta.projectionDigest(f.engine(303).currentSnapshot(303)!!), FamilyCareDelta.projectionDigest(f.engine(id).currentSnapshot(303)!!))
        }
    }

    @Test fun `missing mixed telemetry and financial revisions repair only missing history`() {
        val f = Family(); f.drain(); f.deltas = true
        val position = location(); f.engine(303).emitChildEvent(position)
        f.engine(202).enqueueCommand(303, "sticker_award", award()); f.sync(202); f.sync(303)
        f.stores.getValue(303).discardOutgoing(101)
        // Remove HTTP records already delivered to this paused parent, leaving its projection at revision zero.
        f.http.discardInbox(101)
        f.http.sent.clear(); f.engine(101).requestSync(303); f.drain()
        val repaired = f.http.sent.filter { it.first == 303L && it.second == 101L }.map { JSONObject(it.third).optString("type") }
        assertEquals(2, repaired.count { it == "care_delta" })
        assertFalse(repaired.contains("snapshot_chunk"))
        assertEquals(6, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
        assertTrue(f.stores.getValue(101).archived.any { it.second.id == position.id })
        assertEquals(f.engine(303).currentSnapshot(303)!!.revision, f.engine(101).currentSnapshot(303)!!.revision)
    }

    @Test fun `history gap falls back to a whole snapshot without losing the committed balance`() {
        val f = Family(); f.drain(); f.deltas = true
        repeat(3) { f.engine(202).enqueueCommand(303, "sticker_award", award()); f.sync(202); f.sync(303) }
        f.stores.getValue(303).discardOutgoing(101); f.http.discardInbox(101)
        f.stores.getValue(303).discardDelta(1)
        f.http.sent.clear(); f.engine(101).requestSync(303); f.drain()
        assertTrue(f.http.sent.any { it.second == 101L && JSONObject(it.third).optString("type") == "snapshot_chunk" })
        assertEquals(8, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
        assertEquals(3L, f.engine(101).currentSnapshot(303)!!.revision)
    }

    @Test fun `conflicting delta result never applies money and requests a whole checkpoint`() {
        val f = Family(); f.drain(); f.deltas = true
        f.engine(202).enqueueCommand(303, "sticker_award", award()); f.sync(202); f.sync(303)
        val original = f.stores.getValue(303).pendingPackets(f.room.id).first { it.peerId == 101L && JSONObject(it.text).optString("type") == "care_delta" }
        val changed = JSONObject(original.text).put("id", UUID.randomUUID().toString())
        changed.getJSONObject("body").put("resultDigest", "0".repeat(64))
        f.stores.getValue(101).transaction { f.engine(101).processUpdate(update(303, changed.toString())) }
        assertEquals(5, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
        assertEquals(0L, f.engine(101).currentSnapshot(303)!!.revision)
        assertTrue(f.stores.getValue(101).pendingPackets(f.room.id).any { JSONObject(it.text).optString("type") == "sync_request" })
        f.drain(); assertEquals(6, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
    }

    @Test fun `delta transaction failure rolls back financial state identities and receive cursor`() {
        val f = Family(); f.drain(); f.deltas = true
        f.engine(202).enqueueCommand(303, "sticker_award", award()); f.sync(202); f.sync(303)
        val parent = f.stores.getValue(101); val cursor = parent.meta("offset")
        parent.failCommit = true
        assertThrows(IOException::class.java) { f.sync(101) }
        assertEquals(cursor, parent.meta("offset")); assertEquals(5, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
        f.drain(); assertEquals(6, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
    }

    @Test fun `old parent continues using ordinary snapshots when other parent supports deltas`() {
        val f = Family(); f.drain(); f.deltas = true; f.oldCarePeers += 101L; f.http.sent.clear()
        f.engine(202).enqueueCommand(303, "sticker_award", award()); f.drain()
        assertTrue(f.http.sent.any { it.second == 202L && JSONObject(it.third).optString("type") == "care_delta" })
        assertTrue(f.http.sent.any { it.second == 101L && JSONObject(it.third).optString("type") == "snapshot_chunk" })
        assertFalse(f.http.sent.any { it.second == 101L && JSONObject(it.third).optString("type") == "care_delta" })
        assertEquals(6, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
    }

    @Test fun `pending financial delta downgrades to an acknowledged whole checkpoint without losing originals`() {
        val f = Family(); f.drain(); f.deltas = true
        f.engine(202).enqueueCommand(303, "sticker_award", award()); f.sync(202); f.sync(303)
        val original = f.stores.getValue(303).pendingPackets(f.room.id).first { it.peerId == 101L && JSONObject(it.text).optString("type") == "care_delta" }
        f.http.discardInbox(101); f.oldCarePeers += 101L; f.batching = true; f.http.sent.clear()
        f.sync(303)
        assertNotNull(f.stores.getValue(303).pendingPacket(f.room.id, original.packetId, 101))
        assertTrue(f.stores.getValue(303).hasReplacement(f.room.id, original.packetId, 101))
        f.drain(rounds = 150)
        assertEquals(6, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
        assertNull(f.stores.getValue(303).pendingPacket(f.room.id, original.packetId, 101))
        assertTrue(f.http.sent.any { it.second == 101L && JSONObject(it.third).optString("type") == "snapshot_chunk" })
    }

    @Test fun `latest location preview bypasses financial revision and never completes historic delivery`() {
        val f = Family(); f.drain()
        val known = f.engine(101).currentSnapshot(303)!!
        val event = location()
        val packet = FamilyCareProtocol.envelope(f.room, 303, 303, "care_location_head",
            FamilyCareLocationHead(f.room.id, 303, known.epoch, 100, event.copy(delivery = "relayed")).json())
        val parent = f.stores.getValue(101); val cursor = parent.meta("offset")
        assertTrue(parent.transaction { f.engine(101).processLatestPreview(update(303, packet.toString())) })
        assertEquals(cursor, parent.meta("offset"))
        assertEquals(0L, f.engine(101).currentSnapshot(303)!!.revision)
        assertEquals(5, f.engine(101).currentSnapshot(303)!!.snapshot.stickerBalance)
        assertNull(parent.eventDigest(f.room.id, 303, event.id))
        assertTrue(parent.receipts(f.room.id).isEmpty())
        assertEquals(event.id, f.engine(101).latestLocation(303)!!.id)
        // The original revision still applies the location; the preview must not mark it already applied.
        val historical = FamilyCareProtocol.envelope(f.room, 303, 303, "child_event", JSONObject()
            .put("epoch", known.epoch).put("revision", 1).put("event", event.copy(delivery = "relayed").json()))
        parent.transaction { f.engine(101).processUpdate(update(303, historical.toString())) }
        assertEquals(1L, f.engine(101).currentSnapshot(303)!!.revision)
        assertEquals(event.id, f.engine(101).currentSnapshot(303)!!.snapshot.events.last().id)
    }

    @Test fun `latest summary slots coalesce while every original position stays durable and history keeps send turns`() {
        val f = Family(); f.drain(); f.latest = true
        repeat(10) { f.engine(303).emitChildEvent(location()) }
        val child = f.stores.getValue(303)
        assertEquals(20, child.pendingPackets(f.room.id).size)
        assertEquals(2, child.pendingLatestLocationHeads(f.room.id).size)
        assertTrue(child.pendingLatestLocationHeads(f.room.id).all { JSONObject(it.text).getJSONObject("body").getLong("revision") == 10L })
        // Model already negotiated peers so independent capability controls do not consume this fairness turn.
        for (peer in listOf(101L, 202L)) for (feature in listOf("supportedUntil", "careSupportedUntil", "fileSupportedUntil", "latestSupportedUntil"))
            child.setMeta("transport:${f.room.id}:$peer:$feature", f.time + 21_600_000)
        val transport = f.transport(303); val engine = f.engine(303, transport)
        f.http.sent.clear(); engine.flushLatestLocations()
        assertEquals("care_location_head", JSONObject(f.http.sent.first().third).getString("type"))
        f.time += 1101; engine.flushLatestLocations()
        val headsBefore = f.http.sent.count { JSONObject(it.third).optString("type") == "care_location_head" }
        f.time += 1101; f.engine(303).emitChildEvent(location())
        engine.flushLatestLocations(); engine.flush()
        assertEquals(headsBefore, f.http.sent.count { JSONObject(it.third).optString("type") == "care_location_head" })
        assertTrue(f.http.sent.any { JSONObject(it.third).optString("type") in setOf("child_event", "batch", "file_batch") })
        assertEquals(22, child.pendingPackets(f.room.id).size)
    }

    @Test fun `latest head unsent and superseded acknowledgements cannot remove the current summary or history`() {
        val f = Family(); f.drain(); f.latest = true
        f.engine(303).emitChildEvent(location())
        val child = f.stores.getValue(303)
        val old = child.pendingLatestLocationHeads(f.room.id).first { it.peerId == 101L }
        fun ack(packet: FamilyCareOutgoing) = update(101, FamilyCareProtocol.envelope(f.room, 101, 303, "care_ack",
            JSONObject().put("digest", packet.digest), packet.packetId).toString())
        child.transaction { f.engine(303).processUpdate(ack(old)) }
        assertNotNull(child.pendingLatestLocationHead(f.room.id, old.packetId, 101))
        child.markLatestLocationSent(f.room.id, old.packetId, 101, f.time)
        f.engine(303).emitChildEvent(location())
        val current = child.pendingLatestLocationHeads(f.room.id).first { it.peerId == 101L }
        child.transaction { f.engine(303).processUpdate(ack(old)); f.engine(303).processUpdate(ack(current)) }
        assertNotNull(child.pendingLatestLocationHead(f.room.id, current.packetId, 101))
        child.markLatestLocationSent(f.room.id, current.packetId, 101, f.time)
        child.transaction { f.engine(303).processUpdate(ack(current)) }
        assertNull(child.pendingLatestLocationHead(f.room.id, current.packetId, 101))
        assertEquals(4, child.pendingPackets(f.room.id).size)
    }

    @Test fun `new latest capability sends a previously measured point before backlog and acknowledged seed stays quiet`() {
        val f = Family(); f.drain()
        val event = location(); f.engine(303).emitChildEvent(event)
        val child = f.stores.getValue(303)
        assertTrue(child.pendingLatestLocationHeads(f.room.id).isEmpty())
        assertEquals(2, child.pendingPackets(f.room.id).size)
        f.latest = true
        for (peer in listOf(101L, 202L)) for (feature in listOf("supportedUntil", "careSupportedUntil", "fileSupportedUntil", "latestSupportedUntil"))
            child.setMeta("transport:${f.room.id}:$peer:$feature", f.time + 21_600_000)
        val transport = f.transport(303); val engine = f.engine(303, transport)
        f.http.sent.clear(); engine.flushLatestLocations(); engine.flush()
        assertEquals("care_location_head", JSONObject(f.http.sent.first().third).getString("type"))
        assertEquals(event.id, FamilyCareLocationHead.parse(f.room.id, 303,
            JSONObject(f.http.sent.first().third).getJSONObject("body")).event.id)
        f.time += 1101; engine.flushLatestLocations()
        for (packet in child.pendingLatestLocationHeads(f.room.id)) {
            assertTrue(packet.sentAt > 0)
            child.transaction { engine.processUpdate(update(packet.peerId, FamilyCareProtocol.envelope(f.room, packet.peerId, 303,
                "care_ack", JSONObject().put("digest", packet.digest), packet.packetId).toString())) }
        }
        assertTrue(child.pendingLatestLocationHeads(f.room.id).isEmpty())
        assertNotNull(child.acknowledgedLatestLocationHead(f.room.id, 303, 101))
        f.engine(303).emitChildEvent(FamilyEvent(UUID.randomUUID().toString(), "heartbeat",
            JSONObject().put("recordedAt", "2026-09-22T12:01:00Z"), "child", "2026-09-22T12:01:00Z", "pending"))
        f.time += 31_000; f.http.sent.clear()
        f.engine(303, f.transport(303)).flushLatestLocations()
        assertTrue(child.pendingLatestLocationHeads(f.room.id).isEmpty())
        assertTrue(f.http.sent.isEmpty())
        assertEquals(4, child.pendingPackets(f.room.id).size)
    }

    @Test fun `upgrade state seeds its stored latest point without requiring a new GPS fix`() {
        val f = Family(bootstrap = false); val event = location().copy(delivery = "relayed")
        f.engine(303).ensureAuthority(TelegramLedger.apply(TelegramLedger.emptyState(), event))
        val child = f.stores.getValue(303)
        assertNull(child.latestLocationHead(f.room.id, 303))
        f.latest = true
        for (peer in listOf(101L, 202L)) for (feature in listOf("supportedUntil", "careSupportedUntil", "fileSupportedUntil", "latestSupportedUntil"))
            child.setMeta("transport:${f.room.id}:$peer:$feature", f.time + 21_600_000)
        f.engine(303, f.transport(303)).flushLatestLocations()
        assertEquals("care_location_head", JSONObject(f.http.sent.first().third).getString("type"))
        assertEquals(event.id, child.latestLocationHead(f.room.id, 303)!!.event.id)
        assertEquals(0L, child.stateMetadata(f.room.id, 303)!!.revision)
        assertEquals(2, child.pendingLatestLocationHeads(f.room.id).size)
    }

    @Test fun `stale different epoch sibling and conflicting equal revision heads do not replace a trusted location`() {
        val f = Family(); f.drain()
        val known = f.engine(101).currentSnapshot(303)!!
        val original = location().copy(delivery = "relayed")
        fun preview(event: FamilyEvent, revision: Long = 10, epoch: String = known.epoch, sender: Long = 303) =
            f.stores.getValue(101).transaction { f.engine(101).processLatestPreview(update(sender,
                FamilyCareProtocol.envelope(f.room, sender, 303, "care_location_head", FamilyCareLocationHead(f.room.id, 303, epoch, revision, event).json()).toString())) }
        assertTrue(preview(original))
        assertFalse(preview(location().copy(delivery = "relayed"), revision = 9))
        assertFalse(preview(original.copy(payload = JSONObject(original.payload.toString()).put("latitude", 38.0))))
        assertFalse(preview(location().copy(delivery = "relayed"), epoch = UUID.randomUUID().toString()))
        assertFalse(preview(location().copy(delivery = "relayed"), sender = 404))
        assertEquals(original.id, f.engine(101).latestLocation(303)!!.id)
        assertEquals(37.0, f.engine(101).latestLocation(303)!!.payload.getDouble("latitude"), 0.0)
        assertEquals(0L, f.engine(101).currentSnapshot(303)!!.revision)
    }

    @Test fun `large historical care backlog uses files beyond sixteen originals and preserves every point and revision`() {
        // Capability probes are demand driven; negotiate while initial authority snapshots are sent.
        val f = Family(); f.batching = true; f.files = true; f.drain()
        assertTrue(f.transport(303).supportsFiles(101))
        assertTrue(f.transport(303).supportsFiles(202))
        val events = (0 until 100).map { location() }
        events.forEach { f.engine(303).emitChildEvent(it) }
        f.http.sent.clear(); f.drain(rounds = 150)
        assertTrue(f.http.filePacketCounts.any { it > 16 })
        assertTrue(f.http.filePacketCounts.all { it <= FamilyTransportProtocol.MAX_FILE_PACKETS })
        for (parent in listOf(101L, 202L)) {
            assertEquals(100L, f.engine(parent).currentSnapshot(303)!!.revision)
            assertTrue(f.stores.getValue(parent).archived.map { it.second.id }.containsAll(events.map { it.id }))
        }
        assertTrue(f.stores.getValue(303).pendingPackets(f.room.id).isEmpty())
    }

    private class Family(bootstrap: Boolean = true) {
        val ids = listOf(101L, 202L, 303L, 404L)
        val room = FamilyChatRoom.create("우리집", ids.mapIndexed { index, id -> FamilyChatMember(id, "@care${id}_bot",
            listOf("엄마", "아빠", "아들", "딸")[index], listOf("mother", "father", "son", "daughter")[index]) })
        val rewardId = UUID.randomUUID().toString()
        val stores = ids.associateWith { MemoryStore() }
        val http = FakeTelegram()
        var time = Instant.parse("2026-09-22T12:00:00Z").toEpochMilli()
        var batching = false
        var files = false
        var deltas = false
        var latest = false
        val oldPeers = mutableSetOf<Long>()
        val oldCarePeers = mutableSetOf<Long>()
        init {
            if (bootstrap) {
                val seed = TelegramLedger.emptyState().put("stickerBalance", 5)
                seed.getJSONArray("rewards").put(JSONObject().put("id", rewardId).put("name", "간식").put("cost", 3))
                engine(303).ensureAuthority(seed)
                engine(404).ensureAuthority()
            }
        }
        private fun client(id: Long) = TelegramClient("$id:${"x".repeat(32)}", http)
        fun engine(id: Long, transport: FamilyTransport? = null) = FamilyCareEngine(client(id), stores.getValue(id), room, id, now = { time }, transport = transport,
            canSyncDeltas = { deltas && it !in oldCarePeers }, canSendLatestLocation = { latest && it !in oldCarePeers })
        fun transport(id: Long) = FamilyTransport(client(id), stores.getValue(id), room, id, now = { time })
        fun sync(id: Long): Boolean {
            http.acceptFiles = files
            val transport = if (batching && id !in oldPeers) FamilyTransport(client(id), stores.getValue(id), room, id, now = { time }) else null
            return TelegramExchange(client(id), stores.getValue(id), 0, "child", now = { time }, familyCare = engine(id, transport), transport = transport).synchronize()
        }
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
        var acceptFiles = false
        val filePacketCounts = mutableListOf<Int>()
        private val uploaded = mutableMapOf<String, ByteArray>()
        fun inject(from: Long, to: Long, text: String) {
            if (!acceptFiles && JSONObject(text).optString("type") == "file_capabilities") return
            inbox.getOrPut(to) { mutableListOf() }.add(update(from, text).put("update_id", ++sequence))
        }
        fun discardInbox(id: Long) { inbox.remove(id) }
        override fun uploadDocument(token: String, fields: String, filename: String, bytes: ByteArray): TelegramHttpResponse {
            val own = token.substringBefore(':').toLong(); val body = JSONObject(fields); val peer = body.getLong("chat_id")
            val caption = body.getString("caption"); val fileId = UUID.randomUUID().toString()
            uploaded[fileId] = bytes.copyOf()
            filePacketCounts += java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).use { input ->
                JSONArray(String(input.readBytes(), Charsets.UTF_8)).length()
            }
            sent.add(Triple(own, peer, caption))
            val document = JSONObject().put("file_id", fileId).put("file_unique_id", fileId).put("file_size", bytes.size)
                .put("file_name", filename).put("mime_type", "application/gzip")
            val incoming = update(own, caption).put("update_id", ++sequence)
            incoming.getJSONObject("message").remove("text")
            incoming.getJSONObject("message").put("caption", caption).put("document", document)
            inbox.getOrPut(peer) { mutableListOf() }.add(incoming)
            return TelegramHttpResponse(200, JSONObject().put("ok", true).put("result", JSONObject().put("chat",
                JSONObject().put("id", peer).put("type", "private"))).toString())
        }
        override fun downloadFile(token: String, filePath: String, maxBytes: Int, checkActive: () -> Unit): ByteArray {
            checkActive(); val bytes = uploaded.getValue(filePath.substringAfterLast('/').removeSuffix(".gz"))
            require(bytes.size <= maxBytes); return bytes.copyOf()
        }
        override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse {
            val own = token.substringBefore(':').toLong(); val body = JSONObject(json)
            val result: Any = when (method) {
                "getUpdates" -> {
                    polls.add(own)
                    JSONArray(inbox[own].orEmpty().filter { it.getLong("update_id") >= body.getLong("offset") }
                        .takeWhile { pauseBeforeType != (own to JSONObject(it.getJSONObject("message").let { message ->
                            message.optString("text").ifBlank { message.getString("caption") } }).optString("type")) }.take(100))
                }
                "getFile" -> body.getString("file_id").let { fileId -> JSONObject().put("file_id", fileId)
                    .put("file_size", uploaded.getValue(fileId).size).put("file_path", "documents/$fileId.gz") }
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

    internal class MemoryStore : FamilyCareStore, TelegramExchangeStore {
        private var states = linkedMapOf<Pair<String, Long>, FamilyCareState>()
        private var outcomes = linkedMapOf<Pair<String, String>, FamilyCareOutcome>()
        private var commands = linkedMapOf<Pair<String, String>, FamilyCareCommand>()
        private var received = linkedMapOf<Pair<String, String>, String>()
        private var outgoing = mutableListOf<FamilyCareOutgoing>()
        private var receipts = mutableListOf<FamilyCareReceipt>()
        private var retry = linkedMapOf<Pair<String, Long>, Long>()
        private var chunks = mutableListOf<FamilyCareChunk>()
        private var errors = linkedMapOf<Pair<String, Long>, String>()
        private var deltaHistory = mutableListOf<FamilyCareDelta>()
        private var replacements = linkedMapOf<Triple<String, String, Long>, List<String>>()
        private var latestHeads = linkedMapOf<Pair<String, Long>, FamilyCareLocationHead>()
        private var liveOutgoing = linkedMapOf<Triple<String, Long, Long>, FamilyCareOutgoing>()
        private var completedLive = linkedSetOf<Triple<String, Long, Long>>()
        private var latestSent = linkedMapOf<Pair<String, Long>, Long>()
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
            val history = deltaHistory.toMutableList()
            val mappings = LinkedHashMap(replacements)
            val heads = LinkedHashMap(latestHeads); val slots = LinkedHashMap(liveOutgoing); val headTimes = LinkedHashMap(latestSent)
            val completedHeads = LinkedHashSet(completedLive)
            try {
                val value = block()
                if (failCommit) { failCommit = false; throw IOException("commit failed") }
                return value
            } catch (error: Throwable) {
                states = s; outcomes = o; commands = c; received = r; outgoing = q; receipts = a
                retry = retryCopy; chunks = parts; errors = e; archived = movement; meta = m; legacy = l
                deltaHistory = history
                replacements = mappings
                latestHeads = heads; liveOutgoing = slots; latestSent = headTimes
                completedLive = completedHeads
                throw error
            }
        }
        override fun state(roomId: String, childId: Long) = states[roomId to childId]?.let { it.copy(state = JSONObject(it.state.toString())) }
        override fun saveState(state: FamilyCareState) {
            states[state.roomId to state.childId] = state.copy(state = JSONObject(state.state.toString()))
            if (latestHeads[state.roomId to state.childId]?.epoch?.let { it != state.epoch } == true) latestHeads.remove(state.roomId to state.childId)
        }
        override fun latestLocationHead(roomId: String, childId: Long) = latestHeads[roomId to childId]
            ?.takeIf { states[roomId to childId]?.epoch?.let { epoch -> epoch == it.epoch } != false }
            ?.let { FamilyCareLocationHead.parse(roomId, childId, JSONObject(it.json().toString())) }
        override fun saveLatestLocationHead(head: FamilyCareLocationHead): Boolean {
            val known = states[head.roomId to head.childId]
            if (known != null && known.epoch != head.epoch) return false
            val old = latestHeads[head.roomId to head.childId]
            if (old != null && old.epoch != head.epoch && known == null) return false
            if (old != null && old.epoch == head.epoch) {
                if (head.revision < old.revision) return false
                if (head.revision == old.revision) { require(head.eventDigest == old.eventDigest); return false }
                if (Instant.parse(head.event.measuredAt) < Instant.parse(old.event.measuredAt)) return false
            }
            latestHeads[head.roomId to head.childId] = FamilyCareLocationHead.parse(head.roomId, head.childId, JSONObject(head.json().toString()))
            return true
        }
        override fun queueLatestLocationHead(packet: FamilyCareOutgoing) {
            val key = Triple(packet.roomId, packet.childId, packet.peerId)
            val head = FamilyCareLocationHead.parse(packet.roomId, packet.childId, JSONObject(packet.text).getJSONObject("body"))
            liveOutgoing[key]?.let { prior ->
                val old = FamilyCareLocationHead.parse(prior.roomId, prior.childId, JSONObject(prior.text).getJSONObject("body"))
                require(head.epoch == old.epoch)
                if (head.revision < old.revision) return
                require(head.revision != old.revision || head.eventDigest == old.eventDigest)
                if (head.eventDigest == old.eventDigest) return
            }
            liveOutgoing[key] = packet; completedLive.remove(key)
        }
        override fun pendingLatestLocationHeads(roomId: String) = liveOutgoing.filterKeys { it !in completedLive }.values.filter { it.roomId == roomId }
        override fun markLatestLocationSent(roomId: String, packetId: String, peerId: Long, sentAt: Long) {
            liveOutgoing.replaceAll { key, packet -> if (key !in completedLive && packet.roomId == roomId && packet.packetId == packetId && packet.peerId == peerId) {
                latestSent[roomId to peerId] = sentAt; packet.copy(sentAt = sentAt, sendConfirmed = false)
            } else packet }
        }
        override fun markLatestLocationConfirmed(roomId: String, packetId: String, peerId: Long) {
            liveOutgoing.replaceAll { key, packet -> if (key !in completedLive && packet.roomId == roomId && packet.packetId == packetId && packet.peerId == peerId && packet.sentAt > 0) packet.copy(sendConfirmed = true) else packet }
        }
        override fun acknowledgeLatestLocationHead(roomId: String, packetId: String, peerId: Long, digest: String) {
            liveOutgoing.filter { (_, packet) -> packet.roomId == roomId && packet.packetId == packetId && packet.peerId == peerId && packet.digest == digest && packet.sentAt > 0 }
                .keys.forEach { completedLive.add(it) }
        }
        override fun acknowledgedLatestLocationHead(roomId: String, childId: Long, peerId: Long): FamilyCareLocationHead? {
            val key = Triple(roomId, childId, peerId)
            return liveOutgoing[key]?.takeIf { key in completedLive }?.let {
                FamilyCareLocationHead.parse(roomId, childId, JSONObject(it.text).getJSONObject("body"))
            }
        }
        override fun latestLocationSentAt(roomId: String, peerId: Long) = latestSent[roomId to peerId] ?: 0L
        override fun movementEventDigest(roomId: String, childId: Long, eventId: String) = archived.firstOrNull { it.first == childId && it.second.id == eventId }?.second?.let(TelegramLedger::eventDigest)
        override fun saveDelta(delta: FamilyCareDelta) { deltaHistory.add(delta); deltaHistory.removeAll { it.roomId == delta.roomId && it.childId == delta.childId && (it.epoch != delta.epoch || it.revision <= delta.revision - FamilyCareDelta.HISTORY_LIMIT) } }
        override fun deltas(roomId: String, childId: Long, epoch: String, afterRevision: Long, limit: Int) =
            deltaHistory.filter { it.roomId == roomId && it.childId == childId && it.epoch == epoch && it.revision > afterRevision }.sortedBy { it.revision }.take(limit)
        fun discardDelta(revision: Long) { deltaHistory.removeAll { it.revision == revision } }
        fun discardOutgoing(peer: Long) { outgoing.removeAll { it.peerId == peer } }
        override fun replacePacket(original: FamilyCareOutgoing, replacements: List<FamilyCareOutgoing>) {
            this.replacements[Triple(original.roomId, original.packetId, original.peerId)] = replacements.map { it.packetId }
        }
        override fun hasReplacement(roomId: String, packetId: String, peerId: Long) = replacements.containsKey(Triple(roomId, packetId, peerId))
        override fun pendingPackets(roomId: String, peerId: Long, limit: Int): List<FamilyCareOutgoing> {
            val targets = replacements.filterKeys { it.first == roomId && it.third == peerId }.values.flatten().toSet()
            return outgoing.filter { it.roomId == roomId && it.peerId == peerId && !hasReplacement(roomId, it.packetId, peerId) }
                .sortedBy { if (it.packetId in targets) 0 else 1 }.take(limit)
        }
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
            val completed = replacements.filter { (key, ids) -> key.first == roomId && key.third == peerId &&
                ids.none { replacementId -> outgoing.any { it.roomId == roomId && it.peerId == peerId && it.packetId == replacementId } } }.keys
            completed.forEach { key -> outgoing.removeAll { it.roomId == roomId && it.peerId == peerId && it.packetId == key.second }; replacements.remove(key) }
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
