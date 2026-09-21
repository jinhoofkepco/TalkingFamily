package kr.family.homeway.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Explicit, isolated, on-device examples. Never accesses sensors or the relay. */
class DemoStore(context: Context) {
    private val prefs = context.getSharedPreferences("homeway_demo", Context.MODE_PRIVATE)
    fun reset() { prefs.edit().clear().commit(); read() }
    fun read(): FamilySnapshot {
        val raw = prefs.getString("state", null)
        val json = if (raw == null) DemoState.initial() else DemoState.migrate(JSONObject(raw))
        if (raw != json.toString()) save(json)
        return FamilySnapshot.parse(json)
    }
    fun apply(kind: String, payload: JSONObject, sender: String): FamilySnapshot {
        read()
        val json = DemoState.apply(JSONObject(prefs.getString("state", "{}")!!), kind, payload, sender)
        save(json)
        return FamilySnapshot.parse(json)
    }
    private fun save(j: JSONObject) { prefs.edit().putString("state", j.toString()).commit() }
}

/** Pure transition logic keeps demo and live catalog rules aligned and testable. */
internal object DemoState {
    private fun sampleRewards() = JSONArray()
        .put(JSONObject().put("id", "00000000-0000-4000-8000-000000000001").put("name", "아이스크림 먹기").put("cost", 5))
        .put(JSONObject().put("id", "00000000-0000-4000-8000-000000000002").put("name", "영화 보기").put("cost", 10))

    fun migrate(json: JSONObject): JSONObject = JSONObject(json.toString()).apply {
        // Preserve deliberately emptied catalogs; only pre-catalog demo state gets samples.
        if (!has("rewards")) put("rewards", sampleRewards())
    }

    fun initial(now: Instant = Instant.now()): JSONObject {
        fun event(kind: String, payload: JSONObject, sender: String, secondsAgo: Long): JSONObject {
            val at = now.minusSeconds(secondsAgo).toString()
            if(kind=="location") payload.put("capturedAt", at)
            if(kind=="vertical") payload.put("measuredAt", at).put("confidence", "estimated")
            return FamilyEvent(UUID.randomUUID().toString(), kind, payload, sender, at, "relayed").json()
        }
        val events = JSONArray()
            .put(event("chat", JSONObject().put("text", "오늘도 조심히 와. 도착하면 알려 줘 💛"), "guardian", 1800))
            .put(event("chat", JSONObject().put("text", "응! 지금 출발했어 😊"), "child", 1700))
            .put(event("location", JSONObject().put("latitude",37.5665).put("longitude",126.9780).put("accuracy",18).put("source","automatic"), "child", 1000))
            .put(event("vertical", JSONObject().put("phase","ascent_started").put("relativeMeters",0), "child", 920))
            .put(event("vertical", JSONObject().put("phase","ascent_finished").put("relativeMeters",6.3), "child", 872))
            .put(event("vertical", JSONObject().put("phase","descent_started").put("relativeMeters",0), "child", 380))
            .put(event("vertical", JSONObject().put("phase","descent_finished").put("relativeMeters",-6.1), "child", 337))
            .put(event("location", JSONObject().put("latitude",37.5670).put("longitude",126.9785).put("accuracy",12).put("source","manual"), "child", 45))
            .put(event("sticker_award", JSONObject().put("count",1).put("reason","약속을 잘 지켰어요"), "guardian", 30))
        val json = JSONObject().put("events", events).put("stickerBalance",8).put("redemptions",JSONArray())
            .put("rewards",sampleRewards()).put("sharingEnabled",true).put("transport","demo").put("pushConfigured",false)
        return json
    }

