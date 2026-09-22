package kr.family.homeway.data

import org.json.JSONObject
import org.json.JSONArray

/** The same transactional boundary is implemented by SQLite on phones and an in-memory store in tests. */
interface TelegramExchangeStore {
    fun <T> transaction(block: () -> T): T
    fun meta(name: String): Long
    fun setMeta(name: String, value: Long)
    fun cached(): JSONObject?
    fun cache(state: JSONObject)
    fun pending(): List<FamilyEvent>
    fun remove(id: String)
    fun queueReceipt(id: String)
    fun receipts(): List<String>
    fun removeReceipt(id: String)
}

internal class TelegramSyncException : Exception("가족 기록이 서로 달라요. 두 휴대폰을 함께 다시 연결해 주세요.")

/** Actual direct-message exchange, independent of Android so transport failures can be exercised end to end. */
internal class TelegramExchange(
    private val client: TelegramClient,
    private val store: TelegramExchangeStore,
    private val peerId: Long,
    private val peerRole: String,
    private val lock: Any = Any(),
    private val now: () -> Long = System::currentTimeMillis,
    private val checkActive: () -> Unit = {},
    private val onReceived: (FamilyEvent) -> Unit = {},
    private val familyChat: FamilyChatExchange? = null,
    private val onLegacyFailure: (TelegramException) -> Unit = {},
    private val familyCare: FamilyCareEngine? = null,
    private val onCommitted: () -> Unit = {},
    private val onNewChatCommitted: () -> Unit = {},
    private val onPollCompleted: () -> Unit = {},
    private val pollAllowed: () -> Boolean = { true },
) {
    private val legacyWindow = TelegramLegacyWindow(store, now)
    /** Caller serializes network runs; returns false when the persisted Telegram backoff is still active. */
    fun synchronize(timeout: Int = 0): Boolean {
        if (synchronized(lock) { store.meta("retryAfter") > now() }) return false
        try {
            checkActive()
            val pollRevision = TelegramPollWakeup.revision
            val ready = synchronized(lock) {
                if (peerId > 0) legacyWindow.initialize()
                (peerId > 0 && store.meta("legacyRetryAfter") <= now() &&
                    (store.receipts().isNotEmpty() || legacyWindow.next() != null)) ||
                    familyChat?.hasReadyWork() == true || familyCare?.hasReadyWork() == true
            }
            if (!pollAllowed()) {
                flushAll()
                return true
            }
            var interrupted = false
            val updates = try {
                client.getUpdates(synchronized(lock) { store.meta("offset") }, if (ready) 0 else timeout, pollRevision)
            } catch (_: TelegramPollInterruptedException) {
                // A local enqueue woke only getUpdates. Keep the offset and flush its durable outbox now.
                interrupted = true
                JSONArray()
            }
            checkActive()
            for (i in 0 until updates.length()) {
                checkActive()
                val update = updates.getJSONObject(i)
                val updateId = update.optLong("update_id", -1)
                var received: FamilyEvent? = null
                var chatReceived: FamilyChatMessage? = null
                var committed = false
                synchronized(lock) {
                    if (updateId >= store.meta("offset")) store.transaction {
                        chatReceived = familyChat?.processUpdate(update)
                        familyCare?.processUpdate(update)
                        val packet = TelegramProtocol.receive(update, peerId, peerRole)
                        if (packet != null) when (packet.type) {
                            "event" -> {
                                val event = packet.event!!
                                val state = store.cached() ?: TelegramLedger.emptyState()
                                // Invalid financial transitions are never acknowledged. Keep the other lane
                                // working and retain an actionable error across polls and process restarts.
                                val next = try { TelegramLedger.apply(state, event) }
                                catch (_: IllegalArgumentException) { store.setMeta("ledgerConflict", 1); null }
                                if (next != null) {
                                    if (familyCare?.processLegacy(event, peerId) == false) {
                                        // An old parent's optimistic v2 cache must not acknowledge a
                                        // decision rejected by this child's authoritative family board.
                                        store.setMeta("ledgerConflict", 1)
                                    } else {
                                        if (!TelegramLedger.contains(state, event.id)) received = event
                                        store.cache(next)
                                        store.queueReceipt(event.id)
                                    }
                                }
                            }
                            "ack" -> {
                                if (legacyWindow.canAcknowledge(packet.id)) {
                                    val state = store.cached() ?: TelegramLedger.emptyState()
                                    val events = state.optJSONArray("events")
                                    for (j in 0 until (events?.length() ?: 0)) {
                                        events!!.getJSONObject(j).takeIf { it.optString("id") == packet.id }
                                            ?.put("delivery", "relayed")
                                    }
                                    // These records outlive the bounded event list and must retain its receipt status.
                                    listOf("latestLocation", "latestHeartbeat").forEach { key ->
                                        state.optJSONObject(key)?.takeIf { it.optString("id") == packet.id }
                                            ?.put("delivery", "relayed")
                                    }
                                    store.cache(state)
                                    legacyWindow.acknowledge(packet.id!!)
                                }
                            }
                        }
                        // Persist state, dedup identities, receipt and offset as one atomic write.
                        store.setMeta("offset", updateId + 1)
                        committed = true
                    }
                }
                if (committed) onCommitted()
                // Notify at the commit boundary: a later notification or receipt failure must not
                // erase the new-message deadline. Duplicates and non-chat packets do not reset it.
                if (received?.kind == "chat" || chatReceived != null) onNewChatCommitted()
                received?.let(onReceived)
                chatReceived?.let { familyChat?.notifyReceived(it) }
            }
            if (!interrupted) onPollCompleted()
            flushAll()
            return true
        } catch (error: TelegramException) {
            recordBackoff(error)
            throw error
        }
    }

    /** Sending a location or local chat must not consume or reschedule the incoming update stream. */
    fun flushOutgoing(): Boolean {
        if (synchronized(lock) { store.meta("retryAfter") > now() }) return false
        try {
            checkActive()
            flushAll()
            return true
        } catch (error: TelegramException) {
            recordBackoff(error)
            throw error
        }
    }

    private fun recordBackoff(error: TelegramException) = synchronized(lock) {
        store.setMeta("retryAfter", now() + (error.retryAfterSeconds ?: 15).toLong() * 1000)
    }

    private fun flushAll() {
            familyChat?.flush()
            if (synchronized(lock) { store.meta("legacyRetryAfter") <= now() }) {
                try { flushLegacy() }
                catch (error: TelegramException) {
                    // A paired phone's failure must not freeze an otherwise healthy family room.
                    // Keep the original two-phone behavior when no room is active.
                    if ((familyChat == null && familyCare == null) || error.errorCode in setOf(401, 404, 409, 429)) throw error
                    synchronized(lock) { store.setMeta("legacyRetryAfter", now() + 15_000) }
                    onLegacyFailure(error)
                }
            }
            familyCare?.flush()
            if (synchronized(lock) { store.meta("ledgerConflict") != 0L }) throw TelegramSyncException()
    }

    private fun flushLegacy() {
        if (peerId <= 0) return
        synchronized(lock) { legacyWindow.initialize() }
        // Receipts never create receipts, including after retries of an ambiguous send.
        val receipts = synchronized(lock) { store.receipts().take(TelegramLegacyWindow.MAX_RECEIPTS_PER_FLUSH) }
        for (id in receipts) {
            checkActive()
            client.send(peerId.toString(), TelegramProtocol.envelope("ack").put("id", id).toString())
            synchronized(lock) { store.transaction { store.removeReceipt(id) } }
        }
        val sentThisFlush = mutableSetOf<String>()
        repeat(TelegramLegacyWindow.MAX_SENDS_PER_FLUSH) {
            val pending = synchronized(lock) { legacyWindow.next(sentThisFlush) } ?: return
            checkActive()
            // Persist first: Telegram can accept this packet while its HTTP response is lost.
            synchronized(lock) { store.transaction { legacyWindow.markAttempt(pending.id) } }
            client.send(peerId.toString(), TelegramProtocol.event(pending))
            synchronized(lock) { store.transaction { legacyWindow.markConfirmed(pending.id) } }
            sentThisFlush += pending.id
        }
    }
}
