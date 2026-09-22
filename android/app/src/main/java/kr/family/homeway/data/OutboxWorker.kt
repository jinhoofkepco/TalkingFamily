package kr.family.homeway.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = AppRepository(applicationContext)
        val complete = TelegramOutboxDrain.run(
            configured = { repo.configured },
            receiverRunning = { TelegramReceiveService.runtime.value.running },
            hasPending = repo::hasPending,
            retryDelayMillis = repo::synchronizationRetryDelayMillis,
            exchange = { repo.synchronizeScheduled(0) },
            wakeReceiver = { TelegramPollWakeup.signal() },
            nextDelayMillis = { repo.receiveDelayMillis().coerceIn(TelegramOutboxDrain.STEP_MILLIS, 15_000L) },
        )
        return if (complete) Result.success() else Result.retry()
    }
}

/** A bounded ACK exchange avoids scheduling a new WorkManager retry for every next message. */
internal object TelegramOutboxDrain {
    const val MAX_ROUNDS = 8
    const val START_WINDOW_MILLIS = 8_000L
    const val STEP_MILLIS = 1_000L

    suspend fun run(
        configured: () -> Boolean,
        receiverRunning: () -> Boolean,
        hasPending: () -> Boolean,
        retryDelayMillis: () -> Long,
        exchange: suspend () -> Unit,
        wakeReceiver: () -> Unit,
        nextDelayMillis: () -> Long = { STEP_MILLIS },
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Boolean {
        val startedAt = nowMillis()
        try {
            repeat(MAX_ROUNDS) { round ->
                currentCoroutineContext().ensureActive()
                if (!configured() || !hasPending()) return true
                // Let the existing receiver own its poll and ACK loop; keep this durable work
                // as a fallback in case that visible session is stopped before delivery.
                if (receiverRunning()) {
                    wakeReceiver()
                    return false
                }
                if (retryDelayMillis() > 0) return false
                if (round > 0 && nowMillis() - startedAt >= START_WINDOW_MILLIS) return false
                exchange()
                if (!hasPending()) return true
                if (round == MAX_ROUNDS - 1 || nowMillis() - startedAt >= START_WINDOW_MILLIS) return false
                val nextDelay = nextDelayMillis().coerceAtLeast(STEP_MILLIS)
                // In background an ACK may legitimately wait for the next scheduled receive.
                // Keep the durable retry instead of polling every second while that deadline is far away.
                if (nowMillis() - startedAt + nextDelay >= START_WINDOW_MILLIS) return false
                pause(nextDelay)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
        return false
    }
}
