package kr.family.homeway.data

import org.json.JSONObject
import java.util.UUID

internal object TelegramProtocol {
    data class Packet(val type: String, val event: FamilyEvent? = null, val id: String? = null)
    fun envelope(type: String) = JSONObject().put("app", "TalkingFamily").put("v", 2).put("type", type)
    fun event(event: FamilyEvent): String = envelope("event").put("event", event.json()).toString()
    fun receive(update: JSONObject, peerId: Long, peerRole: String): Packet? = runCatching {
        val message = update.optJSONObject("message") ?: return null
        val sender = message.optJSONObject("from") ?: return null
        val chat = message.optJSONObject("chat") ?: return null
        if (peerId <= 0 || sender.optLong("id") != peerId || !sender.optBoolean("is_bot") ||
            chat.optLong("id") != peerId || chat.optString("type") != "private" || message.has("forward_origin")) return null
        val text = message.optString("text")
        if (text.length > 4096) return null
        val packet = JSONObject(text)
        if (packet.optString("app") != "TalkingFamily" || packet.opt("v") != 2) return null
        when (packet.optString("type")) {
            "event" -> {
                val rawEvent = packet.getJSONObject("event")
                // The general cache parser supplies a current-time fallback; wire events must not.
                // Otherwise replaying a malformed packet changes its digest on each receive.
                if (rawEvent.opt("createdAt") !is String) return null
                val event = FamilyEvent.parse(rawEvent).copy(delivery = "relayed", deliveryError = null)
                if (event.sender != peerRole) return null
                Packet("event", TelegramLedger.validate(event))
            }
            "ack" -> {
                val id = packet.getString("id")
                val canonical = UUID.fromString(id).toString()
                if (canonical != id.lowercase()) return null
                Packet("ack", id = canonical)
            }
            else -> null
        }
    }.getOrNull()
}
