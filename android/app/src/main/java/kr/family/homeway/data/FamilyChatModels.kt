package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class FamilyChatMemberDraft(val username: String, val displayName: String, val relationship: String)

data class FamilyChatMember(val botId: Long, val username: String, val displayName: String, val relationship: String) {
    fun json() = JSONObject().put("botId", botId).put("username", username)
        .put("displayName", displayName).put("relationship", relationship)
}

data class FamilyChatRoom(val id: String, val title: String, val members: List<FamilyChatMember>) {
    fun json() = JSONObject().put("id", id).put("title", title).put("members", JSONArray(members.map { it.json() }))
    fun toCode(): String = "TFROOM1:" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(FamilyChatValidation.room(this).json().toString().toByteArray(Charsets.UTF_8))
    fun encode(): String = toCode()
    companion object {
        fun create(title: String, members: List<FamilyChatMember>, id: String = UUID.randomUUID().toString()): FamilyChatRoom =
            FamilyChatValidation.room(FamilyChatRoom(id, title, members))
        fun decode(code: String): FamilyChatRoom = fromCode(code)
        fun parse(json: JSONObject): FamilyChatRoom {
            FamilyChatValidation.keys(json, setOf("id", "title", "members"))
            val rows = json.opt("members") as? JSONArray ?: error("가족 명단을 확인해 주세요.")
            require(rows.length() in 2..8) { "가족방에는 2~8명이 함께할 수 있어요." }
            val members = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                FamilyChatValidation.keys(row, setOf("botId", "username", "displayName", "relationship"))
                FamilyChatMember(FamilyChatValidation.botId(row.opt("botId")),
                    FamilyChatValidation.string(row, "username"), FamilyChatValidation.string(row, "displayName"),
                    FamilyChatValidation.string(row, "relationship"))
            }
            return FamilyChatValidation.room(FamilyChatRoom(FamilyChatValidation.string(json, "id"),
                FamilyChatValidation.string(json, "title"), members))
        }
        fun fromCode(raw: String): FamilyChatRoom {
            val code = raw.trim()
            require(code.length <= 8192 && code.startsWith("TFROOM1:")) { "가족방 코드를 확인해 주세요." }
            val encoded = code.removePrefix("TFROOM1:")
            require(encoded.isNotBlank() && Regex("^[A-Za-z0-9_-]+$").matches(encoded)) { "가족방 코드를 확인해 주세요." }
            return try {
                val bytes = Base64.getUrlDecoder().decode(encoded)
                require(bytes.size <= 6000)
                val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                parse(JSONObject(text))
            } catch (_: Exception) { throw IllegalArgumentException("가족방 코드를 확인해 주세요.") }
        }
    }
}

/** Delivery counts are local projections and are never accepted from the wire or included in the digest. */
data class FamilyChatMessage(val id: String, val roomId: String, val senderId: Long, val text: String,
    val createdAt: String, val recipientCount: Int = 0, val deliveredCount: Int = 0) {
    fun json() = JSONObject().put("id", id).put("roomId", roomId).put("senderId", senderId)
        .put("text", text).put("createdAt", createdAt)
    val digest: String get() = FamilyChatValidation.digest(JSONArray().put(id).put(roomId).put(senderId).put(text).put(createdAt).toString())
    companion object {
        fun parse(json: JSONObject): FamilyChatMessage {
            FamilyChatValidation.keys(json, setOf("id", "roomId", "senderId", "text", "createdAt"))
            return FamilyChatValidation.message(FamilyChatMessage(FamilyChatValidation.string(json, "id"),
                FamilyChatValidation.string(json, "roomId"), FamilyChatValidation.botId(json.opt("senderId")),
                FamilyChatValidation.string(json, "text"), FamilyChatValidation.string(json, "createdAt")))
        }
    }
}

data class FamilyChatDelivery(val roomId: String, val messageId: String, val peerId: Long, val sentAt: Long)
data class FamilyChatReceipt(val roomId: String, val messageId: String, val peerId: Long, val digest: String)
data class FamilyChatCursor(val createdAt: String, val id: String)
data class FamilyChatPage(val messages: List<FamilyChatMessage>, val next: FamilyChatCursor?)

object FamilyChatValidation {
    val relationships = setOf("mother", "father", "son", "daughter", "family")
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    fun identifier(value: String): String {
        require(uuid.matches(value)) { "가족방 기록을 확인해 주세요." }
        return UUID.fromString(value).toString()
    }
    fun botId(value: Any?): Long {
        require(value is Int || value is Long) { "가족 봇 정보를 확인해 주세요." }
        return (value as Number).toLong().also { require(it in 1..4_503_599_627_370_495L) { "가족 봇 정보를 확인해 주세요." } }
    }
    fun room(room: FamilyChatRoom): FamilyChatRoom {
        require(room.members.size in 2..8) { "가족방에는 2~8명이 함께할 수 있어요." }
        val members = room.members.map {
            botId(it.botId)
            require(it.relationship in relationships) { "가족 관계를 선택해 주세요." }
            it.copy(username = TelegramClient.normalizePeerUsername(it.username), displayName = label(it.displayName, 20))
        }
        require(members.map { it.botId }.distinct().size == members.size &&
            members.map { it.username.lowercase() }.distinct().size == members.size) { "같은 가족 봇이 두 번 들어 있어요." }
        return room.copy(id = identifier(room.id), title = label(room.title, 40), members = members)
    }
    fun message(message: FamilyChatMessage): FamilyChatMessage {
        botId(message.senderId)
        require(message.text.trim().isNotEmpty() && message.text.length <= 1500 && !message.text.contains('\u0000') &&
            StandardCharsets.UTF_8.newEncoder().canEncode(message.text)) {
            "메시지는 1~1,500자로 입력해 주세요."
        }
        require(message.createdAt.length <= 40) { "메시지 시간을 확인해 주세요." }
        val time = runCatching { Instant.parse(message.createdAt) }.getOrElse { throw IllegalArgumentException("메시지 시간을 확인해 주세요.") }
        require(time >= Instant.parse("2000-01-01T00:00:00Z") && time < Instant.parse("2100-01-01T00:00:00Z")) { "메시지 시간을 확인해 주세요." }
        return message.copy(id = identifier(message.id), roomId = identifier(message.roomId), text = message.text.trim(), createdAt = time.toString())
    }
    internal fun keys(json: JSONObject, expected: Set<String>) {
        require(json.keys().asSequence().toSet() == expected) { "가족방 형식을 확인해 주세요." }
    }
    internal fun string(json: JSONObject, key: String): String = (json.opt(key) as? String)
        ?: throw IllegalArgumentException("가족방 형식을 확인해 주세요.")
    private fun label(value: String, max: Int): String = value.trim().also {
        require(it.isNotBlank() && it.length <= max && it.none { char -> char.isISOControl() } &&
            StandardCharsets.UTF_8.newEncoder().canEncode(it)) { "이름은 1~${max}자로 입력해 주세요." }
    }
    internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
