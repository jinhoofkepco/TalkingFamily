package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Bounded transport framing; original authenticated v3/v4 messages retain their IDs and digests. */
internal object FamilyTransportProtocol {
    const val MAX_PACKETS = 16
    const val MAX_BYTES = 65_536
    private fun envelope(type: String) = JSONObject().put("app", "TalkingFamily").put("v", 5).put("type", type)
    fun capability(nonce: Long, reply: Boolean): String = envelope("capabilities")
        .put("batch", 1).put("nonce", nonce).put("reply", reply).toString()
    // A separate control preserves the strict, unchanged 0.6.7 batch-capability envelope.
    fun careCapability(nonce: Long, reply: Boolean): String = envelope("care_capabilities")
        .put("delta", 1).put("nonce", nonce).put("reply", reply).toString()

    fun batch(texts: List<String>): String {
        require(texts.size in 2..MAX_PACKETS && texts.all { it.length <= 4096 })
        val bytes = JSONArray(texts).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
        return envelope("batch").put("codec", "gzip-base64").put("data", Base64.getEncoder().encodeToString(compressed)).toString()
            .also { require(it.length <= 4096) }
    }

    fun unpack(json: JSONObject): List<String> {
        FamilyChatValidation.keys(json, setOf("app", "v", "type", "codec", "data"))
        require(json.getString("codec") == "gzip-base64")
        val encoded = json.getString("data")
        require(encoded.length <= 4096)
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
        val packets = JSONArray(text)
        require(packets.length() in 2..MAX_PACKETS)
        return (0 until packets.length()).map { index ->
            (packets.opt(index) as? String)?.also { require(it.length <= 4096) } ?: error("Invalid transport packet")
        }
    }
}

