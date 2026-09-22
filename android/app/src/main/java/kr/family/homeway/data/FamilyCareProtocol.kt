package kr.family.homeway.data

import org.json.JSONObject
import java.util.UUID

/** Version 4 care is exchanged only along a pinned parent-child edge, never with siblings. */
object FamilyCareProtocol {
    data class Packet(val id: String, val type: String, val roomId: String, val childId: Long,
        val actorId: Long, val body: JSONObject, val digest: String)

    fun envelope(room: FamilyChatRoom, ownBotId: Long, childId: Long, type: String, body: JSONObject,
        id: String = UUID.randomUUID().toString()): JSONObject = JSONObject().put("app", "TalkingFamily")
        .put("v", 4).put("type", type).put("id", id).put("roomId", room.id)
        .put("rosterHash", FamilyCareValidation.rosterHash(room)).put("childId", childId)
        .put("actorId", ownBotId).put("body", body)

    fun outgoing(room: FamilyChatRoom, ownBotId: Long, childId: Long, peerId: Long, type: String,
        body: JSONObject, id: String = UUID.randomUUID().toString()): FamilyCareOutgoing {
        require(edge(room, ownBotId, peerId, childId)) { "이 가족에게는 위치와 칭찬판을 보낼 수 없어요." }
        val packet = envelope(room, ownBotId, childId, type, body, id)
        val text = packet.toString()
        require(text.length <= 4096) { "가족 기록을 짧게 나누어 보내 주세요." }
        return FamilyCareOutgoing(room.id, id, childId, peerId, text, FamilyCareValidation.digest(packet))
    }

    fun receipt(room: FamilyChatRoom, ownBotId: Long, receipt: FamilyCareReceipt): String =
        envelope(room, ownBotId, receipt.childId, "care_ack", JSONObject().put("digest", receipt.digest), receipt.packetId).toString()

    fun receive(update: JSONObject, room: FamilyChatRoom, ownBotId: Long): Packet? = runCatching {
        val telegram = update.optJSONObject("message") ?: return null
        val from = telegram.optJSONObject("from") ?: return null
        val chat = telegram.optJSONObject("chat") ?: return null
        val sender = FamilyChatValidation.botId(from.opt("id"))
        if (from.opt("is_bot") != true || FamilyChatValidation.botId(chat.opt("id")) != sender ||
            chat.optString("type") != "private" || telegram.has("forward_origin") || telegram.has("forward_from") ||
            telegram.has("sender_chat")) return null
        val text = telegram.opt("text") as? String ?: return null
        if (text.length > 4096) return null
        val json = JSONObject(text)
        if (json.optString("app") != "TalkingFamily" || json.opt("v") != 4) return null
        FamilyChatValidation.keys(json, setOf("app", "v", "type", "id", "roomId", "rosterHash", "childId", "actorId", "body"))
        val child = FamilyChatValidation.botId(json.opt("childId"))
        if (json.optString("roomId") != room.id || json.optString("rosterHash") != FamilyCareValidation.rosterHash(room) ||
            FamilyChatValidation.botId(json.opt("actorId")) != sender || !edge(room, ownBotId, sender, child)) return null
        val type = json.getString("type")
        when (type) {
            "command", "sync_request" -> if (ownBotId != child || !FamilyCareValidation.isParent(room, sender)) return null
            "snapshot_chunk", "outcome", "child_event" -> if (sender != child || !FamilyCareValidation.isParent(room, ownBotId)) return null
            "care_ack" -> Unit
            else -> return null
        }
        Packet(FamilyChatValidation.identifier(json.getString("id")), type, room.id, child, sender,
            json.getJSONObject("body"), FamilyCareValidation.digest(json))
    }.getOrNull()

    private fun edge(room: FamilyChatRoom, own: Long, peer: Long, child: Long): Boolean =
        FamilyCareValidation.isChild(room, child) && ((own == child && FamilyCareValidation.isParent(room, peer)) ||
            (peer == child && FamilyCareValidation.isParent(room, own)))
}
