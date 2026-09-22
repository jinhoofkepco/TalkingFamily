package kr.family.homeway.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.IdentityHashMap

/** Activity owners report only an actually open, resumed, unlocked conversation. */
internal object TelegramChatPresence {
    private val tracker = TelegramChatPresenceTracker(::onPresenceChanged)
    val visible = tracker.visible
    fun setVisible(owner: Any, visible: Boolean) = tracker.setVisible(owner, visible)

    private fun onPresenceChanged() {
        TelegramChatReceiveCadence.onVisibilityChanged(visible.value)
        TelegramPollWakeup.signal()
    }
}

internal class TelegramChatPresenceTracker(private val onChanged: () -> Unit) {
    private val lock = Any()
    private val owners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val state = MutableStateFlow(false)
    val visible = state.asStateFlow()

    fun setVisible(owner: Any, visible: Boolean) {
        val changed = synchronized(lock) {
            if (visible) owners.add(owner) else owners.remove(owner)
            val next = owners.isNotEmpty()
            if (state.value == next) false else { state.value = next; true }
        }
        // A socket interruption must never run while holding the owner lock.
        if (changed) onChanged()
    }
}

internal fun isTelegramChatVisible(
    chatScreen: Boolean,
    resumed: Boolean,
    interactive: Boolean,
    keyguardLocked: Boolean,
    closing: Boolean,
) = chatScreen && resumed && interactive && !keyguardLocked && !closing
