package kr.family.homeway.data

/** All methods participate in LocalStore's transaction, including the Telegram receive offset. */
interface FamilyCareStore {
    fun <T> transaction(block: () -> T): T
    fun state(roomId: String, childId: Long): FamilyCareState?
    fun saveState(state: FamilyCareState)
    fun outcome(roomId: String, commandId: String): FamilyCareOutcome?
    fun saveOutcome(outcome: FamilyCareOutcome)
    fun outcomes(roomId: String, childId: Long): List<FamilyCareOutcome>
    fun saveCommand(command: FamilyCareCommand)
    fun commands(roomId: String, childId: Long): List<FamilyCareCommand>
    fun receivedDigest(roomId: String, packetId: String): String?
    fun recordReceived(roomId: String, packetId: String, digest: String)
    fun queuePacket(packet: FamilyCareOutgoing)
    fun pendingPackets(roomId: String): List<FamilyCareOutgoing>
    fun markSent(roomId: String, packetId: String, peerId: Long, sentAt: Long)
    fun acknowledge(roomId: String, packetId: String, peerId: Long, digest: String)
    fun queueReceipt(receipt: FamilyCareReceipt)
    fun receipts(roomId: String): List<FamilyCareReceipt>
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

data class FamilyCareOutgoing(val roomId: String, val packetId: String, val childId: Long,
    val peerId: Long, val text: String, val digest: String, val sentAt: Long = 0)
data class FamilyCareReceipt(val roomId: String, val packetId: String, val childId: Long,
    val peerId: Long, val digest: String)
data class FamilyCareChunk(val roomId: String, val transferId: String, val childId: Long,
    val epoch: String, val revision: Long, val index: Int, val count: Int,
    val digest: String, val encoded: String)
