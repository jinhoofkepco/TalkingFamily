package kr.family.homeway.data

import org.junit.Assert.*
import org.junit.Test

class TelegramFailureReasonTest {
    @Test fun onlyExactChatAndUserLookupFailuresAllowPeerResolution() {
        for (description in listOf("Bad Request: chat not found", "Bad Request: user not found",
            "Bad Request: private chat not found")) {
            assertEquals(TelegramFailureReason.CHAT_NOT_FOUND, TelegramFailureReason.classify(400, description))
            for (code in listOf(0, 200, 401, 403, 404, 409, 429, 500)) {
                assertEquals(TelegramFailureReason.UNKNOWN, TelegramFailureReason.classify(code, description))
            }
        }
    }

    @Test fun similarDescriptionsCannotAuthorizeAResolutionRetry() {
        for (description in listOf(
            "chat not found", "User not found", "Bad Request: User not found", "Bad Request: USER_NOT_FOUND",
            "Bad Request: private chat not found ", "Bad Request: message to copy not found",
            "Bad Request: CHAT_NOT_FOUND", "Bad Request: chat not found ", " Bad Request: chat not found",
            "Bad Request: chat not found\n", "Bad Request: chat not found: extra detail",
            "prefix Bad Request: chat not found", "Bad Request: chat not found\u0000", "", null,
        )) {
            assertEquals(description, TelegramFailureReason.UNKNOWN, TelegramFailureReason.classify(400, description))
        }
    }

    @Test fun onlyTheExplicitBotCommunicationErrorNamesTheSetting() {
        val exact = "Bad Request: USER_BOT_TO_BOT_DISABLED"
        assertEquals(TelegramFailureReason.BOT_TO_BOT_DISABLED, TelegramFailureReason.classify(400, exact))
        for (code in listOf(0, 401, 403, 404, 409, 429, 500)) {
            assertEquals(TelegramFailureReason.UNKNOWN, TelegramFailureReason.classify(code, exact))
        }
        for (description in listOf("USER_BOT_TO_BOT_DISABLED", "Bad Request: user_bot_to_bot_disabled",
            "Forbidden: USER_BOT_TO_BOT_DISABLED", "$exact ", "$exact and another error",
            "Forbidden: bot can't send messages to bots")) {
            for (code in listOf(400, 403)) {
                val reason = TelegramFailureReason.classify(code, description)
                assertEquals(TelegramFailureReason.UNKNOWN, reason)
                assertFalse(reason.safeMessage(code).contains("Bot-to-Bot"))
            }
        }
    }

    @Test fun blockedAndDeactivatedPeersNeverPermitLookupRecovery() {
        for (description in listOf("Forbidden: bot was blocked by the user", "Forbidden: user is deactivated")) {
            val reason = TelegramFailureReason.classify(403, description)
            assertEquals(TelegramFailureReason.PEER_UNAVAILABLE, reason)
            assertFalse(reason.safeMessage(403).contains("Bot-to-Bot"))
            assertEquals(TelegramFailureReason.UNKNOWN, TelegramFailureReason.classify(400, description))
            assertEquals(TelegramFailureReason.UNKNOWN, TelegramFailureReason.classify(403, "$description "))
        }
    }

    @Test fun arbitrary400And403ErrorsDoNotAccuseBotCommunicationSettings() {
        for (code in listOf(400, 403)) {
            for (description in listOf("Bad Request: message is too long", "Forbidden: another restriction", null)) {
                val reason = TelegramFailureReason.classify(code, description)
                assertEquals(TelegramFailureReason.UNKNOWN, reason)
                assertFalse(reason.safeMessage(code).contains("Bot-to-Bot"))
            }
        }
    }

    @Test fun untrustedDescriptionsAndTokenUrlsCannotReachSafeMessages() {
        val token = "123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ_123456789"
        val raw = "Bad Request: chat not found https://api.telegram.org/bot$token/sendMessage"
        for (code in listOf(0, 400, 401, 403, 404, 409, 429, 500)) {
            val reason = TelegramFailureReason.classify(code, raw)
            assertEquals(TelegramFailureReason.UNKNOWN, reason)
            assertFalse(reason.toString().contains(token))
            val message = reason.safeMessage(code, 67)
            assertFalse(message.contains(token))
            assertFalse(message.contains("https://"))
            assertFalse(message.contains(raw))
        }
    }

    @Test fun identityMismatchIsOnlyLocallyAssignedAndExplainsThePinnedIdentity() {
        for (code in listOf(400, 403)) {
            assertEquals(TelegramFailureReason.UNKNOWN,
                TelegramFailureReason.classify(code, "PEER_IDENTITY_MISMATCH"))
        }
        assertEquals("가족 명단에 저장된 봇과 조회된 봇이 달라요. 가족방 관리에서 봇 정보를 확인해 주세요.",
            TelegramFailureReason.PEER_IDENTITY_MISMATCH.safeMessage(400))
    }

    @Test fun safeGlobalMessagesRetainAuthenticationConflictAndRateLimitGuidance() {
        val reason = TelegramFailureReason.UNKNOWN
        assertTrue(reason.safeMessage(401).contains("BotFather"))
        assertTrue(reason.safeMessage(404).contains("BotFather"))
        assertTrue(reason.safeMessage(409).contains("휴대폰마다 별도 봇"))
        assertTrue(reason.safeMessage(429, 67).contains("67초"))
        assertTrue(reason.safeMessage(429).contains("30초"))
        assertTrue(reason.safeMessage(429, 0).contains("1초"))
        assertTrue(reason.safeMessage(503).contains("텔레그램에서 잠시 응답하지 않습니다"))
        assertTrue(reason.safeMessage(0).contains("인터넷 연결"))
    }
}
