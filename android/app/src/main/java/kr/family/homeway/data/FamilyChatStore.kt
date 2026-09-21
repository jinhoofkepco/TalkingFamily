package kr.family.homeway.data

/** Implemented by LocalStore in the same database/transaction as Telegram's update offset. */
interface FamilyChatStore {
    fun <T> transaction(block: () -> T): T
    fun chatMessage(roomId: String, messageId: String): FamilyChatMessage?
    /** Insert an immutable message and its fixed recipient snapshot atomically; never replace existing text. */
    fun insertChatMessage(message: FamilyChatMessage, recipients: List<Long>)
    /** Oldest insertion first. Includes only unacknowledged recipients, not completed rows. */
    fun pendingChatDeliveries(roomId: String): List<FamilyChatDelivery>
    fun markChatSent(roomId: String, messageId: String, peerId: Long, sentAt: Long)
    fun acknowledgeChat(roomId: String, messageId: String, peerId: Long)
    fun queueChatReceipt(receipt: FamilyChatReceipt)
    fun chatReceipts(roomId: String): List<FamilyChatReceipt>
    fun removeChatReceipt(receipt: FamilyChatReceipt)
    fun chatPeerRetryAfter(roomId: String, peerId: Long): Long
    fun setChatPeerRetryAfter(roomId: String, peerId: Long, retryAfter: Long)
    /** Newest page first, stable (createdAt DESC, id DESC) cursor; callers reverse for the screen. */
    fun chatHistory(roomId: String, before: FamilyChatCursor? = null, limit: Int = 200): FamilyChatPage
}
