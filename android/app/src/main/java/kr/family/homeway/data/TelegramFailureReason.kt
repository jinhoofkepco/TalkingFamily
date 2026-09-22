package kr.family.homeway.data

/** Safe error categories only: never retain or display Telegram's untrusted description. */
enum class TelegramFailureReason {
    CHAT_NOT_FOUND,
    BOT_TO_BOT_DISABLED,
    PEER_UNAVAILABLE,
    PEER_IDENTITY_MISMATCH,
    UNKNOWN;

    fun safeMessage(errorCode: Int, retryAfterSeconds: Int? = null): String = when (this) {
        CHAT_NOT_FOUND -> "상대 봇과의 대화를 찾지 못했어요. 가족방 관리에서 봇 정보를 확인해 주세요."
        BOT_TO_BOT_DISABLED -> "보내는 봇과 받는 봇의 Bot-to-Bot Communication 설정을 모두 켜 주세요."
        PEER_UNAVAILABLE -> "상대 봇이 차단되었거나 사용할 수 없어요. 가족방 관리에서 봇 상태를 확인해 주세요."
        PEER_IDENTITY_MISMATCH -> "가족 명단에 저장된 봇과 조회된 봇이 달라요. 가족방 관리에서 봇 정보를 확인해 주세요."
        UNKNOWN -> when (errorCode) {
            401, 404 -> "봇 토큰을 확인해 주세요. BotFather에서 발급한 토큰을 입력해야 합니다."
            400 -> "텔레그램이 요청을 처리하지 못했어요. 가족방의 봇 정보와 연결 상태를 확인해 주세요."
            403 -> "상대 봇에게 메시지를 보낼 권한이 없어요. 가족방의 봇 정보와 연결 상태를 확인해 주세요."
            409 -> "이 봇이 다른 앱이나 웹훅에서 사용 중입니다. 휴대폰마다 별도 봇을 사용해 주세요."
            429 -> "텔레그램 요청이 많습니다. ${(retryAfterSeconds ?: 30).coerceAtLeast(1)}초 뒤 다시 시도해 주세요."
            in 500..599 -> "텔레그램에서 잠시 응답하지 않습니다. 잠시 후 다시 시도해 주세요."
            else -> "텔레그램에 연결하지 못했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요."
        }
    }

    companion object {
        /**
         * Exact Bot API descriptions, including its status and prefix, form the allowlist.
         * Client.cpp fail_query_with_error lowercases the first letter of "User not found";
         * RPC names such as USER_BOT_TO_BOT_DISABLED retain their uppercase spelling.
         * Source: tdlib/telegram-bot-api e3e9dd8e5b3d7ab8537cd5a10dc31d5ffa8f82d1,
         * Client.cpp:73-205, check_chat_access, TdOnCheckChatCallback, and TdOnCheckUserCallback.
         * Do not broaden this to substring, case-insensitive, or status-only matching:
         * CHAT_NOT_FOUND authorizes the caller's separately verified peer resolution.
         */
        fun classify(errorCode: Int, description: String?): TelegramFailureReason = when (errorCode) {
            400 -> when (description) {
                "Bad Request: chat not found", "Bad Request: user not found",
                "Bad Request: private chat not found" -> CHAT_NOT_FOUND
                "Bad Request: USER_BOT_TO_BOT_DISABLED" -> BOT_TO_BOT_DISABLED
                else -> UNKNOWN
            }
            403 -> when (description) {
                "Forbidden: bot was blocked by the user", "Forbidden: user is deactivated" -> PEER_UNAVAILABLE
                else -> UNKNOWN
            }
            else -> UNKNOWN
        }
    }
}
