package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class TelegramFamilyPeerTest {
    private val member = FamilyChatMember(404, "@daughter_bot", "딸", "daughter")
    private val token = "303:${"a".repeat(32)}"
    private val content = "private family message"

    @Test fun `unknown numeric chat is resolved without content and retried once to pinned ID`() {
        val methods = mutableListOf<String>()
        val client = TelegramClient(token) { _, method, raw, _ ->
            methods += method
            val body = JSONObject(raw)
            when (methods.size) {
                1 -> { assertNumericMessage(body); notFound() }
                2 -> {
                    assertEquals("getChat", method)
                    assertEquals(setOf("chat_id"), body.keys().asSequence().toSet())
                    assertEquals(member.username, body.getString("chat_id"))
                    ok(JSONObject().put("id", 404).put("type", "private"))
                }
                3 -> { assertNumericMessage(body); ok(JSONObject().put("message_id", 7)) }
                else -> error("Unexpected retry")
            }
        }
        assertEquals(7, client.sendToFamilyMember(member, content).getInt("message_id"))
        assertEquals(listOf("sendMessage", "getChat", "sendMessage"), methods)
    }

    @Test fun `known peer sends directly without lookup`() {
        var calls = 0
        val client = TelegramClient(token) { _, method, raw, _ ->
            calls++
            assertEquals("sendMessage", method)
            assertNumericMessage(JSONObject(raw))
            ok(JSONObject())
        }
        client.sendToFamilyMember(member, content)
        assertEquals(1, calls)
    }

    @Test fun `lookup cannot redirect content to reassigned username group or malformed ID`() {
        val chats = listOf(
            JSONObject().put("id", 999).put("type", "private"),
            JSONObject().put("id", 404).put("type", "group"),
            JSONObject().put("id", "404").put("type", "private"),
            JSONObject().put("id", 404.1).put("type", "private"),
            JSONObject().put("type", "private"),
        )
        for (chat in chats) {
            var calls = 0
            val client = TelegramClient(token) { _, method, _, _ ->
                when (++calls) {
                    1 -> notFound()
                    2 -> { assertEquals("getChat", method); ok(chat) }
                    else -> error("Content sent after wrong identity")
                }
            }
            val error = assertThrows(TelegramException::class.java) { client.sendToFamilyMember(member, content) }
            assertEquals(TelegramFailureReason.PEER_IDENTITY_MISMATCH, error.reason)
            assertEquals(2, calls)
        }
    }

    @Test fun `ambiguous response and other rejections never trigger lookup or hidden resend`() {
        val failures = listOf<() -> TelegramHttpResponse>(
            { throw IOException("lost response $token") },
            { TelegramHttpResponse(200, "invalid $token") },
            { rejected(403, "Bad Request: chat not found") },
            { rejected(400, "Bad Request: USER_BOT_TO_BOT_DISABLED") },
            { rejected(400, "unknown response $token") },
            { rejected(500, "Bad Request: chat not found") },
            { TelegramHttpResponse(500, notFound().body) },
            { TelegramHttpResponse(200, notFound().body) },
            { TelegramHttpResponse(429, """{"ok":false,"error_code":429,"parameters":{"retry_after":47}}""") },
        )
        for (failure in failures) {
            var calls = 0
            val client = TelegramClient(token) { _, _, _, _ -> calls++; failure() }
            val error = assertThrows(TelegramException::class.java) { client.sendToFamilyMember(member, content) }
            assertEquals(1, calls)
            assertFalse(error.stackTraceToString().contains(token))
        }
    }

    @Test fun `lookup rate limit propagates and second send rejection is never retried again`() {
        for (rateLimited in listOf(true, false)) {
            var calls = 0
            val client = TelegramClient(token) { _, _, _, _ ->
                when (++calls) {
                    1 -> notFound()
                    2 -> if (rateLimited) TelegramHttpResponse(429,
                        """{"ok":false,"error_code":429,"parameters":{"retry_after":47}}""")
                        else ok(JSONObject().put("id", 404).put("type", "private"))
                    3 -> notFound()
                    else -> error("Unbounded retry")
                }
            }
            val error = assertThrows(TelegramException::class.java) { client.sendToFamilyMember(member, content) }
            assertEquals(if (rateLimited) 429 else 400, error.errorCode)
            assertEquals(if (rateLimited) 2 else 3, calls)
            if (rateLimited) assertEquals(47, error.retryAfterSeconds)
        }
    }

    @Test fun `room cancellation is checked before lookup and before resend`() {
        for (cancelAt in 1..3) {
            var calls = 0
            var checks = 0
            val client = TelegramClient(token) { _, _, _, _ ->
                when (++calls) {
                    1 -> notFound()
                    2 -> ok(JSONObject().put("id", 404).put("type", "private"))
                    else -> error("Sent after cancellation")
                }
            }
            assertThrows(CancellationException::class.java) {
                client.sendToFamilyMember(member, content) {
                    if (++checks == cancelAt) throw CancellationException()
                }
            }
            assertEquals(cancelAt - 1, calls)
        }
    }

    private fun assertNumericMessage(body: JSONObject) {
        assertTrue(body.get("chat_id") is Number)
        assertEquals(member.botId, body.getLong("chat_id"))
        assertEquals(content, body.getString("text"))
    }

    private fun notFound() = rejected(400, "Bad Request: chat not found")
    private fun rejected(code: Int, description: String) = TelegramHttpResponse(code,
        JSONObject().put("ok", false).put("error_code", code).put("description", description).toString())
    private fun ok(result: JSONObject) = TelegramHttpResponse(200,
        JSONObject().put("ok", true).put("result", result).toString())
}
