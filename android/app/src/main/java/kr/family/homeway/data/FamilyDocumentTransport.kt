package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A rejected document never exposes its private caption, URL, or payload in diagnostics. */
class InvalidFamilyDocumentException : Exception("가족 기록 파일의 형식을 확인하지 못했어요.")

data class PreparedFamilyDocument(val fileId: String, val actorId: Long, val receiverId: Long,
    val roomId: String, val transferId: String, val count: Int, val rawBytes: Int, val compressedBytes: Int,
    val sha256: String, val telegramMessage: JSONObject)

internal data class EncodedFamilyDocument(val caption: String, val bytes: ByteArray, val count: Int)

/** Transport only: every original is still validated, saved, and acknowledged by its existing protocol. */
internal object FamilyDocumentProtocol {
    const val MAX_PACKETS = 128
    const val MAX_RAW_BYTES = 512 * 1024
    const val MAX_COMPRESSED_BYTES = 256 * 1024
    const val MIN_PACKETS = 4
    const val MIN_RAW_BYTES = 8 * 1024
    private val manifestKeys = setOf("app", "v", "type", "roomId", "actorId", "receiverId", "transferId",
        "codec", "count", "rawBytes", "compressedBytes", "sha256")

    fun encode(texts: List<String>, roomId: String, actorId: Long, receiverId: Long): EncodedFamilyDocument? {
        var rawSize = 2
        val prefix = texts.take(MAX_PACKETS).takeWhile { text ->
            require(text.length <= 4096)
            val addition = JSONObject.quote(text).toByteArray(Charsets.UTF_8).size + 1
            if (rawSize + addition > MAX_RAW_BYTES) false else { rawSize += addition; true }
        }
        if (prefix.size < MIN_PACKETS || prefix.size <= FamilyTransportProtocol.MAX_PACKETS && rawSize < MIN_RAW_BYTES) return null
        var count = prefix.size
        while (count >= MIN_PACKETS) {
            val raw = JSONArray(prefix.take(count)).toString().toByteArray(Charsets.UTF_8)
            val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
            if (compressed.size <= MAX_COMPRESSED_BYTES) {
                val manifest = JSONObject().put("app", "TalkingFamily").put("v", 5).put("type", "file_batch")
                    .put("roomId", roomId).put("actorId", actorId).put("receiverId", receiverId)
                    .put("transferId", UUID.randomUUID().toString()).put("codec", "gzip-json")
                    .put("count", count).put("rawBytes", raw.size).put("compressedBytes", compressed.size)
                    .put("sha256", hash(compressed)).toString()
                require(manifest.length <= 1024)
                return EncodedFamilyDocument(manifest, compressed, count)
            }
            // At most six compression attempts, rather than recompressing a large queue 128 times.
            count /= 2
        }
        return null
    }

    /** No HTTP or storage access. Unrelated or invalid documents are ignored before downloading. */
    fun prepare(update: JSONObject, room: FamilyChatRoom, ownBotId: Long): PreparedFamilyDocument? = runCatching {
        val message = update.optJSONObject("message") ?: return null
        val document = message.optJSONObject("document") ?: return null
        val caption = message.opt("caption") as? String ?: return null
        require(caption.length <= 1024)
        val manifest = JSONObject(caption)
        require(manifest.optString("app") == "TalkingFamily" && manifest.opt("v") == 5 && manifest.optString("type") == "file_batch")
        FamilyChatValidation.keys(manifest, manifestKeys)
        val from = message.getJSONObject("from")
        val chat = message.getJSONObject("chat")
        val actor = FamilyChatValidation.botId(from.opt("id"))
        require(actor != ownBotId && room.members.any { it.botId == actor } && from.opt("is_bot") == true &&
            chat.optString("type") == "private" && FamilyChatValidation.botId(chat.opt("id")) == actor &&
            !message.has("forward_origin") && !message.has("forward_from") && !message.has("sender_chat"))
        require(manifest.getString("roomId") == room.id && FamilyChatValidation.botId(manifest.opt("actorId")) == actor &&
            FamilyChatValidation.botId(manifest.opt("receiverId")) == ownBotId && manifest.getString("codec") == "gzip-json")
        val count = boundedInt(manifest.opt("count"), MIN_PACKETS, MAX_PACKETS)
        val rawBytes = boundedInt(manifest.opt("rawBytes"), 1, MAX_RAW_BYTES)
        val compressedBytes = boundedInt(manifest.opt("compressedBytes"), 1, MAX_COMPRESSED_BYTES)
        if (document.has("file_size")) require(boundedInt(document.opt("file_size"), 1, MAX_COMPRESSED_BYTES) == compressedBytes)
        PreparedFamilyDocument(TelegramClient.validFileId(document.getString("file_id")), actor, ownBotId, room.id,
            FamilyChatValidation.identifier(manifest.getString("transferId")), count, rawBytes, compressedBytes,
            FamilyCareValidation.hash(manifest.getString("sha256")), JSONObject(message.toString()))
    }.getOrNull()

    fun decode(prepared: PreparedFamilyDocument, bytes: ByteArray, room: FamilyChatRoom, ownBotId: Long): List<JSONObject> {
        try {
            require(prepared.roomId == room.id && prepared.receiverId == ownBotId &&
                bytes.size == prepared.compressedBytes && bytes.size <= MAX_COMPRESSED_BYTES && hash(bytes) == prepared.sha256)
            val output = ByteArrayOutputStream()
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= prepared.rawBytes && output.size() + count <= MAX_RAW_BYTES)
                    output.write(buffer, 0, count)
                }
            }
            require(output.size() == prepared.rawBytes)
            val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
            val packets = JSONArray(raw)
            require(packets.length() == prepared.count && packets.length() in MIN_PACKETS..MAX_PACKETS)
            val originals = (0 until packets.length()).map { index ->
                val text = packets.opt(index) as? String ?: error("Invalid packet")
                require(text.length <= 4096)
                JSONObject().put("message", JSONObject(prepared.telegramMessage.toString()).apply {
                    remove("document"); remove("caption"); put("text", text)
                })
            }
            require(originals.all { FamilyChatProtocol.receive(it, room, ownBotId) != null ||
                FamilyCareProtocol.receive(it, room, ownBotId) != null })
            return originals
        } catch (_: Exception) { throw InvalidFamilyDocumentException() }
    }

    private fun boundedInt(value: Any?, min: Int, max: Int): Int {
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it in min.toLong()..max.toLong()) }.toInt()
    }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
