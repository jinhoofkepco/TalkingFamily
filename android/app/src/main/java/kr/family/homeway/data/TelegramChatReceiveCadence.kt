package kr.family.homeway.data

/** Safe counts after durable receive commits and the bounded outgoing flush; never message contents. */
internal data class TelegramReceiveProgress(
    val updateCount: Int,
    val acknowledgedCount: Int = 0,
    val pendingReceipts: Boolean = false,
    val pendingOutgoing: Boolean = false,
)

/** Monotonic normal deadlines with a separate, bounded backlog drain. */
internal class TelegramChatReceiveSchedule(initiallyVisible: Boolean, nowMillis: Long) {
    data class Poll(val generation: Long, val visible: Boolean, val catchingUp: Boolean = false)

    data class CatchUpState(val active: Boolean, val polls: Int, val cooldownRemainingMillis: Long)

    private var visible = initiallyVisible
    private var generation = 0L
    private var intervalMillis = BACKGROUND_MAX_MILLIS
    private var nextPollAt = if (visible) nowMillis else nowMillis + intervalMillis
    private var catchUpStartedAt: Long? = null
    private var nextCatchUpAt = 0L
    private var catchUpPolls = 0
    private var emptyCatchUpPolls = 0
    private var catchUpCooldownUntil = 0L

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

    fun delayMillis(nowMillis: Long): Long {
        expireCatchUp(nowMillis)
        if (visible) return 0
        val deadline = if (catchUpStartedAt != null) nextCatchUpAt else nextPollAt
        return (deadline - nowMillis).coerceAtLeast(0)
    }
    fun beginPoll() = Poll(generation, visible, catchUpStartedAt != null)
    fun isCurrent(poll: Poll): Boolean = poll.generation == generation

    /** Only a completed HTTP response can advance the empty-result interval. */
    fun onPollCompleted(poll: Poll, nowMillis: Long) {
        // A new chat or a screen transition during this request already established a newer deadline.
        if (!isCurrent(poll) || visible || poll.catchingUp) return
        intervalMillis = when (intervalMillis) {
            5_000L -> 10_000L
            10_000L -> 20_000L
            20_000L -> 30_000L
            else -> BACKGROUND_MAX_MILLIS
        }
        nextPollAt = nowMillis + intervalMillis
    }

    /** A full page proves more may be waiting. A queued outgoing record alone proves nothing. */
    fun onPollProgress(progress: TelegramReceiveProgress, nowMillis: Long) {
        expireCatchUp(nowMillis)
        val backlog = progress.updateCount >= TELEGRAM_PAGE_SIZE || progress.pendingReceipts ||
            (progress.acknowledgedCount > 0 && progress.pendingOutgoing)
        if (catchUpStartedAt == null) {
            if (!backlog || nowMillis < catchUpCooldownUntil) return
            catchUpStartedAt = nowMillis
            catchUpPolls = 0
            emptyCatchUpPolls = 0
        }
        catchUpPolls++
        if (progress.updateCount == 0 && !progress.pendingReceipts) emptyCatchUpPolls++
        else emptyCatchUpPolls = 0
        // Allow one extra empty request for an ACK in flight, then restore the saved normal cadence.
        if (catchUpPolls >= CATCH_UP_MAX_POLLS || emptyCatchUpPolls >= CATCH_UP_MAX_EMPTY_POLLS) {
            finishCatchUp(nowMillis)
        } else nextCatchUpAt = nowMillis + CATCH_UP_INTERVAL_MILLIS
    }

    fun catchUpState(nowMillis: Long): CatchUpState {
        expireCatchUp(nowMillis)
        return CatchUpState(catchUpStartedAt != null, catchUpPolls,
            (catchUpCooldownUntil - nowMillis).coerceAtLeast(0))
    }

    private fun expireCatchUp(nowMillis: Long) {
        if (catchUpStartedAt?.let { nowMillis - it >= CATCH_UP_MAX_MILLIS } == true) finishCatchUp(nowMillis)
    }

    private fun finishCatchUp(nowMillis: Long) {
        catchUpStartedAt = null
        catchUpCooldownUntil = nowMillis + CATCH_UP_COOLDOWN_MILLIS
    }

    companion object {
        const val BACKGROUND_FIRST_MILLIS = 5_000L
        const val BACKGROUND_MAX_MILLIS = 60_000L
        const val FOREGROUND_POLL_SECONDS = 10
        const val MIN_CYCLE_MILLIS = 1_000L
        const val ERROR_RETRY_MILLIS = 15_000L
        const val TELEGRAM_PAGE_SIZE = 100
        const val CATCH_UP_INTERVAL_MILLIS = 1_000L
        const val CATCH_UP_MAX_MILLIS = 30_000L
        const val CATCH_UP_MAX_POLLS = 20
        const val CATCH_UP_MAX_EMPTY_POLLS = 2
        const val CATCH_UP_COOLDOWN_MILLIS = 30_000L
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
    fun onPollProgress(progress: TelegramReceiveProgress) = synchronized(lock) { schedule.onPollProgress(progress, now()) }
    fun catchUpState(): TelegramChatReceiveSchedule.CatchUpState = synchronized(lock) { schedule.catchUpState(now()) }
}