    fun apply(current: JSONObject, kind: String, input: JSONObject, sender: String): JSONObject {
        require(sender in setOf("guardian", "child")) { "사용자를 확인해 주세요." }
        val guardianKinds = setOf("sticker_award", "sticker_redeem_approve", "reward_upsert", "reward_delete")
        val childKinds = setOf("sticker_redeem_request", "location", "vertical", "sharing_status", "heartbeat")
        require(kind == "chat" || kind in guardianKinds || kind in childKinds) { "지원하지 않는 요청이에요." }
        require(kind !in guardianKinds || sender == "guardian") { "보호자만 바꿀 수 있어요." }
        require(kind !in childKinds || sender == "child") { "자녀 화면에서 사용할 수 있어요." }
        val json = migrate(current)
        val payload = JSONObject(input.toString())
        val events = json.getJSONArray("events")
        val id = UUID.randomUUID().toString()
        val redemptions = json.getJSONArray("redemptions")
        val rewards = json.getJSONArray("rewards")
        when (kind) {
            "reward_upsert" -> {
                val rewardId = checkedRewardId(payload)
                val name = payload.getString("name").trim()
                val cost = payload.getInt("cost")
                require(name.isNotEmpty() && name.length <= 60 && cost in 1..999) {
                    "약속 이름은 1~60자, 스티커는 1~999개로 입력해 주세요."
                }
                val row = JSONObject().put("id", rewardId).put("name", name).put("cost", cost)
                val index = (0 until rewards.length()).firstOrNull { rewards.getJSONObject(it).getString("id") == rewardId }
                if (index == null) rewards.put(row) else rewards.put(index, row)
                payload.put("name", name)
            }
            "reward_delete" -> {
                val rewardId = checkedRewardId(payload)
                val index = (0 until rewards.length()).firstOrNull { rewards.getJSONObject(it).getString("id") == rewardId }
                    ?: error("이 약속은 이미 삭제되었어요.")
                rewards.remove(index)
            }
            "sticker_award" -> {
                require(payload.getInt("count") == 1) { "스티커는 하나씩 줄 수 있어요." }
                json.put("stickerBalance", json.getInt("stickerBalance") + 1)
            }
            "sticker_redeem_request" -> {
                val rewardId = checkedRewardId(payload)
                val reward = (0 until rewards.length()).map { rewards.getJSONObject(it) }
                    .firstOrNull { it.getString("id") == rewardId }
                    ?: error("이 약속은 삭제되었어요. 다시 골라 주세요.")
                require(payload.getString("reward") == reward.getString("name") && payload.getInt("cost") == reward.getInt("cost")) {
                    "약속이 변경되었어요. 다시 골라 주세요."
                }
                require(reward.getInt("cost") <= json.getInt("stickerBalance")) { "모은 스티커 안에서 골라 주세요." }
                redemptions.put(JSONObject().put("id", id).put("rewardId", rewardId).put("reward", reward.getString("name"))
                    .put("cost", reward.getInt("cost")).put("status", "pending"))
            }
            "sticker_redeem_approve" -> {
                val target = (0 until redemptions.length()).map { redemptions.getJSONObject(it) }
                    .firstOrNull { it.getString("id") == payload.getString("requestId") }
                    ?: error("사용 요청을 찾을 수 없어요.")
                require(target.getString("status") == "pending") { "이미 처리한 요청이에요." }
                if (payload.getBoolean("accepted")) {
                    require(json.getInt("stickerBalance") >= target.getInt("cost")) { "스티커가 부족해요." }
                    json.put("stickerBalance", json.getInt("stickerBalance") - target.getInt("cost"))
                }
                // Requests remain a snapshot of the selected promise, even after edits or deletion.
                target.put("status", if (payload.getBoolean("accepted")) "approved" else "rejected")
            }
            "sharing_status" -> json.put("sharingEnabled", payload.getBoolean("enabled"))
        }
        events.put(FamilyEvent(id, kind, payload, sender, Instant.now().toString(), "relayed").json())
        return json
    }

    private fun checkedRewardId(payload: JSONObject): String {
        val id = payload.getString("rewardId")
        require(runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)) { "약속을 다시 골라 주세요." }
        return id
    }
}
