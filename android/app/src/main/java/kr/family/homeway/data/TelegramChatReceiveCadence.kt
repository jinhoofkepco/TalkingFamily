package kr.family.homeway.data

/** Monotonic receive deadlines. Outgoing work, ACKs and telemetry never count as chat activity. */
internal class TelegramChatReceiveSchedule(initiallyVisible: Boolean, nowMillis: Long) {
    data class Poll(val generation: Long, val visible: Boolean)

    private var visible = initiallyVisible
    private var generation = 0L
    private var intervalMillis = BACKGROUND_MAX_MILLIS
    private var nextPollAt = if (visible) nowMillis else nowMillis + intervalMillis

    fun onVisibilityChanged(next: Boolean, nowMillis: Long) {
        if (next == visible) return
        visible = next
        generation++
        intervalMillis = BACKGROUND_FIRST_MILLIS
        nextPollAt = if (next) nowMillis else nowMillis + intervalMillis
    }

    fun onNewChatCommitted(nowMillis: Long) {
        generation++
        intervalMillis = BACKGROUND_FIRST_MILLIS
        nextPollAt = if (visible) nowMillis else nowMillis + intervalMillis
    }

    fun delayMillis(nowMillis: Long): Long = if (visible) 0 else (nextPollAt - nowMillis).coerceAtLeast(0)
    fun beginPoll() = Poll(generation, visible)
    fun isCurrent(poll: Poll): Boolean = poll.generation == generation

    /** Only a completed HTTP response can advance the empty-result interval. */
    fun onPollCompleted(poll: Poll, nowMillis: Long) {
        // A new chat or a screen transition during this request already established a newer deadline.
        if (!isCurrent(poll) || visible) return
        intervalMillis = when (intervalMillis) {
            5_000L -> 10_000L
            10_000L -> 20_000L
            20_000L -> 30_000L
            else -> BACKGROUND_MAX_MILLIS
        }
        nextPollAt = nowMillis + intervalMillis
    }

    companion object {
        const val BACKGROUND_FIRST_MILLIS = 5_000L
        const val BACKGROUND_MAX_MILLIS = 60_000L
        const val FOREGROUND_POLL_SECONDS = 10
        const val MIN_CYCLE_MILLIS = 1_000L
        const val ERROR_RETRY_MILLIS = 15_000L
    }
}

/** One process-wide schedule shared by the receiver, UI fallback, worker and tracking relay. */
internal object TelegramChatReceiveCadence {
    private val lock = Any()
    private fun now() = System.nanoTime() / 1_000_000
    private val schedule = TelegramChatReceiveSchedule(false, now())

    fun onVisibilityChanged(visible: Boolean) = synchronized(lock) { schedule.onVisibilityChanged(visible, now()) }
    fun onNewChatCommitted() = synchronized(lock) { schedule.onNewChatCommitted(now()) }
    fun delayMillis(): Long = synchronized(lock) { schedule.delayMillis(now()) }
    fun beginPoll(): TelegramChatReceiveSchedule.Poll = synchronized(lock) { schedule.beginPoll() }
    fun isCurrent(poll: TelegramChatReceiveSchedule.Poll): Boolean = synchronized(lock) { schedule.isCurrent(poll) }
    fun onPollCompleted(poll: TelegramChatReceiveSchedule.Poll) = synchronized(lock) { schedule.onPollCompleted(poll, now()) }
}
