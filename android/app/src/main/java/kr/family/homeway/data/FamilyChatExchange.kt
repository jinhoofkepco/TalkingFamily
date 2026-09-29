package kr.family.homeway.data

import org.json.JSONObject

/** Shares the sole Telegram polling loop; never consumes getUpdates independently. */
class FamilyChatExchange(
    private val client: TelegramClient,
    private val store: FamilyChatStore,
    room: FamilyChatRoom,
    private val ownBotId: Long,
    private val lock: Any = Any(),
    private val now: () -> Long = System::currentTimeMillis,
    private val checkActive: () -> Unit = {},
    private val onReceived: (FamilyChatMessage) -> Unit = {},
    private val onPeerFailure: (Long, TelegramException) -> Unit = { _, _ -> },
    private val transport: FamilyTransport? = null,
) {
    private val room = FamilyChatValidation.room(room)
    init { require(this.room.members.any { it.botId == ownBotId }) { "이 휴대폰이 가족 명단에 없어요." } }

    /** Used by the repository; inserting locally and snapshotting all recipients is one durable write. */
    fun enqueue(input: FamilyChatMessage) = synchronized(lock) {
        val message = FamilyChatValidation.message(input)
        require(message.roomId == room.id && message.senderId == ownBotId) { "가족방을 확인해 주세요." }
        require(FamilyChatProtocol.message(message).length <= 4096) { "메시지를 짧게 나누어 보내 주세요." }
        store.transaction {
            val prior = store.chatMessage(room.id, message.id)
            require(prior == null || prior.digest == message.digest) { "같은 메시지의 내용이 달라졌어요." }
            if (prior == null) store.insertChatMessage(message, room.members.map { it.botId }.filter { it != ownBotId })
        }
    }

    /** Caller holds the shared data lock and SQLite transaction that also commits the update offset. */
    fun processUpdate(update: JSONObject): FamilyChatMessage? {
        val packet = FamilyChatProtocol.receive(update, room, ownBotId) ?: return null
        packet.message?.let { message ->
            val prior = store.chatMessage(room.id, message.id)
            // Conflicting identities receive no ACK. A retry must never overwrite accepted text.
            if (prior != null && prior.digest != message.digest) return null
            if (prior != null) transport?.noteReplayedPacket(message.senderId)
            if (prior == null) store.insertChatMessage(message, emptyList())
            store.queueChatReceipt(FamilyChatReceipt(room.id, message.id, message.senderId, message.digest))
            return message.takeIf { prior == null }
        }
        packet.receipt?.let { receipt ->
            val message = store.chatMessage(room.id, receipt.messageId) ?: return null
            if (message.senderId != ownBotId || message.digest != receipt.digest) return null
            val delivery = store.pendingChatDelivery(room.id, receipt.messageId, receipt.peerId)
            if (delivery != null && delivery.sentAt > 0) {
                store.acknowledgeChat(room.id, receipt.messageId, receipt.peerId)
                store.setChatPeerRetryAfter(room.id, receipt.peerId, 0)
            }
        }
        return null
    }

    /** Must run only after the receiving transaction has committed successfully. */
    fun notifyReceived(message: FamilyChatMessage) { onReceived(message) }

    fun hasReadyWork(): Boolean = synchronized(lock) {
        room.members.any { member ->
            member.botId != ownBotId && store.chatPeerRetryAfter(room.id, member.botId) <= now() &&
                (store.firstChatReceipt(room.id, member.botId) != null ||
                    store.pendingChatDeliveries(room.id, member.botId, 1).firstOrNull()?.let {
                        it.sentAt == 0L || now() - it.sentAt >= 30_000
                    } == true)
        }
    }

    /** Each recipient has an independent FIFO and retry deadline; no offline family member blocks another. */
    fun flush() {
        for (member in room.members.filter { it.botId != ownBotId }) {
            checkActive()
            val peer = member.botId
            if (synchronized(lock) { store.chatPeerRetryAfter(room.id, peer) > now() }) continue
            try {
                if (transport != null) {
                    flushTransport(member, transport)
                    continue
                }
                // Bound each peer's work, so a replay burst cannot starve other people or location sharing.
                val receipt = synchronized(lock) { store.firstChatReceipt(room.id, peer) }
                if (receipt != null) {
                    checkActive()
                    client.sendToFamilyMember(member, FamilyChatProtocol.receipt(receipt), checkActive)
                    synchronized(lock) { store.transaction { store.removeChatReceipt(receipt) } }
                }
                val delivery = synchronized(lock) { store.pendingChatDeliveries(room.id, peer, 1).firstOrNull() }
                if (delivery != null && (delivery.sentAt == 0L || now() - delivery.sentAt >= 30_000)) {
                    val message = synchronized(lock) { store.chatMessage(room.id, delivery.messageId) }
                        ?: error("가족방 발송 기록을 찾을 수 없어요.")
                    checkActive()
                    synchronized(lock) { store.transaction {
                        store.markChatSent(room.id, delivery.messageId, peer, now().coerceAtLeast(1))
                    } }
                    // Persist before HTTP: a lost response must still permit the later authenticated ACK.
                    client.sendToFamilyMember(member, FamilyChatProtocol.message(message), checkActive)
                }
            } catch (error: TelegramException) {
                // Rate limits and bot credential/consumer failures are global, handled by TelegramExchange.
                if (error.errorCode in setOf(401, 404, 409, 429)) throw error
                synchronized(lock) { store.transaction { store.setChatPeerRetryAfter(room.id, peer, now() + 15_000) } }
                onPeerFailure(peer, error)
            }
        }
    }

    private fun flushTransport(member: FamilyChatMember, transport: FamilyTransport) {
        val peer = member.botId
        val receipts = synchronized(lock) { store.chatReceipts(room.id, peer, FamilyTransportProtocol.MAX_PACKETS) }
        if (receipts.isNotEmpty()) transport.send(member, receipts.map(FamilyChatProtocol::receipt),
            allowBatch = transport.canBatchAcknowledgements(peer), afterSend = { count ->
            synchronized(lock) { store.transaction { receipts.take(count).forEach(store::removeChatReceipt) } }
        })
        val candidates = synchronized(lock) { store.pendingChatDeliveries(room.id, peer, FamilyTransportProtocol.MAX_PACKETS) }
        val head = candidates.firstOrNull() ?: return
        val deliveries = if (head.sentAt == 0L) candidates.takeWhile { it.sentAt == 0L }
            else if (now() < head.sentAt || now() - head.sentAt >= 30_000) listOf(head) else return
        val texts = synchronized(lock) { deliveries.map {
            FamilyChatProtocol.message(checkNotNull(store.chatMessage(room.id, it.messageId)))
        } }
        transport.send(member, texts, allowBatch = head.sentAt == 0L, beforeSend = { count ->
            synchronized(lock) { store.transaction {
                deliveries.take(count).forEach { store.markChatSent(room.id, it.messageId, peer, now().coerceAtLeast(1)) }
            } }
        })
    }
}
