package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CancellationException

/** Only safe, locally authored text reaches the UI; Telegram URLs contain the bot token. */
class TelegramException(
    val errorCode: Int,
    val retryAfterSeconds: Int? = null,
    val reason: TelegramFailureReason = TelegramFailureReason.UNKNOWN,
    message: String = reason.safeMessage(errorCode, retryAfterSeconds),
    val operation: TelegramOperation = TelegramOperation.UNKNOWN,
) : Exception(message)

enum class TelegramOperation {
    SEND, POLL, IDENTITY, PEER_LOOKUP, WEBHOOK, FILE_UPLOAD, FILE_INFO, FILE_DOWNLOAD, UNKNOWN;
    companion object {
        fun forMethod(method: String): TelegramOperation = when (method) {
            "sendMessage" -> SEND
            "getUpdates" -> POLL
            "getMe" -> IDENTITY
            "getChat" -> PEER_LOOKUP
            "getWebhookInfo" -> WEBHOOK
            "sendDocument" -> FILE_UPLOAD
            "getFile" -> FILE_INFO
            else -> UNKNOWN
        }
    }
}

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

    /** File uploads use the same pinned private recipient and content-free resolution as text. */
    fun sendDocumentToFamilyMember(member: FamilyChatMember, caption: String, bytes: ByteArray,
        checkActive: () -> Unit = {}): JSONObject {
        require(caption.isNotBlank() && caption.length <= 1024 && bytes.size in 1..FamilyDocumentProtocol.MAX_COMPRESSED_BYTES)
        fun upload(): JSONObject {
            checkActive()
            try {
                val fields = JSONObject().put("chat_id", member.botId).put("caption", caption)
                    .put("protect_content", true).put("disable_notification", true).put("disable_content_type_detection", true)
                return parseResponse("sendDocument", transport.uploadDocument(token, fields.toString(),
                    "family-records.json.gz", bytes)).optJSONObject("result")
                    ?: throw TelegramException(0, operation = TelegramOperation.FILE_UPLOAD)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: TelegramException) { throw error }
            catch (_: Exception) { throw TelegramException(0, operation = TelegramOperation.FILE_UPLOAD) }
        }
        try { return upload() }
        catch (error: TelegramException) {
            if (error.errorCode != 400 || error.reason != TelegramFailureReason.CHAT_NOT_FOUND) throw error
        }
        checkActive()
        val chat = objectResult("getChat", JSONObject().put("chat_id", normalizePeerUsername(member.username)))
        val resolved = chat.opt("id")
        if (chat.optString("type") != "private" || resolved !is Number || resolved.toString() != member.botId.toString())
            throw TelegramException(400, reason = TelegramFailureReason.PEER_IDENTITY_MISMATCH, operation = TelegramOperation.FILE_UPLOAD)
        return upload()
    }

    /** A Telegram-issued path is accepted only as a relative path under the fixed Telegram HTTPS host. */
    fun downloadFamilyDocument(fileId: String, expectedBytes: Int, checkActive: () -> Unit = {}): ByteArray {
        validFileId(fileId)
        require(expectedBytes in 1..FamilyDocumentProtocol.MAX_COMPRESSED_BYTES)
        checkActive()
        val file = try { objectResult("getFile", JSONObject().put("file_id", fileId)) }
        catch (error: TelegramException) {
            if (error.errorCode in setOf(400, 404)) throw InvalidFamilyDocumentException()
            throw error
        }
        val path = try {
            require(file.opt("file_id") == fileId)
            if (file.has("file_size")) {
                val size = file.opt("file_size")
                require((size is Int || size is Long) && (size as Number).toLong() == expectedBytes.toLong())
            }
            validFilePath(file.getString("file_path"))
        } catch (_: Exception) { throw InvalidFamilyDocumentException() }
        checkActive()
        val bytes = try { transport.downloadFile(token, path, expectedBytes, checkActive) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: InvalidFamilyDocumentException) { throw error }
        catch (error: TelegramException) {
            if (error.errorCode in setOf(400, 404)) throw InvalidFamilyDocumentException()
            throw error
        }
        catch (_: Exception) { throw TelegramException(0, operation = TelegramOperation.FILE_DOWNLOAD) }
        if (bytes.size != expectedBytes) throw InvalidFamilyDocumentException()
        checkActive()
        return bytes
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
        return response.optJSONArray("result") ?: throw TelegramException(0, operation = TelegramOperation.POLL)
    }

    private fun objectResult(method: String, body: JSONObject): JSONObject =
        request(method, body).optJSONObject("result") ?: throw TelegramException(0, operation = TelegramOperation.forMethod(method))

    private fun request(method: String, body: JSONObject, timeout: Int = 0, pollRevision: Long? = null): JSONObject {
        // Do not retain causes: IOException / JSONException may include a token-bearing URL or body.
        try {
            val response = if (pollRevision != null) {
                if (pollRevision != TelegramPollWakeup.revision) throw TelegramPollInterruptedException()
                transport.poll(token, body.toString(), timeout, pollRevision)
            } else transport.execute(token, method, body.toString(), timeout)
            return parseResponse(method, response)
        } catch (interrupted: TelegramPollInterruptedException) {
            throw interrupted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TelegramException) {
            throw error
        } catch (_: Exception) {
            throw TelegramException(0, operation = TelegramOperation.forMethod(method))
        }
    }

    private fun parseResponse(method: String, response: TelegramHttpResponse): JSONObject {
            val parsed = runCatching { JSONObject(response.body) }.getOrNull()
            if (response.status !in 200..299 || parsed?.optBoolean("ok") != true) {
                val code = parsed?.optInt("error_code", response.status) ?: response.status
                val retry = if (code == 429) {
                    parsed?.optJSONObject("parameters")?.optInt("retry_after", 30)?.coerceAtLeast(1) ?: 30
                } else null
                val reason = if (response.status == code && parsed?.opt("ok") == false)
                    TelegramFailureReason.classify(code, parsed.optString("description"))
                    else TelegramFailureReason.UNKNOWN
                throw TelegramException(code, retry, reason, operation = TelegramOperation.forMethod(method))
            }
            return parsed
    }

    companion object {
        internal fun validFileId(fileId: String): String = fileId.also {
            require(it.length in 1..512 && Regex("^[A-Za-z0-9_-]+$").matches(it))
        }
        internal fun validFilePath(path: String): String = path.also {
            require(it.length in 1..512 && !it.startsWith('/') && it.split('/').all { segment ->
                segment.isNotEmpty() && segment !in setOf(".", "..") && Regex("^[A-Za-z0-9_.-]+$").matches(segment)
            })
        }
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
    fun uploadDocument(token: String, fields: String, filename: String, bytes: ByteArray): TelegramHttpResponse =
        throw UnsupportedOperationException()
    fun downloadFile(token: String, filePath: String, maxBytes: Int, checkActive: () -> Unit): ByteArray =
        throw UnsupportedOperationException()
}

