package kr.family.homeway.data

/** OS notification choices remain authoritative when the app changes its preferred sound channel. */
internal object FamilyNotificationPolicy {
    enum class Action { IGNORE, CLEAR, SILENT, AUDIBLE }

    fun action(chatVisible: Boolean, appAllowed: Boolean, legacyAllowed: Boolean,
        selectedChannelAllowed: Boolean, soundEnabled: Boolean): Action = when {
        chatVisible -> Action.CLEAR
        !appAllowed || !legacyAllowed || !selectedChannelAllowed -> Action.IGNORE
        soundEnabled -> Action.AUDIBLE
        else -> Action.SILENT
    }
}
