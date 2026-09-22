package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class FamilyCareState(val roomId: String, val childId: Long, val epoch: String,
    val revision: Long, val authoritative: Boolean, val state: JSONObject) {
    val snapshot: FamilySnapshot get() = FamilySnapshot.parse(state)
    fun rewardVersion(id: String): Long = state.optJSONObject("careRewardVersions")?.optLong(id, 0) ?: 0
    fun json(): JSONObject = JSONObject().put("roomId", roomId).put("childId", childId).put("epoch", epoch)
        .put("revision", revision).put("authoritative", authoritative).put("state", state)
    companion object {
        fun parse(json: JSONObject) = FamilyCareState(json.getString("roomId"), json.getLong("childId"),
            json.getString("epoch"), json.getLong("revision"), json.getBoolean("authoritative"), json.getJSONObject("state"))
    }
}

data class FamilyCareCommand(val id: String, val roomId: String, val childId: Long, val actorId: Long,
    val baseEpoch: String, val kind: String, val payload: JSONObject, val createdAt: String,
    val expectedRewardVersion: Long? = null) {
    fun json(): JSONObject = JSONObject().put("id", id).put("roomId", roomId).put("childId", childId)
        .put("actorId", actorId).put("baseEpoch", baseEpoch).put("kind", kind).put("payload", payload)
        .put("createdAt", createdAt).put("expectedRewardVersion", expectedRewardVersion ?: JSONObject.NULL)
    val digest: String get() = FamilyCareValidation.digest(json())
    companion object {
        fun parse(j: JSONObject): FamilyCareCommand {
            FamilyChatValidation.keys(j, setOf("id", "roomId", "childId", "actorId", "baseEpoch", "kind", "payload", "createdAt", "expectedRewardVersion"))
            val version = j.opt("expectedRewardVersion").let { if (it == JSONObject.NULL) null else FamilyCareValidation.counter(it) }
            return FamilyCareCommand(FamilyChatValidation.identifier(j.getString("id")),
                FamilyChatValidation.identifier(j.getString("roomId")), FamilyChatValidation.botId(j.opt("childId")),
                FamilyChatValidation.botId(j.opt("actorId")), FamilyChatValidation.identifier(j.getString("baseEpoch")),
                j.getString("kind"), j.getJSONObject("payload"), FamilyCareValidation.timestamp(j.getString("createdAt")), version)
        }
    }
}

data class FamilyCareOutcome(val roomId: String, val childId: Long, val commandId: String,
    val actorId: Long, val commandDigest: String, val epoch: String, val revision: Long,
    val accepted: Boolean, val reason: String? = null) {
    fun json(): JSONObject = JSONObject().put("roomId", roomId).put("childId", childId).put("commandId", commandId)
        .put("actorId", actorId).put("commandDigest", commandDigest).put("epoch", epoch).put("revision", revision)
        .put("accepted", accepted).put("reason", reason ?: JSONObject.NULL)
    companion object {
        fun parse(j: JSONObject): FamilyCareOutcome {
            FamilyChatValidation.keys(j, setOf("roomId", "childId", "commandId", "actorId", "commandDigest", "epoch", "revision", "accepted", "reason"))
            require(j.opt("accepted") is Boolean)
            val reason = j.opt("reason").let { if (it == JSONObject.NULL) null else (it as? String)?.also { text -> require(text.length <= 240) } ?: error("처리 결과를 확인해 주세요.") }
            return FamilyCareOutcome(FamilyChatValidation.identifier(j.getString("roomId")),
                FamilyChatValidation.botId(j.opt("childId")), FamilyChatValidation.identifier(j.getString("commandId")),
                FamilyChatValidation.botId(j.opt("actorId")), FamilyCareValidation.hash(j.getString("commandDigest")),
                FamilyChatValidation.identifier(j.getString("epoch")), FamilyCareValidation.counter(j.opt("revision")), j.getBoolean("accepted"), reason)
        }
    }
}

