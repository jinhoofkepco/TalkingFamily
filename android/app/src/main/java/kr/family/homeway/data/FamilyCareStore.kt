package kr.family.homeway.data

/** All methods participate in LocalStore's transaction, including the Telegram receive offset. */
interface FamilyCareStore {
    fun <T> transaction(block: () -> T): T
    fun state(roomId: String, childId: Long): FamilyCareState?
    fun stateMetadata(roomId: String, childId: Long): FamilyCareStateMetadata? = state(roomId, childId)?.let {
        FamilyCareStateMetadata(it.epoch, it.revision, it.authoritative)
    }
    fun saveState(state: FamilyCareState)
    /** Bounded repair history; permanent event identities must never be pruned with it. */
    fun saveDelta(delta: FamilyCareDelta) = Unit
    fun deltas(roomId: String, childId: Long, epoch: String, afterRevision: Long, limit: Int): List<FamilyCareDelta> = emptyList()
    /** Keep the original durable until every compatible replacement has been acknowledged. */
    fun replacePacket(original: FamilyCareOutgoing, replacements: List<FamilyCareOutgoing>) = Unit
    fun hasReplacement(roomId: String, packetId: String, peerId: Long): Boolean = false
    fun packetsNeedingCompatibility(roomId: String, peerId: Long, limit: Int): List<FamilyCareOutgoing> =
        pendingPackets(roomId).filter { it.peerId == peerId && !hasReplacement(roomId, it.packetId, peerId) &&
            runCatching { org.json.JSONObject(it.text).optString("type") in setOf("care_delta", "care_sync") }.getOrDefault(false) }.take(limit)
    /** Replay identities are independent of the bounded display projection. */
    fun eventDigest(roomId: String, childId: Long, eventId: String): String? =
        state(roomId, childId)?.state?.optJSONObject("appliedEventIds")?.optString(eventId)?.takeIf { it.isNotEmpty() }
    fun recordEventDigest(roomId: String, childId: Long, eventId: String, digest: String) = Unit
    fun outcome(roomId: String, commandId: String): FamilyCareOutcome?
    fun saveOutcome(outcome: FamilyCareOutcome)
    fun outcomes(roomId: String, childId: Long): List<FamilyCareOutcome>
    fun saveCommand(command: FamilyCareCommand)
    fun commands(roomId: String, childId: Long): List<FamilyCareCommand>
    fun latestCommand(roomId: String, childId: Long): FamilyCareCommand? = commands(roomId, childId).lastOrNull()
    fun receivedDigest(roomId: String, packetId: String): String?
    fun recordReceived(roomId: String, packetId: String, digest: String)
    fun queuePacket(packet: FamilyCareOutgoing)
    fun pendingPackets(roomId: String): List<FamilyCareOutgoing>
    fun pendingPacket(roomId: String, packetId: String, peerId: Long): FamilyCareOutgoing? =
        pendingPackets(roomId).firstOrNull { it.packetId == packetId && it.peerId == peerId }
    fun pendingPackets(roomId: String, peerId: Long, limit: Int): List<FamilyCareOutgoing> =
        pendingPackets(roomId).filter { it.peerId == peerId }.take(limit)
    fun pendingPeers(roomId: String): List<Long> =
        (pendingPackets(roomId).map { it.peerId } + receipts(roomId).map { it.peerId }).distinct()
    fun hasPendingPackets(roomId: String): Boolean = pendingPackets(roomId).isNotEmpty()
    fun hasPendingKind(roomId: String, childId: Long, type: String): Boolean = pendingPackets(roomId).any {
        it.childId == childId && runCatching { org.json.JSONObject(it.text).optString("type") == type }.getOrDefault(false)
    }
    /** Persist an attempt before HTTP; it also clears confirmation until that attempt succeeds. */
    fun markSent(roomId: String, packetId: String, peerId: Long, sentAt: Long)
    fun markSendConfirmed(roomId: String, packetId: String, peerId: Long)
    fun acknowledge(roomId: String, packetId: String, peerId: Long, digest: String)
    fun queueReceipt(receipt: FamilyCareReceipt)
    fun receipts(roomId: String): List<FamilyCareReceipt>
    fun firstReceipt(roomId: String, peerId: Long): FamilyCareReceipt? = receipts(roomId).firstOrNull { it.peerId == peerId }
    fun receipts(roomId: String, peerId: Long, limit: Int): List<FamilyCareReceipt> =
        receipts(roomId).filter { it.peerId == peerId }.take(limit)
    fun removeReceipt(receipt: FamilyCareReceipt)
    fun retryAfter(roomId: String, peerId: Long): Long
    fun setRetryAfter(roomId: String, peerId: Long, until: Long)
    fun snapshotChunks(roomId: String, transferId: String): List<FamilyCareChunk>
    fun putSnapshotChunk(chunk: FamilyCareChunk)
    fun removeSnapshotChunks(roomId: String, transferId: String)
    fun setError(roomId: String, childId: Long, error: String?)
    fun error(roomId: String, childId: Long): String?
    /** Location/vertical are archived; heartbeat/sharing changes remain in the care state. */
    fun archiveEvent(roomId: String, childId: Long, event: FamilyEvent)
}

data class FamilyCareStateMetadata(val epoch: String, val revision: Long, val authoritative: Boolean)

data class FamilyCareOutgoing(val roomId: String, val packetId: String, val childId: Long,
    val peerId: Long, val text: String, val digest: String, val sentAt: Long = 0,
    val sendConfirmed: Boolean = false)
data class FamilyCareReceipt(val roomId: String, val packetId: String, val childId: Long,
    val peerId: Long, val digest: String)
data class FamilyCareChunk(val roomId: String, val transferId: String, val childId: Long,
    val epoch: String, val revision: Long, val index: Int, val count: Int,
    val digest: String, val encoded: String)