/** One instance per serialized exchange; durable metadata shares the receive transaction. No sleeps. */
class FamilyTransport(
    private val client: TelegramClient,
    private val store: TelegramExchangeStore,
    private val room: FamilyChatRoom,
    private val ownBotId: Long,
    private val lock: Any = Any(),
    private val now: () -> Long = System::currentTimeMillis,
    private val checkActive: () -> Unit = {},
) {
    var deferredUntilMillis: Long? = null
        private set
    private fun key(peer: Long, name: String) = "transport:${room.id}:$peer:$name"
    private fun meta(peer: Long, name: String) = store.meta(key(peer, name))
    private fun put(peer: Long, name: String, value: Long) = store.setMeta(key(peer, name), value)
    private var incomingBatchIds = emptySet<String>()
    private val recordSlots = mutableMapOf<Long, MutableMap<Long, Int>>()
    fun supportsBatch(peer: Long): Boolean = synchronized(lock) {
        meta(peer, "supportedUntil") > now() && meta(peer, "batchFallbackUntil") <= now()
    }
    fun supportsCareDeltas(peer: Long): Boolean = synchronized(lock) {
        supportsCareDeltas(store, room.id, peer, now())
    }

    /** Fixed slots bound transport bookkeeping even when a location stream runs for years. */
    private fun record(peer: Long, id: String): String {
        val fingerprint = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)))
            .long.and(Long.MAX_VALUE).coerceAtLeast(1)
        val slots = recordSlots.getOrPut(peer) {
            (0 until RECORD_SLOTS).mapNotNull { index -> meta(peer, "record:$index:id")
                .takeIf { it != 0L }?.let { it to index } }.toMap().toMutableMap()
        }
        val existing = slots[fingerprint]?.takeIf { meta(peer, "record:$it:id") == fingerprint }
        val index = existing ?: (0 until RECORD_SLOTS).firstOrNull { it !in slots.values }
            ?: (0 until RECORD_SLOTS).minBy { meta(peer, "record:$it:lastUsed") }
        val slot = "record:$index"
        if (meta(peer, "$slot:id") != fingerprint) {
            listOf("batchAt", "batchAttempts", "batchProof", "careAt", "careAttempts", "replays", "individualAckUntil")
                .forEach { put(peer, "$slot:$it", 0) }
            slots.entries.removeAll { it.value == index }
            slots[fingerprint] = index
            put(peer, "$slot:id", fingerprint)
        }
        val usedAt = now().coerceAtLeast(1)
        if (meta(peer, "$slot:lastUsed") != usedAt) put(peer, "$slot:lastUsed", usedAt)
        return slot
    }

    fun canBatchAcknowledgements(peer: Long, packetIds: List<String> = emptyList()): Boolean = synchronized(lock) {
        store.transaction { packetIds.none { meta(peer, "${record(peer, it)}:individualAckUntil") > now() } }
    }

    /** A single compatibility receipt must not split the healthy receipts before it into singles. */
    fun batchableAcknowledgementCount(peer: Long, packetIds: List<String>): Int = synchronized(lock) {
        if (packetIds.isEmpty()) return@synchronized 0
        store.transaction {
            val firstIndividual = packetIds.indexOfFirst { meta(peer, "${record(peer, it)}:individualAckUntil") > now() }
            if (firstIndividual < 0) packetIds.size else firstIndividual.coerceAtLeast(1)
        }
    }

    /** Ordinary duplicates stay batched. Repeated individual retries recover a downgraded sender's ACK. */
    fun noteReplayedPacket(peer: Long, packetId: String) = synchronized(lock) {
        val slot = record(peer, packetId)
        if (packetId in incomingBatchIds) {
            put(peer, "$slot:replays", 0); put(peer, "$slot:individualAckUntil", 0)
        } else {
            val replays = (meta(peer, "$slot:replays") + 1).coerceAtMost(2)
            put(peer, "$slot:replays", replays)
            if (replays >= 2) put(peer, "$slot:individualAckUntil", now() + ACK_FALLBACK_MILLIS)
        }
    }

    /** Call only for an authenticated ACK of a genuinely attempted original. */
    fun noteAcknowledgedPacket(peer: Long, packetId: String) = synchronized(lock) {
        val slot = record(peer, packetId)
        if (meta(peer, "$slot:batchAt") > 0) {
            put(peer, "supportedUntil", now() + CAPABILITY_TTL_MILLIS)
            put(peer, "batchFallbackUntil", 0)
        }
        put(peer, "$slot:batchAt", 0); put(peer, "$slot:batchAttempts", 0)
        put(peer, "$slot:careAt", 0); put(peer, "$slot:careAttempts", 0)
    }

    fun shouldFallbackCarePacket(peer: Long, packetId: String): Boolean = synchronized(lock) {
        val slot = record(peer, packetId)
        val attemptedAt = meta(peer, "$slot:careAt")
        meta(peer, "$slot:careAttempts") >= MAX_BATCH_ATTEMPTS && attemptedAt > 0 &&
            (now() < attemptedAt || now() - attemptedAt >= 30_000)
    }

    fun noteCareDeltaFallback(peer: Long) = synchronized(lock) {
        put(peer, "careSupportedUntil", 0)
        put(peer, "care:probeWanted", 1)
        put(peer, "care:probeAt", 0)
        put(peer, "care:retryAt", now() + 30_000)
    }

    private fun originalId(text: String): String? = runCatching {
        val json = JSONObject(text)
        when {
            json.opt("v") == 3 && json.optString("type") == "chat" -> json.getJSONObject("message").getString("id")
            json.opt("v") == 4 && json.optString("type") != "care_ack" -> json.getString("id")
            else -> null
        }
    }.getOrNull()

    private fun probeReady(peer: Long, care: Boolean): Boolean {
        val prefix = if (care) "care" else "batch"
        val supported = if (care) supportsCareDeltas(peer) else supportsBatch(peer)
        val at = meta(peer, "$prefix:probeAt")
        return !supported && meta(peer, "$prefix:probeWanted") != 0L && meta(peer, "$prefix:retryAt") <= now() &&
            (at == 0L || now() < at || now() - at >= CAPABILITY_TTL_MILLIS)
    }

    private fun controlReady(peer: Long, care: Boolean, repliesOnly: Boolean): Boolean {
        val prefix = if (care) "care" else "batch"
        if (meta(peer, "$prefix:replyNonce") != 0L) return meta(peer, "$prefix:retryAt") <= now()
        return !repliesOnly && probeReady(peer, care)
    }

    private fun deferControlTurn(peer: Long): Boolean = synchronized(lock) {
        if (meta(peer, "controlTurn") != 0L &&
            (controlReady(peer, false, false) || controlReady(peer, true, false))) {
            deferredUntilMillis = minOf(deferredUntilMillis ?: Long.MAX_VALUE,
                maxOf(now() + GLOBAL_INTERVAL_MILLIS, meta(peer, "nextSend"), store.meta("transport:nextSend")))
            true
        } else false
    }

    /** Retry backlogs batch too; two unacknowledged batch attempts trigger a bounded legacy fallback. */
    fun send(member: FamilyChatMember, texts: List<String>, allowBatch: Boolean = true,
        beforeSend: (Int) -> Unit = {}, afterSend: (Int) -> Unit = {}): Int {
        if (texts.isEmpty()) return 0
        val peer = member.botId
        val ids = texts.map(::originalId)
        synchronized(lock) { store.transaction {
            if (allowBatch && supportsBatch(peer) && ids.filterNotNull().any {
                val slot = record(peer, it)
                meta(peer, "$slot:batchProof") == meta(peer, "batchProof") &&
                    meta(peer, "$slot:batchAttempts") >= MAX_BATCH_ATTEMPTS
            }) {
                put(peer, "supportedUntil", 0)
                put(peer, "careSupportedUntil", 0)
                put(peer, "batchFallbackUntil", now() + BATCH_FALLBACK_MILLIS)
                put(peer, "batch:probeAt", 0)
                put(peer, "batch:retryAt", now() + 30_000)
            }
            if (!supportsBatch(peer)) put(peer, "batch:probeWanted", 1)
            if (!supportsCareDeltas(peer)) put(peer, "care:probeWanted", 1)
        } }
        if (deferControlTurn(peer)) {
            if (ids.any { it != null }) synchronized(lock) { put(peer, "dataTurnUntil", now() + 5_000) }
            return 0
        }
        val acknowledgements = ids.all { it == null }
        if (acknowledgements && synchronized(lock) { meta(peer, "dataTurnUntil") > now() }) {
            deferredUntilMillis = minOf(deferredUntilMillis ?: Long.MAX_VALUE, now() + GLOBAL_INTERVAL_MILLIS)
            return 0
        }
        var count = 1
        var wire = texts.first()
        if (allowBatch && supportsBatch(member.botId)) {
            for (size in minOf(texts.size, FamilyTransportProtocol.MAX_PACKETS) downTo 2) {
                val candidate = runCatching { FamilyTransportProtocol.batch(texts.take(size)) }.getOrNull() ?: continue
                count = size; wire = candidate; break
            }
        }
        if (!reserve(peer)) {
            if (!acknowledgements) synchronized(lock) { put(peer, "dataTurnUntil", now() + 5_000) }
            return 0
        }
        checkActive()
        beforeSend(count)
        synchronized(lock) { store.transaction {
            ids.take(count).forEachIndexed { index, id -> if (id != null) {
                val slot = record(peer, id)
                val priorAttempts = if (meta(peer, "$slot:batchProof") == meta(peer, "batchProof"))
                    meta(peer, "$slot:batchAttempts") else 0
                put(peer, "$slot:batchAt", if (count > 1) now().coerceAtLeast(1) else 0)
                put(peer, "$slot:batchAttempts", if (count > 1) priorAttempts + 1 else 0)
                put(peer, "$slot:batchProof", meta(peer, "batchProof"))
                if (runCatching { JSONObject(texts[index]).optString("type") in setOf("care_sync", "care_delta") }.getOrDefault(false)) {
                    put(peer, "$slot:careAt", now().coerceAtLeast(1))
                    put(peer, "$slot:careAttempts", meta(peer, "$slot:careAttempts") + 1)
                }
            } }
            if (controlReady(peer, false, false) || controlReady(peer, true, false)) put(peer, "controlTurn", 1)
            if (!acknowledgements) put(peer, "dataTurnUntil", 0)
        } }
        client.sendToFamilyMember(member, wire, checkActive)
        afterSend(count)
        return count
    }

    fun sendLegacy(peer: Long, text: String, beforeSend: () -> Unit = {}, afterSend: () -> Unit = {}): Boolean {
        if (deferControlTurn(peer)) return false
        if (!reserve(peer)) return false
        checkActive(); beforeSend()
        client.send(peer.toString(), text)
        afterSend()
        return true
    }

    private fun reserve(peer: Long): Boolean = synchronized(lock) {
        val at = now()
        val deadline = maxOf(meta(peer, "nextSend"), store.meta("transport:nextSend"))
        // A changed wall clock cannot strand a persisted permit forever.
        if (deadline > at && deadline - at <= PEER_INTERVAL_MILLIS) {
            deferredUntilMillis = minOf(deferredUntilMillis ?: Long.MAX_VALUE, deadline)
            return@synchronized false
        }
        put(peer, "nextSend", at + PEER_INTERVAL_MILLIS)
        store.setMeta("transport:nextSend", at + GLOBAL_INTERVAL_MILLIS)
        true
    }

    /** Called inside the transaction which advances getUpdates offset. Null means an invalid wrapper. */
    fun incoming(update: JSONObject): List<JSONObject>? {
        incomingBatchIds = emptySet()
        val message = update.optJSONObject("message") ?: return listOf(update)
        val text = message.opt("text") as? String ?: return listOf(update)
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return listOf(update)
        if (json.optString("app") != "TalkingFamily" || json.opt("v") != 5) return listOf(update)
        data class Control(val sender: Long, val nonce: Long, val reply: Boolean, val care: Boolean)
        var control: Control? = null
        var batchSender: Long? = null
        val originals = runCatching {
            require(text.length <= 4096)
            val from = message.getJSONObject("from")
            val chat = message.getJSONObject("chat")
            val sender = FamilyChatValidation.botId(from.opt("id"))
            require(sender != ownBotId && room.members.any { it.botId == sender } && from.opt("is_bot") == true &&
                chat.optString("type") == "private" && FamilyChatValidation.botId(chat.opt("id")) == sender &&
                !message.has("forward_origin") && !message.has("forward_from") && !message.has("sender_chat"))
            when (json.getString("type")) {
                "capabilities", "care_capabilities" -> {
                    val care = json.getString("type") == "care_capabilities"
                    val feature = if (care) "delta" else "batch"
                    FamilyChatValidation.keys(json, setOf("app", "v", "type", feature, "nonce", "reply"))
                    require(json.opt(feature) == 1 && json.opt("reply") is Boolean)
                    val nonce = FamilyChatValidation.botId(json.opt("nonce"))
                    control = Control(sender, nonce, json.getBoolean("reply"), care)
                    emptyList()
                }
                "batch" -> {
                    val packets = FamilyTransportProtocol.unpack(json).map { original ->
                        JSONObject().put("message", JSONObject(message.toString()).put("text", original))
                    }
                    // Validate every original sender/room/parent-child edge before processing any packet.
                    require(packets.all { FamilyChatProtocol.receive(it, room, ownBotId) != null ||
                        FamilyCareProtocol.receive(it, room, ownBotId) != null })
                    batchSender = sender
                    packets
                }
                else -> null
            }
        }.getOrNull() ?: return null
        control?.let { (sender, nonce, reply, care) ->
            val prefix = if (care) "care" else "batch"
            if (reply && (meta(sender, "$prefix:challenge") != nonce ||
                now() - meta(sender, "$prefix:challengeAt") !in 0..CAPABILITY_TTL_MILLIS)) return null
            // Storage failures must roll back the enclosing receive transaction and its offset.
            if (!reply) put(sender, "$prefix:replyNonce", nonce)
            put(sender, if (care) "careSupportedUntil" else "supportedUntil", now() + CAPABILITY_TTL_MILLIS)
            if (!care) {
                put(sender, "batchFallbackUntil", 0)
                put(sender, "batchProof", meta(sender, "batchProof") + 1)
            }
        }
        batchSender?.let { sender ->
            // A fully validated batch is fresh evidence, including after an app upgrade or downgrade.
            put(sender, "supportedUntil", now() + CAPABILITY_TTL_MILLIS)
            put(sender, "batchFallbackUntil", 0)
            put(sender, "batchProof", meta(sender, "batchProof") + 1)
            incomingBatchIds = originals.mapNotNull { it.optJSONObject("message")?.optString("text")?.let(::originalId) }.toSet()
        }
        return originals
    }

    /** Responses are one-way. A legacy peer ignores the bounded probe and still receives v3/v4 data. */
    fun flushControls(repliesOnly: Boolean) {
        for (member in room.members.filter { it.botId != ownBotId }) {
            checkActive()
            val peer = member.botId
            // One control per peer per pass; batch negotiation precedes optional care deltas.
            val care = synchronized(lock) { when {
                controlReady(peer, false, repliesOnly) -> false
                controlReady(peer, true, repliesOnly) -> true
                else -> null
            } } ?: continue
            val prefix = if (care) "care" else "batch"
            val reply = synchronized(lock) { meta(peer, "$prefix:replyNonce") }
            val probe = reply == 0L
            if (!reserve(peer)) continue
            val nonce = if (reply != 0L) reply else now().coerceIn(1, 4_503_599_627_370_495L)
            synchronized(lock) { if (probe) {
                put(peer, "$prefix:challenge", nonce); put(peer, "$prefix:challengeAt", now())
            } }
            try {
                val text = if (care) FamilyTransportProtocol.careCapability(nonce, reply != 0L)
                    else FamilyTransportProtocol.capability(nonce, reply != 0L)
                client.sendToFamilyMember(member, text, checkActive)
                synchronized(lock) {
                    if (reply != 0L) put(peer, "$prefix:replyNonce", 0)
                    if (probe) put(peer, "$prefix:probeAt", now().coerceAtLeast(1))
                    put(peer, "$prefix:retryAt", 0); put(peer, "controlTurn", 0)
                }
            } catch (error: TelegramException) {
                if (error.errorCode in setOf(401, 404, 409, 429)) throw error
                synchronized(lock) { put(peer, "$prefix:retryAt", now() + 15_000); put(peer, "controlTurn", 0) }
            }
        }
    }

    companion object {
        const val PEER_INTERVAL_MILLIS = 1_100L
        private const val GLOBAL_INTERVAL_MILLIS = 100L
        private const val CAPABILITY_TTL_MILLIS = 6 * 60 * 60 * 1000L
        private const val MAX_BATCH_ATTEMPTS = 2
        private const val BATCH_FALLBACK_MILLIS = 3 * 60 * 1000L
        private const val ACK_FALLBACK_MILLIS = 2 * 60 * 1000L
        private const val RECORD_SLOTS = 128
        fun supportsCareDeltas(store: TelegramExchangeStore, roomId: String, peer: Long,
            nowMillis: Long = System.currentTimeMillis()): Boolean =
            store.meta("transport:$roomId:$peer:careSupportedUntil") > nowMillis
    }
}
