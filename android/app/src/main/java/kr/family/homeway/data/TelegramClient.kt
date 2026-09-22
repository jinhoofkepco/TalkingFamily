package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Only safe, locally authored text reaches the UI; Telegram URLs contain the bot token. */
class TelegramException(
    val errorCode: Int,
    val retryAfterSeconds: Int? = null,
    val reason: TelegramFailureReason = TelegramFailureReason.UNKNOWN,
    message: String = reason.safeMessage(errorCode, retryAfterSeconds)
) : Exception(message)

/**
 * Direct Bot API transport. Each phone owns its bot and is the only getUpdates consumer.
 * The caller must persist its update offset only after saving the corresponding messages.
 * A lost or ambiguous response is never retried here. Only an explicit chat-not-found
 * rejection permits one family-peer lookup and retry to the same pinned numeric ID.
 * Callers must respect TelegramException.retryAfterSeconds and deduplicate their event IDs.
 */
class TelegramClient internal constructor(rawToken: String, private val transport: TelegramHttpTransport) {
    constructor(token: String) : this(token, UrlConnectionTelegramTransport)

    private val token = normalizeToken(rawToken)

    fun getMe(): JSONObject = objectResult("getMe", JSONObject())

    /** A configured webhook is reported to the user; it is never automatically deleted. */
    fun getWebhookInfo(): JSONObject = objectResult("getWebhookInfo", JSONObject())

    /**
     * Both bots must enable Bot-to-Bot Communication in BotFather for private messages.
     * Use the username only when pairing, then send to the pinned positive private-chat ID.
     */
    fun send(peer: String, envelopeText: String): JSONObject {
        val destination = peer.trim()
        val chatId: Any = if (Regex("^[+-]?[0-9]+$").matches(destination)) {
            require(Regex("^[1-9][0-9]*$").matches(destination)) { "가족 봇의 개인 대화 ID가 올바르지 않습니다." }
            destination.toLongOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("가족 봇의 개인 대화 ID가 올바르지 않습니다.")
        } else normalizePeerUsername(destination)
        require(envelopeText.isNotBlank() && envelopeText.length <= 4096) {
            "메시지가 비어 있거나 너무 깁니다. 내용을 줄여 주세요."
        }
        return objectResult("sendMessage", JSONObject()
            .put("chat_id", chatId)
            .put("text", envelopeText)
            .put("protect_content", true))
    }

    /** Resolve an inaccessible family peer without sending private content to an unverified username. */
    fun sendToFamilyMember(
        member: FamilyChatMember,
        envelopeText: String,
        checkActive: () -> Unit = {},
    ): JSONObject {
        checkActive()
        try {
            return send(member.botId.toString(), envelopeText)
        } catch (error: TelegramException) {
            if (error.errorCode != 400 || error.reason != TelegramFailureReason.CHAT_NOT_FOUND) throw error
        }
        checkActive()
        val username = normalizePeerUsername(member.username)
        val chat = objectResult("getChat", JSONObject().put("chat_id", username))
        // optLong alone would also accept a fractional/string ID. Require the exact numeric identity.
        val resolvedId = chat.opt("id")
        if (chat.optString("type") != "private" || resolvedId !is Number ||
            resolvedId.toString() != member.botId.toString()) {
            throw TelegramException(400, reason = TelegramFailureReason.PEER_IDENTITY_MISMATCH)
        }
        checkActive()
        return send(member.botId.toString(), envelopeText)
    }

    fun getUpdates(offset: Long, timeout: Int = 0, pollRevision: Long = TelegramPollWakeup.revision): JSONArray {
        require(offset >= 0) { "텔레그램 수신 위치가 올바르지 않습니다." }
        require(timeout in 0..50) { "텔레그램 대기 시간이 올바르지 않습니다." }
        val response = request("getUpdates", JSONObject()
            .put("offset", offset)
            .put("timeout", timeout)
            .put("limit", 100)
            .put("allowed_updates", JSONArray().put("message")), timeout,
            pollRevision.takeIf { timeout > 0 })
        return response.optJSONArray("result") ?: throw TelegramException(0)
    }

