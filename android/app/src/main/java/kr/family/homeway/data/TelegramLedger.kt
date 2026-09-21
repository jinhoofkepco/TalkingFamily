package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

/** Local projection of the two paired devices' events. Caller authenticates the peer and serializes writes. */
internal object TelegramLedger {
    private const val MAX_EVENTS = 1000
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    private val guardianKinds = setOf("sticker_award", "sticker_redeem_approve", "reward_upsert", "reward_delete")
    private val childKinds = setOf("location", "vertical", "sticker_redeem_request", "sharing_status", "heartbeat")

    fun emptyState(): JSONObject = JSONObject()
        .put("events", JSONArray()).put("redemptions", JSONArray()).put("rewards", JSONArray())
        .put("stickerBalance", 0).put("sharingEnabled", false)
        .put("transport", "telegram").put("pushConfigured", false).put("appliedEventIds", JSONObject())

    fun contains(state: JSONObject, id: String): Boolean = state.optJSONObject("appliedEventIds")?.has(id.lowercase()) == true

    /** Bounds and types are checked before payloads enter the cache; unknown fields are discarded. */
    fun validate(event: FamilyEvent): FamilyEvent {
        require(event.sender == "child" || event.sender == "guardian") { "사용자를 확인해 주세요." }
        require(event.kind == "chat" || event.kind in guardianKinds || event.kind in childKinds) { "지원하지 않는 요청이에요." }
        require(event.kind !in guardianKinds || event.sender == "guardian") { "보호자만 바꿀 수 있어요." }
        require(event.kind !in childKinds || event.sender == "child") { "자녀 화면에서 사용할 수 있어요." }
        require(event.json().toString().length <= 3900) { "내용이 너무 길어요. 짧게 나누어 보내 주세요." }
        val id = identifier(event.id)
        val createdAt = timestamp(event.createdAt)
        val p = event.payload
        val payload = JSONObject()
        when (event.kind) {
            "chat" -> payload.put("text", text(p, "text", 1500))
            "location" -> {
                val source = p.opt("source")
                require(source == "manual" || source == "automatic") { "위치 공유 방식을 확인해 주세요." }
                payload.put("latitude", number(p, "latitude", -90.0, 90.0))
                    .put("longitude", number(p, "longitude", -180.0, 180.0))
                    .put("accuracy", number(p, "accuracy", 0.0, 100_000.0))
                    .put("capturedAt", timestamp(p.opt("capturedAt"))).put("source", source)
                LocationMotionMetadata.copyValidated(p, payload)
            }
            "vertical" -> {
                val phase = p.opt("phase")
                require(phase in setOf("ascent_started", "ascent_finished", "descent_started", "descent_finished") && p.opt("confidence") == "estimated") {
                    "높이 변화 기록을 확인해 주세요."
                }
                payload.put("phase", phase).put("confidence", "estimated")
                    .put("relativeMeters", number(p, "relativeMeters", -10_000.0, 10_000.0))
                    .put("measuredAt", timestamp(p.opt("measuredAt")))
                if (p.has("latitude") || p.has("longitude")) {
                    payload.put("latitude", number(p, "latitude", -90.0, 90.0))
                        .put("longitude", number(p, "longitude", -180.0, 180.0))
                }
            }
            "sticker_award" -> {
                require(integer(p, "count", 1, 1) == 1) { "스티커는 하나씩 줄 수 있어요." }
                payload.put("count", 1).put("reason", text(p, "reason", 200))
            }
            "sticker_redeem_request" -> payload.put("rewardId", identifier(p.opt("rewardId")))
                .put("reward", text(p, "reward", 60)).put("cost", integer(p, "cost", 1, 999))
            "sticker_redeem_approve" -> payload.put("requestId", identifier(p.opt("requestId")))
                .put("accepted", boolean(p, "accepted"))
            "reward_upsert" -> payload.put("rewardId", identifier(p.opt("rewardId")))
                .put("name", text(p, "name", 60)).put("cost", integer(p, "cost", 1, 999))
            "reward_delete" -> payload.put("rewardId", identifier(p.opt("rewardId")))
            "sharing_status" -> payload.put("enabled", boolean(p, "enabled"))
            "heartbeat" -> {
                payload.put("recordedAt", timestamp(p.opt("recordedAt")))
                if (p.has("batteryPercent")) payload.put("batteryPercent", number(p, "batteryPercent", 0.0, 100.0))
            }
        }
        return event.copy(id = id, payload = payload, createdAt = createdAt).also {
            // Leave room for the Telegram envelope and receipt metadata.
            require(it.json().toString().length <= 3700) { "내용이 너무 길어요. 짧게 나누어 보내 주세요." }
        }
    }

