package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class TelegramClientTest {
    private val token = "123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ_123456789"

    @Test fun sendUsesPrivateBotUsernameAndPlainText() {
        var calls = 0
        val client = TelegramClient(token) { actualToken, method, raw, timeout ->
            calls++
            assertEquals(token, actualToken)
            assertEquals("sendMessage", method)
            assertEquals(0, timeout)
            val request = JSONObject(raw)
            assertEquals("@FamilyGuardianBot", request.getString("chat_id"))
            assertEquals("{\"message\":\"안녕 <가족>\"}", request.getString("text"))
            assertFalse(request.has("parse_mode"))
            assertTrue(request.getBoolean("protect_content"))
            TelegramHttpResponse(200, """{"ok":true,"result":{"message_id":8,"chat":{"id":987654321,"type":"private"}}}""")
        }
        val message = client.send(" FamilyGuardianBot ", "{\"message\":\"안녕 <가족>\"}")
        assertEquals(987654321L, message.getJSONObject("chat").getLong("id"))
        assertEquals(1, calls)
    }

    @Test fun getUpdatesPreservesLongOffsetAndExplicitlySelectsMessages() {
        val client = TelegramClient(token) { _, method, raw, timeout ->
            assertEquals("getUpdates", method)
            assertEquals(25, timeout)
            val request = JSONObject(raw)
            assertEquals(4000000000L, request.getLong("offset"))
            assertEquals(25, request.getInt("timeout"))
            assertEquals(100, request.getInt("limit"))
            assertEquals("message", request.getJSONArray("allowed_updates").getString(0))
            TelegramHttpResponse(200, """{"ok":true,"result":[{"update_id":4000000000}]}""")
        }
        assertEquals(4000000000L, client.getUpdates(4000000000L, 25).getJSONObject(0).getLong("update_id"))
    }

    @Test fun sendUsesPinnedPrivateChatIdAsJsonNumber() {
        val client = TelegramClient(token) { _, method, raw, _ ->
            assertEquals("sendMessage", method)
            val request = JSONObject(raw)
            assertTrue(request.get("chat_id") is Number)
            assertEquals(4000000000L, request.getLong("chat_id"))
            TelegramHttpResponse(200, """{"ok":true,"result":{"message_id":9,"chat":{"id":4000000000,"type":"private"}}}""")
        }
        assertEquals(9, client.send("4000000000", "hello").getInt("message_id"))
    }

    @Test fun rejectGroupsInvalidNumericIdsAndUrlDestinationsBeforeNetwork() {
        var calls = 0
        val client = TelegramClient(token) { _, _, _, _ ->
            calls++
            TelegramHttpResponse(200, """{"ok":true,"result":{}}""")
        }
        for (destination in listOf("-100123456789", "0", "+123456789", "000123456789",
            "9223372036854775808", "123456.78", "https://t.me/FamilyBot", "@FamilyBot/path", "")) {
            assertThrows(IllegalArgumentException::class.java) { client.send(destination, "hello") }
        }
        assertEquals(0, calls)
    }

    @Test fun rateLimitReportsRetryAfterWithoutRetryingOrLeakingDescription() {
        var calls = 0
        val client = TelegramClient(token) { _, _, _, _ ->
            calls++
            TelegramHttpResponse(429, JSONObject().put("ok", false).put("error_code", 429)
                .put("description", "Token was $token at https://api.telegram.org/bot$token/sendMessage")
                .put("parameters", JSONObject().put("retry_after", 67)).toString())
        }
        val error = assertThrows(TelegramException::class.java) { client.send("@ParentBot", "hello") }
        assertEquals(429, error.errorCode)
        assertEquals(67, error.retryAfterSeconds)
        assertEquals(TelegramOperation.SEND, error.operation)
        assertEquals(1, calls)
        assertFalse(error.toString().contains(token))
        assertNull(error.cause)
    }

    @Test fun networkErrorsNeverExposeTheirTokenBearingCause() {
        val client = TelegramClient(token) { _, _, _, _ ->
            throw IOException("https://api.telegram.org/bot$token/getMe")
        }
        val error = assertThrows(TelegramException::class.java) { client.getMe() }
        assertEquals(0, error.errorCode)
        assertEquals(TelegramOperation.IDENTITY, error.operation)
        assertFalse(error.stackTraceToString().contains(token))
        assertNull(error.cause)
    }

    @Test fun pollRateLimitRetainsSafeOperationAndBackoff() {
        val client = TelegramClient(token) { _, _, _, _ ->
            TelegramHttpResponse(429, """{"ok":false,"error_code":429,"description":"$token","parameters":{"retry_after":91}}""")
        }
        val error = assertThrows(TelegramException::class.java) { client.getUpdates(0) }
        assertEquals(TelegramOperation.POLL, error.operation)
        assertEquals(91, error.retryAfterSeconds)
        assertFalse(error.stackTraceToString().contains(token))
    }

    @Test fun webhookInspectionDoesNotDeleteOrChangeIt() {
        val methods = mutableListOf<String>()
        val client = TelegramClient(token) { _, method, _, _ ->
            methods += method
            TelegramHttpResponse(200, """{"ok":true,"result":{"url":"https://existing.example/webhook"}}""")
        }
        assertEquals("https://existing.example/webhook", client.getWebhookInfo().getString("url"))
        assertEquals(listOf("getWebhookInfo"), methods)
    }

    @Test fun malformedResponseAndWrongResultTypeFailClosed() {
        for (body in listOf("not json $token", "{\"ok\":true,\"result\":[]}")) {
            val client = TelegramClient(token) { _, _, _, _ -> TelegramHttpResponse(200, body) }
            val error = assertThrows(TelegramException::class.java) { client.getMe() }
            assertFalse(error.stackTraceToString().contains(token))
        }
    }

    @Test fun authenticationAndPollConflictReturnSafeActionableErrors() {
        for (code in listOf(401, 409)) {
            val client = TelegramClient(token) { _, _, _, _ ->
                TelegramHttpResponse(code, """{"ok":false,"error_code":$code,"description":"$token"}""")
            }
            val error = assertThrows(TelegramException::class.java) { client.getUpdates(0) }
            assertEquals(code, error.errorCode)
            assertFalse(error.toString().contains(token))
        }
    }

    @Test fun rejectCredentialOrPeerUrlInjectionBeforeAnyNetworkCall() {
        for (bad in listOf("https://api.telegram.org/bot$token", "$token/../getMe", "$token\nextra", "")) {
            assertThrows(IllegalArgumentException::class.java) { TelegramClient.normalizeToken(bad) }
        }
        for (bad in listOf("https://t.me/FamilyBot", "@FamilyBot/path", "@Family Bot", "@@FamilyBot", "abcd")) {
            assertThrows(IllegalArgumentException::class.java) { TelegramClient.normalizePeerUsername(bad) }
        }
        assertEquals(token, TelegramClient.normalizeToken(" $token "))
        assertEquals("@FamilyBot", TelegramClient.normalizePeerUsername(" FamilyBot "))
        assertEquals("@23FamilyBot", TelegramClient.normalizePeerUsername("@23FamilyBot"))
    }

    @Test fun invalidPollArgumentsAndOversizedMessageNeverReachTransport() {
        var calls = 0
        val client = TelegramClient(token) { _, _, _, _ ->
            calls++
            TelegramHttpResponse(200, """{"ok":true,"result":{}}""")
        }
        assertThrows(IllegalArgumentException::class.java) { client.getUpdates(-1) }
        assertThrows(IllegalArgumentException::class.java) { client.getUpdates(0, 51) }
        assertThrows(IllegalArgumentException::class.java) { client.send("@FamilyBot", " ") }
        assertThrows(IllegalArgumentException::class.java) { client.send("@FamilyBot", "a".repeat(4097)) }
        assertEquals(0, calls)
    }

    @Test fun documentUploadUsesPinnedRecipientAndSilentProtectedMultipartFields() {
        val http = FileHttp()
        val bytes = byteArrayOf(31, -117, 1, 2)
        val client = TelegramClient(token, http)
        client.sendDocumentToFamilyMember(FamilyChatMember(202, "@FamilyParentBot", "아빠", "father"), "safe manifest", bytes)
        assertEquals(1, http.uploads)
        val fields = JSONObject(http.lastFields)
        assertTrue(fields.get("chat_id") is Number)
        assertEquals(202L, fields.getLong("chat_id"))
        assertTrue(fields.getBoolean("protect_content"))
        assertTrue(fields.getBoolean("disable_notification"))
        assertTrue(fields.getBoolean("disable_content_type_detection"))
        assertEquals("safe manifest", fields.getString("caption"))
        assertEquals("family-records.json.gz", http.lastFilename)
        assertArrayEquals(bytes, http.lastBytes)
        assertEquals(0, http.lookups)
    }

    @Test fun documentUploadRateLimitIsNotRetriedAndDoesNotExposeServerDescription() {
        val http = FileHttp().apply { uploadError = 429 }
        val error = assertThrows(TelegramException::class.java) {
            TelegramClient(token, http).sendDocumentToFamilyMember(FamilyChatMember(202, "@FamilyParentBot", "아빠", "father"), "manifest", byteArrayOf(1))
        }
        assertEquals(429, error.errorCode)
        assertEquals(73, error.retryAfterSeconds)
        assertEquals(TelegramOperation.FILE_UPLOAD, error.operation)
        assertEquals(1, http.uploads)
        assertFalse(error.stackTraceToString().contains(token))
        assertNull(error.cause)
    }

    @Test fun missingDocumentChatResolvesIdentityWithoutSendingContentToAnotherBot() {
        val http = FileHttp().apply { uploadError = 400; resolvedPeer = 999 }
        val error = assertThrows(TelegramException::class.java) {
            TelegramClient(token, http).sendDocumentToFamilyMember(FamilyChatMember(202, "@FamilyParentBot", "아빠", "father"), "private manifest", byteArrayOf(1))
        }
        assertEquals(TelegramFailureReason.PEER_IDENTITY_MISMATCH, error.reason)
        assertEquals(1, http.uploads)
        assertEquals(1, http.lookups)
        assertEquals(202L, JSONObject(http.lastFields).getLong("chat_id"))
    }

    @Test fun fileMetadataMustPinIdentitySizeAndSafeRelativeTelegramPathBeforeDownload() {
        for (path in listOf("../secret", "documents/../secret", "documents//file", "/documents/file", "https://evil.test/file",
            "documents/%2e%2e", "documents/file?token=private", "documents\\file")) {
            val http = FileHttp().apply { filePath = path }
            assertThrows(InvalidFamilyDocumentException::class.java) { TelegramClient(token, http).downloadFamilyDocument("safe_file", 4) }
            assertEquals(0, http.downloads)
        }
        for (http in listOf(FileHttp().apply { returnedFileId = "other_file" }, FileHttp().apply { fileSize = 5 })) {
            assertThrows(InvalidFamilyDocumentException::class.java) { TelegramClient(token, http).downloadFamilyDocument("safe_file", 4) }
            assertEquals(0, http.downloads)
        }
        val good = FileHttp()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), TelegramClient(token, good).downloadFamilyDocument("safe_file", 4))
        assertEquals(1, good.downloads)
    }

    @Test fun permanentlyMissingFileIsTerminalButAuthRateAndNetworkFailuresRemainRetryable() {
        for (stage in listOf("info", "download")) {
            for (code in listOf(400, 404)) {
                val http = FileHttp().apply { if (stage == "info") infoError = code else downloadError = code }
                val error = assertThrows(InvalidFamilyDocumentException::class.java) {
                    TelegramClient(token, http).downloadFamilyDocument("safe_file", 4)
                }
                assertFalse(error.stackTraceToString().contains(token))
                assertNull(error.cause)
            }
            for (code in listOf(401, 409, 429, 500)) {
                val http = FileHttp().apply { if (stage == "info") infoError = code else downloadError = code }
                val error = assertThrows(TelegramException::class.java) { TelegramClient(token, http).downloadFamilyDocument("safe_file", 4) }
                assertEquals(code, error.errorCode)
                assertEquals(if (stage == "info") TelegramOperation.FILE_INFO else TelegramOperation.FILE_DOWNLOAD, error.operation)
                if (code == 429) assertEquals(73, error.retryAfterSeconds)
                assertFalse(error.stackTraceToString().contains(token))
                assertNull(error.cause)
            }
        }
        val network = FileHttp().apply { downloadException = IOException("https://api.telegram.org/file/bot$token/private-path") }
        val error = assertThrows(TelegramException::class.java) { TelegramClient(token, network).downloadFamilyDocument("safe_file", 4) }
        assertEquals(0, error.errorCode)
        assertEquals(TelegramOperation.FILE_DOWNLOAD, error.operation)
        assertFalse(error.stackTraceToString().contains(token))
        assertNull(error.cause)
    }

    @Test fun truncatedOrOversizedDownloadAndInvalidUploadAreRejected() {
        for (size in listOf(3, 5)) {
            val http = FileHttp().apply { downloaded = ByteArray(size) }
            assertThrows(InvalidFamilyDocumentException::class.java) { TelegramClient(token, http).downloadFamilyDocument("safe_file", 4) }
        }
        val http = FileHttp(); val client = TelegramClient(token, http)
        val member = FamilyChatMember(202, "@FamilyParentBot", "아빠", "father")
        assertThrows(IllegalArgumentException::class.java) { client.sendDocumentToFamilyMember(member, "manifest", ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { client.sendDocumentToFamilyMember(member, "manifest", ByteArray(256 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { client.sendDocumentToFamilyMember(member, "a".repeat(1025), byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { client.downloadFamilyDocument("unsafe/path", 4) }
        assertEquals(0, http.uploads)
        assertEquals(0, http.downloads)
    }

    @Test fun documentCancellationPropagatesThroughDownloadCallbackAndNetworkBoundaries() {
        val http = FileHttp(); var activeChecks = 0
        val cancelled = CancellationException("cancelled")
        val callbackError = assertThrows(CancellationException::class.java) {
            TelegramClient(token, http).downloadFamilyDocument("safe_file", 4) {
                activeChecks++
                if (activeChecks == 3) throw cancelled
            }
        }
        assertSame(cancelled, callbackError)
        assertEquals(1, http.downloads)
        val transport = FileHttp().apply { downloadException = cancelled }
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            TelegramClient(token, transport).downloadFamilyDocument("safe_file", 4)
        })
        val upload = FileHttp().apply { uploadException = cancelled }
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            TelegramClient(token, upload).sendDocumentToFamilyMember(FamilyChatMember(202, "@FamilyParentBot", "아빠", "father"), "manifest", byteArrayOf(1))
        })
    }

    private inner class FileHttp : TelegramHttpTransport {
        var uploads = 0; var downloads = 0; var lookups = 0
        var uploadError: Int? = null; var infoError: Int? = null; var downloadError: Int? = null
        var uploadException: Exception? = null; var downloadException: Exception? = null
        var resolvedPeer = 202L; var returnedFileId = "safe_file"; var fileSize = 4; var filePath = "documents/safe_file.gz"
        var downloaded = byteArrayOf(1, 2, 3, 4)
        var lastFields = "{}"; var lastFilename = ""; var lastBytes = ByteArray(0)
        override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse {
            if (method == "getChat") { lookups++; return ok(JSONObject().put("id", resolvedPeer).put("type", "private")) }
            assertEquals("getFile", method)
            assertEquals("safe_file", JSONObject(json).getString("file_id"))
            return infoError?.let(::error) ?: ok(JSONObject().put("file_id", returnedFileId).put("file_size", fileSize).put("file_path", filePath))
        }
        override fun uploadDocument(token: String, fields: String, filename: String, bytes: ByteArray): TelegramHttpResponse {
            uploads++; lastFields = fields; lastFilename = filename; lastBytes = bytes.copyOf()
            uploadException?.let { throw it }
            return uploadError?.let(::error) ?: ok(JSONObject().put("chat", JSONObject().put("id", 202).put("type", "private")))
        }
        override fun downloadFile(token: String, filePath: String, maxBytes: Int, checkActive: () -> Unit): ByteArray {
            downloads++; assertEquals(this.filePath, filePath); assertEquals(4, maxBytes); checkActive()
            downloadException?.let { throw it }
            downloadError?.let { throw TelegramException(it, if (it == 429) 73 else null, operation = TelegramOperation.FILE_DOWNLOAD) }
            return downloaded
        }
        private fun ok(result: JSONObject) = TelegramHttpResponse(200, JSONObject().put("ok", true).put("result", result).toString())
        private fun error(code: Int) = TelegramHttpResponse(code, JSONObject().put("ok", false).put("error_code", code)
            .put("description", if (code == 400) "Bad Request: chat not found" else "https://api.telegram.org/bot$token/private-path")
            .put("parameters", JSONObject().put("retry_after", 73)).toString())
    }
}
