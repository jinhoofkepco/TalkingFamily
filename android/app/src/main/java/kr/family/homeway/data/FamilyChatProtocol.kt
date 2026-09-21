package kr.family.homeway.data

import org.json.JSONObject

/** Chat-only version 3. The authenticated Telegram sender, never a payload label, owns the message. */
object FamilyChatProtocol {
    data class Packet(val message: FamilyChatMessage? = null, val receipt: FamilyChatReceipt? = null)
    fun envelope(type: String) = JSONObject().put("app", "TalkingFamily").put("v", 3).put("type", type)
    fun message(message: FamilyChatMessage): String = envelope("chat").put("message", FamilyChatValidation.message(message).json()).toString()
    fun receipt(receipt: FamilyChatReceipt): String = envelope("chat_ack").put("roomId", receipt.roomId)
        .put("id", receipt.messageId).put("digest", receipt.digest).toString()

    fun receive(update: JSONObject, room: FamilyChatRoom, ownBotId: Long): Packet? = runCatching {
        val telegramMessage = update.optJSONObject("message") ?: return null
        val from = telegramMessage.optJSONObject("from") ?: return null
        val chat = telegramMessage.optJSONObject("chat") ?: return null
        val senderId = FamilyChatValidation.botId(from.opt("id"))
        if (senderId == ownBotId || room.members.none { it.botId == senderId } ||
            from.opt("is_bot") != true || FamilyChatValidation.botId(chat.opt("id")) != senderId ||
            chat.optString("type") != "private" || telegramMessage.has("forward_origin") ||
            telegramMessage.has("forward_from") || telegramMessage.has("sender_chat")) return null
        val text = telegramMessage.opt("text") as? String ?: return null
        if (text.length > 4096) return null
        val packet = JSONObject(text)
        if (packet.optString("app") != "TalkingFamily" || packet.opt("v") != 3) return null
        when (packet.optString("type")) {
            "chat" -> {
                FamilyChatValidation.keys(packet, setOf("app", "v", "type", "message"))
                val message = FamilyChatMessage.parse(packet.getJSONObject("message"))
                if (message.roomId != room.id || message.senderId != senderId) return null
                Packet(message = message)
            }
            "chat_ack" -> {
                FamilyChatValidation.keys(packet, setOf("app", "v", "type", "roomId", "id", "digest"))
                val roomId = FamilyChatValidation.identifier(FamilyChatValidation.string(packet, "roomId"))
                val id = FamilyChatValidation.identifier(FamilyChatValidation.string(packet, "id"))
                val digest = FamilyChatValidation.string(packet, "digest")
                if (roomId != room.id || !Regex("^[0-9a-f]{64}$").matches(digest)) return null
                Packet(receipt = FamilyChatReceipt(roomId, id, senderId, digest))
            }
            else -> null
        }
    }.getOrNull()
}
