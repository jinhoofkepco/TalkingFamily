package kr.family.homeway.tracking

import kr.family.homeway.data.AppRepository
import kr.family.homeway.data.TelegramReceiveService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Deliver an active location session's durable queue when the regular receiver is absent. */
internal object TrackingOutboxRelay {
    suspend fun run(repository: AppRepository) = withContext(Dispatchers.IO) {
        run(
            enabled = { repository.sharingEnabled && repository.configured && repository.isChild && !repository.demoMode },
            receiverRunning = { TelegramReceiveService.runtime.value.running },
            hasPending = repository::hasPending,
            // Repository serialization and persisted Telegram backoff are shared with every sender.
            exchange = { repository.synchronizeScheduled(0) },
        )
    }

    internal suspend fun run(
        enabled: () -> Boolean,
        receiverRunning: () -> Boolean,
        hasPending: () -> Boolean,
        exchange: suspend () -> Unit,
        pause: suspend (Long) -> Unit = { delay(it) },
    ) {
        while (currentCoroutineContext().isActive && enabled()) {
            val waitMillis = try {
                if (!receiverRunning() && hasPending()) exchange()
                5_000L
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                15_000L
            }
            pause(waitMillis)
        }
    }
}
