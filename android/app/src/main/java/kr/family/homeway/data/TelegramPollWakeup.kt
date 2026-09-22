package kr.family.homeway.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cancels only an idle getUpdates request. A sendMessage request is never registered here. */
internal class TelegramPollWakeController {
    private val lock = Any()
    private val state = MutableStateFlow(0L)
    val changes = state.asStateFlow()
    val revision: Long get() = state.value
    private var active: Registration? = null

    inner class Registration internal constructor(private val interrupt: () -> Unit) : AutoCloseable {
        @Volatile var interrupted = false
            private set
        internal fun markInterrupted() { interrupted = true }
        internal fun interruptRequest() { runCatching(interrupt) }
        override fun close() = synchronized(lock) { if (active === this) active = null }
    }

    fun register(expectedRevision: Long, interrupt: () -> Unit): Registration = synchronized(lock) {
        check(active == null) { "Only one Telegram poll may run at a time" }
        Registration(interrupt).also {
            if (expectedRevision != revision) it.markInterrupted() else active = it
        }
    }

    fun signal() {
        val prior = synchronized(lock) {
            state.value += 1
            active?.also { it.markInterrupted(); active = null }
        }
        // Never hold the coordination lock while disconnecting a socket.
        prior?.interruptRequest()
    }
}

internal object TelegramPollWakeup {
    private val controller = TelegramPollWakeController()
    val revision get() = controller.revision
    val changes get() = controller.changes
    fun signal() = controller.signal()
    fun register(expectedRevision: Long, interrupt: () -> Unit) = controller.register(expectedRevision, interrupt)
}

internal class TelegramPollInterruptedException : Exception()
