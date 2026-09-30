package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FamilyCareHistorySource(val streamId: String, val anchor: Long, val count: Long)
data class FamilyCareHistoryEntry(val sequence: Long, val event: FamilyEvent)
data class FamilyCareHistoryProgress(val roomId: String, val childId: Long, val transferId: String,
    val streamId: String?, val cursor: Long, val anchor: Long, val count: Long, val complete: Boolean, val checkedAt: Long)

/** Independent archival repair. Its cursors and receipts never stand in for the ordered care ledger. */
internal class FamilyCareHistorySync(private val room: FamilyChatRoom, private val own: Long,
    private val store: FamilyCareStore, private val supported: (Long) -> Boolean, private val now: () -> Long,
    private val onProof: (Long, String) -> Unit = { _, _ -> }) {
    fun request(child: Long, force: Boolean = false): Boolean {
        require(FamilyCareValidation.isParent(room, own) && FamilyCareValidation.isChild(room, child))
        if (!supported(child)) return false
        val old = store.historyProgress(room.id, child)
        if (old != null && !old.complete) return false
        if (old != null && !force && now() >= old.checkedAt && now() - old.checkedAt < CHECK_INTERVAL) return false
        val next = FamilyCareHistoryProgress(room.id, child, UUID.randomUUID().toString(), old?.streamId,
            if (force) 0 else old?.cursor ?: 0, 0, 0, false, now())
        store.saveHistoryProgress(next)
        queueRequest(next)
        return true
    }

    fun maintain() {
        if (!FamilyCareValidation.isParent(room, own)) return
        room.members.filter { FamilyCareValidation.isChild(room, it.botId) }.forEach { request(it.botId) }
    }

    fun receive(packet: FamilyCareProtocol.Packet): Boolean = when (packet.type) {
        "history_request" -> receiveRequest(packet)
        "history_event" -> receiveEvent(packet)
        "history_checkpoint" -> receiveCheckpoint(packet)
        else -> false
    }

    private fun queueRequest(progress: FamilyCareHistoryProgress) {
        store.queuePacket(FamilyCareProtocol.outgoing(room, own, progress.childId, progress.childId, "history_request",
            JSONObject().put("transferId", progress.transferId).put("streamId", progress.streamId ?: JSONObject.NULL)
                .put("cursor", progress.cursor).put("anchor", progress.anchor)))
    }

    private fun receiveRequest(packet: FamilyCareProtocol.Packet): Boolean {
        if (!supported(packet.actorId)) return false
        val body = packet.body
        FamilyChatValidation.keys(body, setOf("transferId", "streamId", "cursor", "anchor"))
        val transfer = identifier(body, "transferId")
        val requestedStream = body.opt("streamId").let { if (it == JSONObject.NULL) null else { require(it is String); FamilyChatValidation.identifier(it) } }
        val requestedCursor = counter(body, "cursor"); val requestedAnchor = counter(body, "anchor")
        val pending = store.pendingHistoryPackets(room.id, packet.actorId, PAGE_SIZE + 1)
        val checkpoint = pending.firstOrNull { JSONObject(it.text).optString("type") == "history_checkpoint" }
        if (checkpoint != null) {
            val prior = JSONObject(checkpoint.text).getJSONObject("body")
            if (prior.getString("transferId") == transfer && prior.getLong("previous") == requestedCursor) return true
            val provesPrior = checkpoint.sentAt > 0 && prior.getString("transferId") == transfer &&
                prior.getString("streamId") == requestedStream && prior.getLong("anchor") == requestedAnchor &&
                prior.getLong("cursor") == requestedCursor && prior.getLong("previous") < requestedCursor && !prior.getBoolean("done")
            if (!provesPrior) return false
            // The receiver only requests this cursor after committing all exact manifest entries.
            store.acknowledge(room.id, checkpoint.packetId, checkpoint.peerId, checkpoint.digest)
            store.completeHistoryPage(room.id, checkpoint.packetId, checkpoint.peerId)
            onProof(checkpoint.peerId, checkpoint.packetId)
        }
        // Only one durable response page per parent can be in flight.
        if (store.pendingHistoryPackets(room.id, packet.actorId, PAGE_SIZE + 1).isNotEmpty()) return false
        val source = store.historySource(room.id, own)
        val cursor = if (requestedStream == source.streamId) requestedCursor else 0
        val anchor = if (requestedAnchor == 0L || requestedStream != source.streamId) source.anchor else requestedAnchor
        require(cursor <= anchor && anchor <= source.anchor)
        val entries = store.historyEntries(room.id, own, cursor, anchor, PAGE_SIZE)
        require(cursor == anchor || entries.isNotEmpty()) { "이동 기록의 저장 순서를 확인하지 못했어요." }
        val next = entries.lastOrNull()?.sequence ?: anchor
        val originals = entries.map { entry -> FamilyCareProtocol.outgoing(room, own, own, packet.actorId, "history_event",
            JSONObject().put("transferId", transfer).put("streamId", source.streamId).put("sequence", entry.sequence)
                .put("event", entry.event.copy(sender = "child", delivery = "relayed").json())) }
        val manifest = JSONArray().apply { entries.forEach { put(JSONObject().put("sequence", it.sequence).put("eventId", it.event.id)) } }
        val marker = FamilyCareProtocol.outgoing(room, own, own, packet.actorId, "history_checkpoint", JSONObject()
            .put("transferId", transfer).put("streamId", source.streamId).put("previous", cursor).put("cursor", next)
            .put("anchor", anchor).put("count", store.historySource(room.id, own, anchor).count).put("done", next == anchor).put("entries", manifest)
            .put("digest", digest(entries)))
        originals.forEach(store::queuePacket); store.queuePacket(marker)
        store.linkHistoryPage(marker, originals)
        return true
    }

    private fun receiveEvent(packet: FamilyCareProtocol.Packet): Boolean {
        FamilyChatValidation.keys(packet.body, setOf("transferId", "streamId", "sequence", "event"))
        identifier(packet.body, "transferId"); identifier(packet.body, "streamId")
        require(counter(packet.body, "sequence") > 0)
        val event = TelegramLedger.validate(FamilyEvent.parse(packet.body.getJSONObject("event")))
        require(event.sender == "child" && event.delivery == "relayed" && event.kind in MovementHistoryDates.KINDS)
        val old = store.movementEventDigest(room.id, packet.childId, event.id)
        if (old != null && old != TelegramLedger.eventDigest(event)) return false
        // A delayed page remains useful even after a new transfer has begun. Immutable IDs deduplicate it.
        store.archiveEvent(room.id, packet.childId, event)
        return true
    }

    private fun receiveCheckpoint(packet: FamilyCareProtocol.Packet): Boolean {
        val body = packet.body
        FamilyChatValidation.keys(body, setOf("transferId", "streamId", "previous", "cursor", "anchor", "count", "done", "entries", "digest"))
        val transfer = identifier(body, "transferId"); val stream = identifier(body, "streamId")
        val previous = counter(body, "previous"); val cursor = counter(body, "cursor"); val anchor = counter(body, "anchor")
        val count = counter(body, "count"); require(body.opt("done") is Boolean)
        val done = body.getBoolean("done"); require(previous <= cursor && cursor <= anchor && done == (cursor == anchor))
        val manifest = body.getJSONArray("entries"); require(manifest.length() <= PAGE_SIZE)
        require(count >= manifest.length() && (count == 0L) == (anchor == 0L))
        require(manifest.length() != 0 || previous == anchor)
        val ids = mutableListOf<String>(); val sequences = mutableListOf<Long>()
        for (i in 0 until manifest.length()) {
            val item = manifest.getJSONObject(i); FamilyChatValidation.keys(item, setOf("sequence", "eventId"))
            val sequence = counter(item, "sequence"); require(sequence > (sequences.lastOrNull() ?: previous) && sequence <= anchor)
            sequences.add(sequence); ids.add(identifier(item, "eventId"))
        }
        require(ids.distinct().size == ids.size && (sequences.lastOrNull() ?: anchor) == cursor)
        val expectedDigest = FamilyCareValidation.hash(body.getString("digest"))
        val archived = store.historyEvents(room.id, packet.childId, ids).associateBy { it.id }
        if (!ids.all(archived::containsKey)) return false
        val exact = ids.mapIndexed { index, id -> FamilyCareHistoryEntry(sequences[index], archived.getValue(id)) }
        if (digest(exact) != expectedDigest) return false
        val progress = store.historyProgress(room.id, packet.childId)
        // A verified old marker may clear its sender's page, but cannot advance another active transfer.
        if (progress == null || progress.transferId != transfer) return true
        val changedStream = progress.streamId != null && progress.streamId != stream
        if (changedStream && previous != 0L) return false
        if (!changedStream && progress.cursor != previous) return cursor <= progress.cursor && progress.streamId == stream
        if (!changedStream && progress.anchor != 0L && (progress.anchor != anchor || progress.streamId != stream)) return false
        val next = progress.copy(streamId = stream, cursor = cursor, anchor = anchor, count = count, complete = done, checkedAt = now())
        // The marker proves the exact request was handled; a lost request ACK must not block the next page.
        store.pendingHistoryPackets(room.id, packet.childId, PAGE_SIZE + 1).filter {
            val json = JSONObject(it.text); val request = json.getJSONObject("body")
            json.optString("type") == "history_request" && request.optString("transferId") == transfer &&
                request.optLong("cursor", -1) == progress.cursor && it.sentAt > 0
        }.forEach {
            store.acknowledge(room.id, it.packetId, it.peerId, it.digest)
            onProof(it.peerId, it.packetId)
        }
        store.saveHistoryProgress(next)
        if (!done) queueRequest(next)
        return true
    }

    companion object {
        const val PAGE_SIZE = 24
        private const val CHECK_INTERVAL = 15 * 60 * 1000L
        val TYPES = setOf("history_request", "history_event", "history_checkpoint")
        fun isHistory(packet: FamilyCareOutgoing): Boolean = runCatching { JSONObject(packet.text).optString("type") in TYPES }.getOrDefault(false)
        private fun identifier(j: JSONObject, key: String): String { require(j.opt(key) is String); return FamilyChatValidation.identifier(j.getString(key)) }
        private fun counter(j: JSONObject, key: String) = FamilyCareValidation.counter(j.opt(key))
        fun digest(entries: List<FamilyCareHistoryEntry>): String = FamilyCareValidation.digest(JSONArray().apply {
            entries.forEach { put(JSONObject().put("sequence", it.sequence).put("eventId", it.event.id).put("digest", TelegramLedger.eventDigest(it.event))) }
        })
    }
}
