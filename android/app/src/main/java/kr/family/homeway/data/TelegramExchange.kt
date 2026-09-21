package kr.family.homeway.data

import org.json.JSONObject

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
) {
    /** Caller serializes network runs; returns false when the persisted Telegram backoff is still active. */
    fun synchronize(timeout: Int = 0): Boolean {
        if (synchronized(lock) { store.meta("retryAfter") > now() }) return false
        try {
            checkActive()
            val updates = client.getUpdates(synchronized(lock) { store.meta("offset") }, timeout)
            checkActive()
            for (i in 0 until updates.length()) {
                checkActive()
                val update = updates.getJSONObject(i)
                val updateId = update.optLong("update_id", -1)
                var received: FamilyEvent? = null
                synchronized(lock) {
                    if (updateId >= store.meta("offset")) store.transaction {
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
                                    if (!TelegramLedger.contains(state, event.id)) received = event
                                    store.cache(next)
                                    store.queueReceipt(event.id)
                                }
                            }
                            "ack" -> {
                                val first = store.pending().firstOrNull()
                                if (first?.id == packet.id && store.meta("sentAt") > 0) {
                                    val state = store.cached() ?: TelegramLedger.emptyState()
                                    val events = state.optJSONArray("events")
                                    for (j in 0 until (events?.length() ?: 0)) {
                                        events!!.getJSONObject(j).takeIf { it.optString("id") == packet.id }
                                            ?.put("delivery", "relayed")
                                    }
                                    store.cache(state)
                                    store.remove(packet.id!!)
                                    store.setMeta("sentAt", 0)
                                }
                            }
                        }
                        // Persist state, dedup identities, receipt and offset as one atomic write.
                        store.setMeta("offset", updateId + 1)
                    }
                }
                received?.let(onReceived)
            }
            // Receipts never create receipts, including after retries of an ambiguous send.
            val receipts = synchronized(lock) { store.receipts().take(100) }
            for (id in receipts) {
                checkActive()
                client.send(peerId.toString(), TelegramProtocol.envelope("ack").put("id", id).toString())
                synchronized(lock) { store.transaction { store.removeReceipt(id) } }
            }
            val pending = synchronized(lock) { store.pending().firstOrNull() }
            if (pending != null && synchronized(lock) { store.meta("sentAt") == 0L || now() - store.meta("sentAt") >= 30_000 }) {
                checkActive()
                // Telegram may accept the message even if its HTTP response is lost. Persist the
                // attempt first so a peer ACK remains valid after that failure or a process crash.
                synchronized(lock) { store.transaction { store.setMeta("sentAt", now().coerceAtLeast(1)) } }
                client.send(peerId.toString(), TelegramProtocol.event(pending))
            }
            if (synchronized(lock) { store.meta("ledgerConflict") != 0L }) throw TelegramSyncException()
            return true
        } catch (error: TelegramException) {
            synchronized(lock) {
                store.setMeta("retryAfter", now() + (error.retryAfterSeconds ?: 15).toLong() * 1000)
            }
            throw error
        }
    }
}