object FamilyCareValidation {
    val parentRelationships = setOf("mother", "father")
    val childRelationships = setOf("son", "daughter")
    val parentKinds = setOf("sticker_award", "sticker_redeem_approve", "reward_upsert", "reward_delete")
    val childKinds = setOf("sticker_redeem_request")
    val telemetryKinds = setOf("location", "vertical", "heartbeat", "sharing_status")
    fun isParent(room: FamilyChatRoom, id: Long) = room.members.any { it.botId == id && it.relationship in parentRelationships }
    fun isChild(room: FamilyChatRoom, id: Long) = room.members.any { it.botId == id && it.relationship in childRelationships }
    fun rosterHash(room: FamilyChatRoom): String = digest(room.copy(members = room.members.sortedBy { it.botId }).json())
    fun counter(value: Any?): Long {
        require(value is Int || value is Long) { "기록 순서를 확인해 주세요." }
        return (value as Number).toLong().also { require(it in 0..9_007_199_254_740_991L) { "기록 순서를 확인해 주세요." } }
    }
    fun hash(value: String): String = value.also { require(Regex("^[0-9a-f]{64}$").matches(it)) { "가족 기록을 확인해 주세요." } }
    fun timestamp(value: String): String {
        require(value.length <= 40)
        val instant = Instant.parse(value)
        require(instant >= Instant.parse("2000-01-01T00:00:00Z") && instant < Instant.parse("2100-01-01T00:00:00Z"))
        return instant.toString()
    }
    fun command(room: FamilyChatRoom, command: FamilyCareCommand): FamilyCareCommand {
        require(command.roomId == room.id && isChild(room, command.childId)) { "자녀를 확인해 주세요." }
        require((command.kind in parentKinds && isParent(room, command.actorId)) ||
            (command.kind in childKinds && command.actorId == command.childId)) { "이 요청을 처리할 권한이 없어요." }
        val event = TelegramLedger.validate(FamilyEvent(command.id, command.kind, command.payload,
            if (command.kind in parentKinds) "guardian" else "child", command.createdAt, "relayed"))
        FamilyChatValidation.keys(command.payload, event.payload.keys().asSequence().toSet())
        val changesReward = command.kind in setOf("reward_upsert", "reward_delete", "sticker_redeem_request")
        require(!changesReward || command.expectedRewardVersion != null) { "최신 칭찬판을 받은 뒤 다시 시도해 주세요." }
        command.expectedRewardVersion?.let { counter(it) }
        return command.copy(id = event.id, payload = event.payload, createdAt = event.createdAt,
            baseEpoch = FamilyChatValidation.identifier(command.baseEpoch))
    }
    fun digest(value: Any?): String = FamilyChatValidation.digest(canonical(value))
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> JSONObject.numberToString(value)
        else -> error("지원하지 않는 가족 기록이에요.")
    }
}

