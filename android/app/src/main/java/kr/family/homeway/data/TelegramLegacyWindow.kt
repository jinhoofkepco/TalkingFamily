package kr.family.homeway.data

/** Metadata-only v2 pipeline. Callers hold the shared store lock and transact every mutation. */
internal class TelegramLegacyWindow(private val store: TelegramExchangeStore, private val now: () -> Long) {
    fun initialize() {
        if (store.meta(VERSION_KEY) != 0L) return
        val first = store.pending().firstOrNull() ?: return
        store.transaction {
            // Older installs tracked only the head. Its response may have been lost, so only
            // an authenticated ACK or a successful retry can establish that it was accepted.
            val oldSentAt = store.meta("sentAt")
            if (oldSentAt > 0) {
                store.setMeta(sentAtKey(first.id), oldSentAt)
                store.setMeta(confirmedKey(first.id), 0)
            }
            store.setMeta(VERSION_KEY, 1)
        }
    }

    fun next(sentThisFlush: Set<String> = emptySet()): FamilyEvent? {
        for (event in store.pending().take(MAX_IN_FLIGHT)) {
            if (event.id in sentThisFlush) continue
            val sentAt = store.meta(sentAtKey(event.id))
            if (sentAt == 0L || now() < sentAt || now() - sentAt >= RETRY_MILLIS) return event
            // A failed/ambiguous HTTP attempt is an ordering barrier, even before its retry is due.
            if (store.meta(confirmedKey(event.id)) != 1L) return null
        }
        return null
    }

    fun markAttempt(id: String) {
        store.setMeta(sentAtKey(id), now().coerceAtLeast(1))
        store.setMeta(confirmedKey(id), 0)
        mirrorHead()
    }

    fun markConfirmed(id: String) { store.setMeta(confirmedKey(id), 1) }

    fun canAcknowledge(id: String?): Boolean = id != null &&
        store.pending().take(MAX_IN_FLIGHT).any { it.id == id } && store.meta(sentAtKey(id)) > 0

    fun acknowledge(id: String) {
        store.remove(id)
        store.setMeta(sentAtKey(id), 0)
        store.setMeta(confirmedKey(id), 0)
        mirrorHead()
    }

    private fun mirrorHead() {
        // Compatibility only: this global value must never be reused as proof for another ID.
        store.setMeta("sentAt", store.pending().firstOrNull()?.let { store.meta(sentAtKey(it.id)) } ?: 0)
    }

    companion object {
        const val MAX_IN_FLIGHT = 16
        const val MAX_SENDS_PER_FLUSH = 8
        const val MAX_RECEIPTS_PER_FLUSH = 8
        private const val RETRY_MILLIS = 30_000L
        private const val VERSION_KEY = "legacyWindowVersion"
        fun sentAtKey(id: String) = "legacySentAt:$id"
        fun confirmedKey(id: String) = "legacyConfirmed:$id"
    }
}
