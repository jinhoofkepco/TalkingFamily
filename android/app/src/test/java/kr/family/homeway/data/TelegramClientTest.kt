package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

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
        assertFalse(error.stackTraceToString().contains(token))
        assertNull(error.cause)
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
}
