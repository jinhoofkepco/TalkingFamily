package kr.family.homeway.data

import org.json.JSONObject
import java.time.Instant

data class FamilyEvent(
    val id: String,
    val kind: String,
    val payload: JSONObject,
    val sender: String,
    val createdAt: String,
    val delivery: String,
    val deliveryError: String? = null,
    // Presentation fields supplied by the authenticated family-room store, never v2 wire fields.
    val senderId: Long? = null,
    val senderName: String? = null,
    val roomId: String? = null,
    val deliveredTo: Int? = null,
    val recipientCount: Int? = null,
) {
    fun json() = JSONObject().put("id", id).put("kind", kind).put("payload", payload)
        .put("sender", sender).put("createdAt", createdAt).put("delivery", delivery)
        .apply { deliveryError?.let { put("deliveryError", it) } }
    val measuredAt: String get() = when (kind) {
        "location" -> payload.optString("capturedAt", createdAt)
        "vertical" -> payload.optString("measuredAt", createdAt)
        else -> createdAt
    }
    companion object {
        fun parse(j: JSONObject) = FamilyEvent(j.getString("id"), j.getString("kind"),
            j.optJSONObject("payload") ?: JSONObject(), j.getString("sender"),
            j.optString("createdAt", Instant.now().toString()), j.optString("delivery", "pending"),
            j.optString("deliveryError").takeIf { it.isNotBlank() })
    }
}
data class Redemption(val id: String, val reward: String, val cost: Int, val status: String)
data class Reward(val id: String, val name: String, val cost: Int)
data class FamilySnapshot(
    val events: List<FamilyEvent> = emptyList(), val stickerBalance: Int = 0,
    val redemptions: List<Redemption> = emptyList(), val sharingEnabled: Boolean = false,
    val transport: String = "unconfigured", val pushConfigured: Boolean = false,
    val rewards: List<Reward> = emptyList(),
) {
    companion object {
        fun parse(j: JSONObject): FamilySnapshot {
            val events = j.optJSONArray("events")
            val redemptions = j.optJSONArray("redemptions")
            val rewards = j.optJSONArray("rewards")
            val knownEvents = (0 until (events?.length() ?: 0)).map { FamilyEvent.parse(events!!.getJSONObject(it)) }.toMutableList()
            listOf("latestLocation", "latestHeartbeat").forEach { key ->
                j.optJSONObject(key)?.let { latest ->
                    val event = FamilyEvent.parse(latest)
                    if(knownEvents.none { it.id == event.id }) knownEvents.add(event)
                }
            }
            return FamilySnapshot(
                knownEvents,
                j.optInt("stickerBalance"),
                (0 until (redemptions?.length() ?: 0)).map {
                    val r = redemptions!!.getJSONObject(it)
                    Redemption(r.getString("id"), r.getString("reward"), r.getInt("cost"), r.getString("status"))
                }, j.optBoolean("sharingEnabled"), j.optString("transport", "unconfigured"), j.optBoolean("pushConfigured"),
                (0 until (rewards?.length() ?: 0)).map {
                    val reward = rewards!!.getJSONObject(it)
                    Reward(reward.getString("id"), reward.getString("name"), reward.getInt("cost"))
                }
            )
        }
    }
}
