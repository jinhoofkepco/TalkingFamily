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
    message: String = telegramErrorMessage(errorCode, retryAfterSeconds)
) : Exception(message)

private fun telegramErrorMessage(code: Int, retry: Int?): String = when (code) {
    401, 404 -> "봇 토큰을 확인해 주세요. BotFather에서 발급한 토큰을 입력해야 합니다."
    400, 403 -> "상대 봇 이름과 두 봇의 Bot-to-Bot Communication 설정을 확인해 주세요."
    409 -> "이 봇이 다른 앱이나 웹훅에서 사용 중입니다. 휴대폰마다 별도 봇을 사용해 주세요."
    429 -> "텔레그램 요청이 많습니다. ${retry ?: 30}초 뒤 다시 시도해 주세요."
    in 500..599 -> "텔레그램에서 잠시 응답하지 않습니다. 잠시 후 다시 시도해 주세요."
    else -> "텔레그램에 연결하지 못했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요."
}

/**
 * Direct Bot API transport. Each phone owns its bot and is the only getUpdates consumer.
 * The caller must persist its update offset only after saving the corresponding messages.
 * No retry is hidden here: retrying a send after a lost response can duplicate the message.
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

    fun getUpdates(offset: Long, timeout: Int = 0): JSONArray {
        require(offset >= 0) { "텔레그램 수신 위치가 올바르지 않습니다." }
        require(timeout in 0..50) { "텔레그램 대기 시간이 올바르지 않습니다." }
        val response = request("getUpdates", JSONObject()
            .put("offset", offset)
            .put("timeout", timeout)
            .put("limit", 100)
            .put("allowed_updates", JSONArray().put("message")), timeout)
        return response.optJSONArray("result") ?: throw TelegramException(0)
    }

    private fun objectResult(method: String, body: JSONObject): JSONObject =
        request(method, body).optJSONObject("result") ?: throw TelegramException(0)

    private fun request(method: String, body: JSONObject, timeout: Int = 0): JSONObject {
        // Do not retain causes: IOException / JSONException may include a token-bearing URL or body.
        try {
            val response = transport.execute(token, method, body.toString(), timeout)
            val parsed = runCatching { JSONObject(response.body) }.getOrNull()
            if (response.status !in 200..299 || parsed?.optBoolean("ok") != true) {
                val code = parsed?.optInt("error_code", response.status) ?: response.status
                val retry = if (code == 429) {
                    parsed?.optJSONObject("parameters")?.optInt("retry_after", 30)?.coerceAtLeast(1) ?: 30
                } else null
                throw TelegramException(code, retry)
            }
            return parsed
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
}

/** Fixed HTTPS endpoint and disabled redirects prevent forwarding a credential elsewhere. */
private object UrlConnectionTelegramTransport : TelegramHttpTransport {
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse {
        val connection = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
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
            connection.outputStream.use { it.write(bytes) }
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
            return TelegramHttpResponse(status, raw)
        } finally {
            connection.disconnect()
        }
    }
}