    private fun objectResult(method: String, body: JSONObject): JSONObject =
        request(method, body).optJSONObject("result") ?: throw TelegramException(0)

    private fun request(method: String, body: JSONObject, timeout: Int = 0, pollRevision: Long? = null): JSONObject {
        // Do not retain causes: IOException / JSONException may include a token-bearing URL or body.
        try {
            val response = if (pollRevision != null) {
                if (pollRevision != TelegramPollWakeup.revision) throw TelegramPollInterruptedException()
                transport.poll(token, body.toString(), timeout, pollRevision)
            } else transport.execute(token, method, body.toString(), timeout)
            val parsed = runCatching { JSONObject(response.body) }.getOrNull()
            if (response.status !in 200..299 || parsed?.optBoolean("ok") != true) {
                val code = parsed?.optInt("error_code", response.status) ?: response.status
                val retry = if (code == 429) {
                    parsed?.optJSONObject("parameters")?.optInt("retry_after", 30)?.coerceAtLeast(1) ?: 30
                } else null
                val reason = if (response.status == code && parsed?.opt("ok") == false)
                    TelegramFailureReason.classify(code, parsed.optString("description"))
                    else TelegramFailureReason.UNKNOWN
                throw TelegramException(code, retry, reason)
            }
            return parsed
        } catch (interrupted: TelegramPollInterruptedException) {
            throw interrupted
        } catch (error: TelegramException) {
            throw error
        } catch (_: Exception) {
            throw TelegramException(0)
        }
    }

    companion object {
        fun normalizeToken(raw: String): String = raw.trim().also {
            require(Regex("^[0-9]{1,20}:[A-Za-z0-9_-]{20,128}$").matches(it)) {
                "BotFather에서 받은 봇 토큰을 입력해 주세요."
            }
        }

        fun normalizePeerUsername(raw: String): String {
            val username = raw.trim().removePrefix("@")
            require(Regex("^[A-Za-z0-9_]{5,32}$").matches(username)) {
                "상대 봇의 @사용자이름을 입력해 주세요. 링크나 표시 이름은 사용할 수 없습니다."
            }
            return "@$username"
        }
    }
}

internal data class TelegramHttpResponse(val status: Int, val body: String)

internal fun interface TelegramHttpTransport {
    fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse
    fun poll(token: String, json: String, timeoutSeconds: Int, revision: Long): TelegramHttpResponse =
        execute(token, "getUpdates", json, timeoutSeconds)
}

/** Fixed HTTPS endpoint and disabled redirects prevent forwarding a credential elsewhere. */
private object UrlConnectionTelegramTransport : TelegramHttpTransport {
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse =
        executeRequest(token, method, json, timeoutSeconds, null)

    override fun poll(token: String, json: String, timeoutSeconds: Int, revision: Long): TelegramHttpResponse =
        executeRequest(token, "getUpdates", json, timeoutSeconds, revision)

    private fun executeRequest(token: String, method: String, json: String, timeoutSeconds: Int,
        pollRevision: Long?): TelegramHttpResponse {
        val connection = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        var poll: TelegramPollWakeController.Registration? = null
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = (timeoutSeconds + 15) * 1000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val bytes = json.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            if (pollRevision != null && pollRevision != TelegramPollWakeup.revision) throw TelegramPollInterruptedException()
            connection.outputStream.use { it.write(bytes) }
            // Register after the request is connected: disconnecting an unconnected URLConnection
            // could otherwise be followed by a new connection, losing the wakeup during setup.
            poll = pollRevision?.let { TelegramPollWakeup.register(it) { connection.disconnect() } }
            if (poll?.interrupted == true) throw TelegramPollInterruptedException()
            val status = connection.responseCode
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw TelegramException(0)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) throw TelegramException(0)
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }.orEmpty()
            if (poll?.interrupted == true) throw TelegramPollInterruptedException()
            return TelegramHttpResponse(status, raw)
        } catch (error: Exception) {
            if (poll?.interrupted == true) throw TelegramPollInterruptedException()
            throw error
        } finally {
            poll?.close()
            connection.disconnect()
        }
    }
}
