package kr.family.homeway.tracking

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Retries a failed durable enqueue with the same identity, including a commit followed by an error. */
class TrackingEventWriter(
    private val enqueue: suspend (String, JSONObject, String) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun write(kind: String, payload: JSONObject) {
        val id = UUID.randomUUID().toString()
        var retryMillis = 1_000L
        while (true) {
            try {
                enqueue(kind, payload, id)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(error)
                pause(retryMillis)
                retryMillis = (retryMillis * 2).coerceAtMost(30_000L)
            }
        }
    }
}
