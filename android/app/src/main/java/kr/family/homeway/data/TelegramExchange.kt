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
    private val transport: FamilyTransport? = null,
    private val onPollProgress: (TelegramReceiveProgress) -> Unit = {},
    private val pendingOutgoingWork: () -> Boolean = { store.pending().isNotEmpty() },
    private val onInvalidDocument: () -> Unit = {},
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
            // A later authenticated live location can be displayed even when an earlier history
            // document is temporarily unavailable. This never advances the receive cursor or board.
            if (familyCare != null) for (i in 0 until updates.length()) {
                checkActive()
                val update = updates.getJSONObject(i)
                if (update.optLong("update_id", -1) < synchronized(lock) { store.meta("offset") }) continue
                val previews = transport?.latestPreviewUpdates(update) ?: listOf(update).filter(::isLatestPreview)
                for (preview in previews) {
                    val changed = synchronized(lock) { store.transaction { familyCare.processLatestPreview(preview) } }
                    if (changed) onCommitted()
                }
            }
            val acknowledgementsBefore = (familyChat?.receivedAcknowledgements ?: 0) +
                (familyCare?.receivedAcknowledgements ?: 0)
            var legacyAcknowledgements = 0
            var committedUpdates = 0
            var downloadedDocuments = 0
            var hasMoreUpdates = false
            for (i in 0 until updates.length()) {
                checkActive()
                val update = updates.getJSONObject(i)
                val updateId = update.optLong("update_id", -1)
                if (updateId < synchronized(lock) { store.meta("offset") }) continue
                // No file request, decompression or JSON parsing may hold the SQLite/data lock.
                val prepared = transport?.prepareDocument(update)
                if (prepared != null && downloadedDocuments >= MAX_DOCUMENTS_PER_EXCHANGE) {
                    hasMoreUpdates = (i until updates.length()).any { index ->
                        updates.getJSONObject(index).optLong("update_id", -1) >= synchronized(lock) { store.meta("offset") }
                    }
                    break
                }
                var documentRejected = false
                val downloaded = if (prepared == null) null else {
                    downloadedDocuments++
                    try { checkNotNull(transport).downloadDocument(prepared) }
                    catch (_: InvalidFamilyDocumentException) { documentRejected = true; null }
                    catch (error: TelegramException) {
                        // A permanently unavailable history file must not hide later plaintext
                        // fallback forever. The sender keeps every original until its own ACK.
                        if (!recordBlockedDownloadFailure(updateId, error)) throw error
                        documentRejected = true
                        null
                    }
                }
                checkActive()
                val received = mutableListOf<FamilyEvent>()
                val chatReceived = mutableListOf<FamilyChatMessage>()
                var committed = false
                synchronized(lock) {
                    if (updateId >= store.meta("offset")) store.transaction {
                        val originals = if (prepared != null) {
                            if (downloaded == null) emptyList() else {
                                checkNotNull(transport).noteDownloadedDocument(prepared, downloaded)
                                downloaded
                            }
                        } else if (transport == null) listOf(update) else transport.incoming(update).orEmpty()
                        for (original in originals) {
                        familyChat?.processUpdate(original)?.let(chatReceived::add)
                        familyCare?.processUpdate(original)
                        val packet = TelegramProtocol.receive(original, peerId, peerRole)
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
                                        if (!TelegramLedger.contains(state, event.id)) received.add(event)
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
                                    legacyAcknowledgements++
                                }
                            }
                        }
                        }
                        // Persist state, dedup identities, receipt and offset as one atomic write.
                        store.setMeta("offset", updateId + 1)
                        clearPastBlockedDownload()
                        committed = true
                    }
                }
                if (committed) {
                    committedUpdates++
                    onCommitted()
                    if (documentRejected) onInvalidDocument()
                }
                // Notify at the commit boundary: a later notification or receipt failure must not
                // erase the new-message deadline. Duplicates and non-chat packets do not reset it.
                if (received.any { it.kind == "chat" } || chatReceived.isNotEmpty()) onNewChatCommitted()
                received.forEach(onReceived)
                chatReceived.forEach { familyChat?.notifyReceived(it) }
            }
            if (!interrupted) onPollCompleted()
            flushAll()
            if (!interrupted) {
                val progress = synchronized(lock) {
                    TelegramReceiveProgress(committedUpdates, legacyAcknowledgements +
                        (familyChat?.receivedAcknowledgements ?: 0) + (familyCare?.receivedAcknowledgements ?: 0) -
                        acknowledgementsBefore,
                        pendingReceipts = (peerId > 0 && store.meta("legacyRetryAfter") <= now() && store.receipts().isNotEmpty()) ||
                            familyChat?.hasReadyReceipts() == true || familyCare?.hasReadyReceipts() == true,
                        pendingOutgoing = pendingOutgoingWork(), hasMoreUpdates = hasMoreUpdates)
                }
                onPollProgress(progress)
            }
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

    /** Only eligible file-I/O failures consume this one durable retry slot. */
    private fun recordBlockedDownloadFailure(updateId: Long, error: TelegramException): Boolean {
        if (error.operation !in setOf(TelegramOperation.FILE_INFO, TelegramOperation.FILE_DOWNLOAD) ||
            (error.errorCode != 0 && error.errorCode !in 500..599)) return false
        checkActive()
        return synchronized(lock) {
            store.transaction {
                if (updateId < store.meta("offset")) return@transaction false
                val prior = if (store.meta(BLOCKED_DOCUMENT_UPDATE) == updateId) store.meta(BLOCKED_DOCUMENT_FAILURES) else 0
                val failures = (prior.coerceIn(0, MAX_BLOCKED_DOCUMENT_FAILURES.toLong()) + 1)
                    .coerceAtMost(MAX_BLOCKED_DOCUMENT_FAILURES.toLong())
                store.setMeta(BLOCKED_DOCUMENT_UPDATE, updateId)
                store.setMeta(BLOCKED_DOCUMENT_FAILURES, failures)
                failures >= MAX_BLOCKED_DOCUMENT_FAILURES
            }
        }
    }

    /** Runs in the same transaction as the advancing receive cursor. */
    private fun clearPastBlockedDownload() {
        if (store.meta(BLOCKED_DOCUMENT_FAILURES) > 0 && store.meta(BLOCKED_DOCUMENT_UPDATE) < store.meta("offset")) {
            store.setMeta(BLOCKED_DOCUMENT_UPDATE, 0)
            store.setMeta(BLOCKED_DOCUMENT_FAILURES, 0)
        }
    }

    private fun isLatestPreview(update: JSONObject): Boolean = runCatching {
        val text = update.optJSONObject("message")?.opt("text") as? String ?: return false
        if (text.length > 4096) return false
        val packet = JSONObject(text)
        packet.optString("app") == "TalkingFamily" && packet.opt("v") == 4 &&
            packet.optString("type") == "care_location_head"
    }.getOrDefault(false)

    private fun flushAll() {
        transport?.flushControls(repliesOnly = true)
        familyCare?.flushLatestLocations()
        // All three lanes use the same peer permit. Start after the lane which actually
        // used a turn, so inactive lanes cannot bias the next winner after reconstruction.
        val firstLane = if (transport == null) 0 else synchronized(lock) {
            store.meta(NEXT_FLUSH_LANE).coerceIn(0, 2).toInt()
        }
        var nextLane = firstLane
        repeat(3) { index ->
            val lane = (firstLane + index) % 3
            val permitsBefore = transport?.reservedSendPermits
            when (lane) {
                0 -> familyChat?.flush()
                1 -> flushLegacyLane()
                2 -> familyCare?.flush()
            }
            if (permitsBefore != null && permitsBefore != transport?.reservedSendPermits) nextLane = (lane + 1) % 3
        }
        if (nextLane != firstLane) synchronized(lock) { store.transaction { store.setMeta(NEXT_FLUSH_LANE, nextLane.toLong()) } }
        transport?.flushControls(repliesOnly = false)
        if (synchronized(lock) { store.meta("ledgerConflict") != 0L }) throw TelegramSyncException()
    }

    private fun flushLegacyLane() {
        if (synchronized(lock) { store.meta("legacyRetryAfter") > now() }) return
        try { flushLegacy() }
        catch (error: TelegramException) {
            // A paired phone's failure must not freeze an otherwise healthy family room.
            // Keep the original two-phone behavior when no room is active.
            if ((familyChat == null && familyCare == null) || error.errorCode in setOf(401, 404, 409, 429)) throw error
            synchronized(lock) { store.setMeta("legacyRetryAfter", now() + 15_000) }
            onLegacyFailure(error)
        }
    }

    private fun flushLegacy() {
        if (peerId <= 0) return
        synchronized(lock) { legacyWindow.initialize() }
        // Receipts never create receipts, including after retries of an ambiguous send.
        val receipts = synchronized(lock) { store.receipts().take(TelegramLegacyWindow.MAX_RECEIPTS_PER_FLUSH) }
        for (id in receipts) {
            checkActive()
            if (transport != null) {
                if (!transport.sendLegacy(peerId, TelegramProtocol.envelope("ack").put("id", id).toString(), afterSend = {
                    synchronized(lock) { store.transaction { store.removeReceipt(id) } }
                })) return
                continue
            }
            client.send(peerId.toString(), TelegramProtocol.envelope("ack").put("id", id).toString())
            synchronized(lock) { store.transaction { store.removeReceipt(id) } }
        }
        val sentThisFlush = mutableSetOf<String>()
        repeat(TelegramLegacyWindow.MAX_SENDS_PER_FLUSH) {
            val pending = synchronized(lock) { legacyWindow.next(sentThisFlush) } ?: return
            checkActive()
            if (transport != null) {
                if (!transport.sendLegacy(peerId, TelegramProtocol.event(pending), beforeSend = {
                    synchronized(lock) { store.transaction { legacyWindow.markAttempt(pending.id) } }
                }, afterSend = {
                    synchronized(lock) { store.transaction { legacyWindow.markConfirmed(pending.id) } }
                })) return
                sentThisFlush += pending.id
                return@repeat
            }
            // Persist first: Telegram can accept this packet while its HTTP response is lost.
            synchronized(lock) { store.transaction { legacyWindow.markAttempt(pending.id) } }
            client.send(peerId.toString(), TelegramProtocol.event(pending))
            synchronized(lock) { store.transaction { legacyWindow.markConfirmed(pending.id) } }
            sentThisFlush += pending.id
        }
    }

    companion object {
        const val MAX_DOCUMENTS_PER_EXCHANGE = 2
        internal const val BLOCKED_DOCUMENT_UPDATE = "documentBlockedUpdate"
        internal const val BLOCKED_DOCUMENT_FAILURES = "documentBlockedFailures"
        const val MAX_BLOCKED_DOCUMENT_FAILURES = 3
        internal const val NEXT_FLUSH_LANE = "exchangeNextFlushLane"
    }
}