/** Fixed HTTPS endpoint and disabled redirects prevent forwarding a credential elsewhere. */
private object UrlConnectionTelegramTransport : TelegramHttpTransport {
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse =
        executeRequest(token, method, json, timeoutSeconds, null)

    override fun poll(token: String, json: String, timeoutSeconds: Int, revision: Long): TelegramHttpResponse =
        executeRequest(token, "getUpdates", json, timeoutSeconds, revision)

    override fun uploadDocument(token: String, fields: String, filename: String, bytes: ByteArray): TelegramHttpResponse {
        val boundary = "TalkingFamily-${UUID.randomUUID()}"
        val json = JSONObject(fields)
        val prefix = buildString {
            json.keys().forEach { name ->
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n${json.get(name)}\r\n")
            }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"$filename\"\r\n")
            append("Content-Type: application/gzip\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val connection = URL("https://api.telegram.org/bot$token/sendDocument").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 10_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false; connection.useCaches = false; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(prefix.size + bytes.size + suffix.size)
            connection.outputStream.use { it.write(prefix); it.write(bytes); it.write(suffix) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { boundedRead(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            return TelegramHttpResponse(status, response.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    override fun downloadFile(token: String, filePath: String, maxBytes: Int, checkActive: () -> Unit): ByteArray {
        TelegramClient.validFilePath(filePath)
        val connection = URL("https://api.telegram.org/file/bot$token/$filePath").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"; connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            checkActive()
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = runCatching { connection.errorStream?.use { boundedRead(it, 4096) }?.toString(Charsets.UTF_8) }.getOrNull()
                val retry = if (status == 429) runCatching { JSONObject(error.orEmpty()).getJSONObject("parameters")
                    .getInt("retry_after").coerceAtLeast(1) }.getOrDefault(30) else null
                throw TelegramException(status, retry, operation = TelegramOperation.FILE_DOWNLOAD)
            }
            if (connection.contentLengthLong > maxBytes) throw InvalidFamilyDocumentException()
            return connection.inputStream.use { boundedRead(it, maxBytes, checkActive) }
        } finally { connection.disconnect() }
    }

    private fun boundedRead(input: java.io.InputStream, maxBytes: Int, checkActive: () -> Unit = {}): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) throw InvalidFamilyDocumentException()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

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
