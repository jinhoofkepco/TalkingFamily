package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
    fun supportsBatch(peer: Long): Boolean = synchronized(lock) { meta(peer, "supportedUntil") > now() }
    fun canBatchAcknowledgements(peer: Long): Boolean = synchronized(lock) { meta(peer, "individualAcksUntil") <= now() }
    /** A missing batch ACK may mean the other app was downgraded. Replayed originals get legacy ACKs. */
    fun noteReplayedPacket(peer: Long) = synchronized(lock) { put(peer, "individualAcksUntil", now() + CAPABILITY_TTL_MILLIS) }

    /** A retry of an unacknowledged batch uses original individual envelopes, including after downgrade. */
    fun send(member: FamilyChatMember, texts: List<String>, allowBatch: Boolean = true,
        beforeSend: (Int) -> Unit = {}, afterSend: (Int) -> Unit = {}): Int {
        if (texts.isEmpty()) return 0
        synchronized(lock) { if (!supportsBatch(member.botId)) put(member.botId, "probeWanted", 1) }
        var count = 1
        var wire = texts.first()
        if (allowBatch && supportsBatch(member.botId)) {
            for (size in minOf(texts.size, FamilyTransportProtocol.MAX_PACKETS) downTo 2) {
                val candidate = runCatching { FamilyTransportProtocol.batch(texts.take(size)) }.getOrNull() ?: continue
                count = size; wire = candidate; break
            }
        }
        if (!reserve(member.botId)) return 0
        checkActive()
        beforeSend(count)
        client.sendToFamilyMember(member, wire, checkActive)
        afterSend(count)
        return count
    }

    fun sendLegacy(peer: Long, text: String, beforeSend: () -> Unit = {}, afterSend: () -> Unit = {}): Boolean {
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
        val message = update.optJSONObject("message") ?: return listOf(update)
        val text = message.opt("text") as? String ?: return listOf(update)
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return listOf(update)
        if (json.optString("app") != "TalkingFamily" || json.opt("v") != 5) return listOf(update)
        var control: Triple<Long, Long, Boolean>? = null
        val originals = runCatching {
            require(text.length <= 4096)
            val from = message.getJSONObject("from")
            val chat = message.getJSONObject("chat")
            val sender = FamilyChatValidation.botId(from.opt("id"))
            require(sender != ownBotId && room.members.any { it.botId == sender } && from.opt("is_bot") == true &&
                chat.optString("type") == "private" && FamilyChatValidation.botId(chat.opt("id")) == sender &&
                !message.has("forward_origin") && !message.has("forward_from") && !message.has("sender_chat"))
            when (json.getString("type")) {
                "capabilities" -> {
                    FamilyChatValidation.keys(json, setOf("app", "v", "type", "batch", "nonce", "reply"))
                    require(json.opt("batch") == 1 && json.opt("reply") is Boolean)
                    val nonce = FamilyChatValidation.botId(json.opt("nonce"))
                    control = Triple(sender, nonce, json.getBoolean("reply"))
                    emptyList()
                }
                "batch" -> {
                    val packets = FamilyTransportProtocol.unpack(json).map { original ->
                        JSONObject().put("message", JSONObject(message.toString()).put("text", original))
                    }
                    // Validate every original sender/room/parent-child edge before processing any packet.
                    require(packets.all { FamilyChatProtocol.receive(it, room, ownBotId) != null ||
                        FamilyCareProtocol.receive(it, room, ownBotId) != null })
                    packets
                }
                else -> null
            }
        }.getOrNull() ?: return null
        control?.let { (sender, nonce, reply) ->
            if (reply && (meta(sender, "challenge") != nonce || now() - meta(sender, "probeAt") !in 0..CAPABILITY_TTL_MILLIS)) return null
            // Storage failures must roll back the enclosing receive transaction and its offset.
            if (!reply) put(sender, "replyNonce", nonce)
            put(sender, "supportedUntil", now() + CAPABILITY_TTL_MILLIS)
            put(sender, "individualAcksUntil", 0)
        }
        return originals
    }

    /** Responses are one-way. A legacy peer ignores the bounded probe and still receives v3/v4 data. */
    fun flushControls(repliesOnly: Boolean) {
        for (member in room.members.filter { it.botId != ownBotId }) {
            checkActive()
            val peer = member.botId
            val reply = synchronized(lock) { meta(peer, "replyNonce") }
            val probe = synchronized(lock) { !repliesOnly && reply == 0L && !supportsBatch(peer) && meta(peer, "probeWanted") != 0L &&
                (meta(peer, "probeAt") == 0L || now() - meta(peer, "probeAt") >= CAPABILITY_TTL_MILLIS) }
            if (reply == 0L && !probe) continue
            if (!reserve(peer)) continue
            val nonce = if (reply != 0L) reply else now().coerceIn(1, 4_503_599_627_370_495L)
            synchronized(lock) { if (probe) { put(peer, "challenge", nonce); put(peer, "probeAt", now()) } }
            try {
                client.sendToFamilyMember(member, FamilyTransportProtocol.capability(nonce, reply != 0L), checkActive)
                if (reply != 0L) synchronized(lock) { put(peer, "replyNonce", 0) }
            } catch (error: TelegramException) {
                if (error.errorCode in setOf(401, 404, 409, 429)) throw error
                if (reply != 0L) synchronized(lock) { put(peer, "replyNonce", 0) }
            }
        }
    }

    companion object {
        const val PEER_INTERVAL_MILLIS = 1_100L
        private const val GLOBAL_INTERVAL_MILLIS = 100L
        private const val CAPABILITY_TTL_MILLIS = 6 * 60 * 60 * 1000L
    }
}