/** Bounded, compressed snapshots omit private chat and unbounded authority replay identities. */
object FamilyCareSnapshots {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val CHUNK_SIZE = 2400
    const val MAX_CHUNKS = 1400
    fun projection(state: JSONObject): JSONObject {
        val result = JSONObject().put("stickerBalance", state.optInt("stickerBalance"))
            .put("sharingEnabled", state.optBoolean("sharingEnabled")).put("transport", "telegram").put("pushConfigured", false)
            .put("rewards", JSONArray((state.optJSONArray("rewards") ?: JSONArray()).toString()))
            .put("careRewardVersions", JSONObject((state.optJSONObject("careRewardVersions") ?: JSONObject()).toString()))
        val redemptions = state.optJSONArray("redemptions") ?: JSONArray()
        val selected = (0 until redemptions.length()).filter { it >= redemptions.length() - 200 || redemptions.getJSONObject(it).optString("status") == "pending" }
        result.put("redemptions", JSONArray().apply { selected.forEach { put(redemptions.getJSONObject(it)) } })
        val events = state.optJSONArray("events") ?: JSONArray()
        val careEvents = (0 until events.length()).map { events.getJSONObject(it) }.filter { it.optString("kind") != "chat" }.takeLast(200)
        result.put("events", JSONArray(careEvents))
        listOf("latestLocation", "latestHeartbeat").forEach { key -> state.optJSONObject(key)?.let { result.put(key, it) } }
        return result
    }
    fun chunks(source: FamilyCareState): List<FamilyCareChunk> {
        val wire = source.copy(authoritative = false, state = projection(source.state)).json().toString().toByteArray(Charsets.UTF_8)
        require(wire.size <= MAX_BYTES) { "칭찬판 기록이 너무 커요. 완료한 사용 요청을 정리해 주세요." }
        val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(wire) } }.toByteArray()
        val encoded = Base64.getEncoder().encodeToString(compressed)
        val parts = encoded.chunked(CHUNK_SIZE)
        require(parts.size in 1..MAX_CHUNKS)
        val id = UUID.randomUUID().toString()
        val digest = FamilyChatValidation.digest(String(wire, Charsets.UTF_8))
        return parts.mapIndexed { index, text -> FamilyCareChunk(source.roomId, id, source.childId, source.epoch, source.revision, index, parts.size, digest, text) }
    }
    fun chunkJson(chunk: FamilyCareChunk): JSONObject = JSONObject().put("transferId", chunk.transferId)
        .put("epoch", chunk.epoch).put("revision", chunk.revision).put("index", chunk.index).put("count", chunk.count)
        .put("digest", chunk.digest).put("encoded", chunk.encoded)
    fun parseChunk(roomId: String, childId: Long, j: JSONObject): FamilyCareChunk {
        FamilyChatValidation.keys(j, setOf("transferId", "epoch", "revision", "index", "count", "digest", "encoded"))
        val count = FamilyCareValidation.counter(j.opt("count")).also { require(it in 1..MAX_CHUNKS.toLong()) }.toInt()
        val index = FamilyCareValidation.counter(j.opt("index")).also { require(it < count) }.toInt()
        val encoded = j.getString("encoded").also { require(it.length in 1..CHUNK_SIZE && Regex("^[A-Za-z0-9+/]*={0,2}$").matches(it)) }
        return FamilyCareChunk(roomId, FamilyChatValidation.identifier(j.getString("transferId")), childId,
            FamilyChatValidation.identifier(j.getString("epoch")), FamilyCareValidation.counter(j.opt("revision")), index, count,
            FamilyCareValidation.hash(j.getString("digest")), encoded)
    }
    fun assemble(parts: List<FamilyCareChunk>): FamilyCareState {
        require(parts.isNotEmpty())
        val first = parts.first()
        require(parts.size == first.count && parts.map { it.index }.toSet() == (0 until first.count).toSet())
        require(parts.all { it.copy(index = first.index, encoded = first.encoded) == first })
        val compressed = Base64.getDecoder().decode(parts.sortedBy { it.index }.joinToString("") { it.encoded })
        val output = ByteArrayOutputStream()
        try { GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                require(output.size() + length <= MAX_BYTES) { "가족 기록 크기를 확인해 주세요." }
                output.write(buffer, 0, length)
            }
        } } catch (_: java.io.IOException) { throw IllegalArgumentException("가족 기록 압축 형식을 확인해 주세요.") }
        val text = output.toString(Charsets.UTF_8.name())
        require(FamilyChatValidation.digest(text) == first.digest) { "가족 기록이 손상되었어요." }
        val json = JSONObject(text)
        FamilyChatValidation.keys(json, setOf("roomId", "childId", "epoch", "revision", "authoritative", "state"))
        val state = FamilyCareState.parse(json)
        require(state.roomId == first.roomId && state.childId == first.childId && state.epoch == first.epoch &&
            state.revision == first.revision && !state.authoritative)
        validateProjection(state.state)
        return state
    }
    private fun validateProjection(state: JSONObject) {
        require(state.keys().asSequence().all { it in setOf("stickerBalance", "sharingEnabled", "transport", "pushConfigured", "rewards", "redemptions", "events", "careRewardVersions", "latestLocation", "latestHeartbeat") })
        require(FamilyCareValidation.counter(state.opt("stickerBalance")) <= Int.MAX_VALUE && state.opt("sharingEnabled") is Boolean)
        val rewards = state.getJSONArray("rewards")
        val ids = mutableSetOf<String>()
        for (i in 0 until rewards.length()) {
            val row = rewards.getJSONObject(i)
            val id = FamilyChatValidation.identifier(row.getString("id"))
            require(ids.add(id))
            TelegramLedger.validate(FamilyEvent(UUID.randomUUID().toString(), "reward_upsert", JSONObject()
                .put("rewardId", id).put("name", row.getString("name")).put("cost", row.getInt("cost")), "guardian", "2026-01-01T00:00:00Z", "relayed"))
        }
        val versions = state.getJSONObject("careRewardVersions")
        versions.keys().forEach { FamilyChatValidation.identifier(it); FamilyCareValidation.counter(versions.get(it)) }
        val requests = state.getJSONArray("redemptions")
        val requestIds = mutableSetOf<String>()
        for (i in 0 until requests.length()) {
            val row = requests.getJSONObject(i)
            require(requestIds.add(FamilyChatValidation.identifier(row.getString("id"))))
            require(row.getString("status") in setOf("pending", "approved", "rejected"))
            require(row.getString("reward").length in 1..60 && row.getInt("cost") in 1..999)
        }
        val events = state.getJSONArray("events")
        require(events.length() <= 200)
        for (i in 0 until events.length()) require(TelegramLedger.validate(FamilyEvent.parse(events.getJSONObject(i))).kind != "chat")
        listOf("latestLocation" to "location", "latestHeartbeat" to "heartbeat").forEach { (key, kind) ->
            state.optJSONObject(key)?.let { require(TelegramLedger.validate(FamilyEvent.parse(it)).kind == kind) }
        }
    }
}