    /**
     * Returns a new state, leaving current untouched even on failure. Each sender must arrive FIFO.
     * Only the guardian changes the balance. Request snapshots do not depend on the current catalog:
     * a child may have sent a request immediately before the guardian changed that catalog.
     */
    fun apply(current: JSONObject, input: FamilyEvent): JSONObject {
        val event = validate(input)
        val state = JSONObject(current.toString())
        val applied = state.optJSONObject("appliedEventIds") ?: JSONObject().also { state.put("appliedEventIds", it) }
        val digest = digest(event)
        if (applied.has(event.id)) {
            require(applied.getString(event.id) == digest) { "같은 기록의 내용이 달라졌어요. 연결을 확인해 주세요." }
            return state
        }
        val events = array(state, "events")
        val redemptions = array(state, "redemptions")
        val rewards = array(state, "rewards")
        val p = event.payload
        when (event.kind) {
            "reward_upsert" -> {
                val id = p.getString("rewardId")
                val row = JSONObject().put("id", id).put("name", p.getString("name")).put("cost", p.getInt("cost"))
                val index = index(rewards, id)
                if (index == null) rewards.put(row) else rewards.put(index, row)
            }
            "reward_delete" -> index(rewards, p.getString("rewardId"))?.let { rewards.remove(it) }
            "sticker_award" -> {
                val balance = state.optInt("stickerBalance")
                require(balance in 0 until Int.MAX_VALUE) { "스티커 수를 확인해 주세요." }
                state.put("stickerBalance", balance + 1)
            }
            "sticker_redeem_request" -> redemptions.put(JSONObject().put("id", event.id)
                .put("rewardId", p.getString("rewardId")).put("reward", p.getString("reward"))
                .put("cost", p.getInt("cost")).put("status", "pending"))
            "sticker_redeem_approve" -> {
                val targetIndex = index(redemptions, p.getString("requestId"))
                require(targetIndex != null) { "사용 요청을 먼저 받아야 해요. 연결 후 다시 시도해 주세요." }
                val target = redemptions.getJSONObject(targetIndex)
                // A second decision, including one with a new event ID, cannot debit or reopen a request.
                if (target.getString("status") == "pending") {
                    if (p.getBoolean("accepted")) {
                        val balance = state.optInt("stickerBalance")
                        val cost = target.getInt("cost")
                        require(balance >= cost) { "스티커가 부족해요." }
                        state.put("stickerBalance", balance - cost)
                    }
                    target.put("status", if (p.getBoolean("accepted")) "approved" else "rejected")
                        .put("decisionId", event.id)
                }
            }
            "sharing_status" -> state.put("sharingEnabled", p.getBoolean("enabled"))
            "location" -> latest(state, "latestLocation", event, "capturedAt")
            "heartbeat" -> latest(state, "latestHeartbeat", event, "recordedAt")
        }
        events.put(event.json())
        if (events.length() > MAX_EVENTS) {
            state.put("events", JSONArray().apply {
                for (i in events.length() - MAX_EVENTS until events.length()) put(events.getJSONObject(i))
            })
        }
        // Never evict financial replay identities when the visible timeline is trimmed.
        applied.put(event.id, digest)
        state.put("transport", "telegram").put("pushConfigured", false)
        return state
    }

    private fun array(state: JSONObject, name: String): JSONArray = state.optJSONArray(name)
        ?: JSONArray().also { state.put(name, it) }

    private fun index(rows: JSONArray, id: String): Int? = (0 until rows.length())
        .firstOrNull { rows.getJSONObject(it).getString("id") == id }

    private fun latest(state: JSONObject, key: String, event: FamilyEvent, timeField: String) {
        val prior = state.optJSONObject(key)
        val priorTime = prior?.optJSONObject("payload")?.optString(timeField)
        if (priorTime.isNullOrBlank() || Instant.parse(event.payload.getString(timeField)) >= Instant.parse(priorTime)) {
            state.put(key, event.json())
        }
    }

    private fun identifier(value: Any?): String {
        require(value is String && uuid.matches(value)) { "기록 식별자를 확인해 주세요." }
        return value.lowercase()
    }

    private fun timestamp(value: Any?): String {
        require(value is String && value.length <= 40 && Regex("^\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(value)) { "기록 시간을 확인해 주세요." }
        return runCatching { Instant.parse(value).toString() }
            .getOrElse { throw IllegalArgumentException("기록 시간을 확인해 주세요.") }
    }

    private fun text(p: JSONObject, key: String, max: Int): String {
        val value = p.opt(key)
        require(value is String && value.length <= max && value.trim().isNotEmpty()) { "내용은 1~${max}자로 입력해 주세요." }
        return value.trim()
    }

    private fun number(p: JSONObject, key: String, min: Double, max: Double): Double {
        val value = p.opt(key)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() in min..max) { "숫자 값을 확인해 주세요. ($key)" }
        return value.toDouble()
    }

    private fun integer(p: JSONObject, key: String, min: Int, max: Int): Int {
        val value = number(p, key, min.toDouble(), max.toDouble())
        require(value % 1.0 == 0.0) { "스티커 수는 정수로 입력해 주세요." }
        return value.toInt()
    }

    private fun boolean(p: JSONObject, key: String): Boolean {
        val value = p.opt(key)
        require(value is Boolean) { "설정 값을 확인해 주세요. ($key)" }
        return value
    }

    private fun digest(event: FamilyEvent): String {
        // Payloads contain only validated primitives; sorting keys makes JSON key order irrelevant.
        val parts = JSONArray().put(event.id).put(event.kind).put(event.sender).put(event.createdAt)
        // Older receivers discard display estimates. Keep the raw event identity stable across an
        // upgrade/retry; an already-applied event remains immutable (including its original estimates).
        event.payload.keys().asSequence()
            .filterNot { event.kind == "location" && it in LocationMotionMetadata.keys }
            .sorted().forEach { key -> parts.put(key).put(event.payload.get(key)) }
        return MessageDigest.getInstance("SHA-256").digest(parts.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
