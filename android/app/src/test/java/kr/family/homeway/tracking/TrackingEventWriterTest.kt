package kr.family.homeway.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackingEventWriterTest {
    @Test fun retriesUseSameIdEvenWhenCommitSucceededButResponseFailed() = runBlocking {
        val stored = mutableSetOf<String>()
        val attempts = mutableListOf<String>()
        val failures = mutableListOf<Exception>()
        val pauses = mutableListOf<Long>()
        val payload = JSONObject().put("capturedAt", "2026-09-22T12:00:00Z")
        val writer = TrackingEventWriter(enqueue = { kind, value, id ->
            assertEquals("location", kind)
            assertSame(payload, value)
            attempts += id
            stored += id
            if (attempts.size == 1) throw IllegalStateException("Commit response lost")
        }, onFailure = { failures += it }, pause = { pauses += it })
        writer.write("location", payload)
        assertEquals(2, attempts.size)
        assertEquals(attempts.first(), attempts.last())
        assertEquals(1, stored.size)
        assertEquals(1, failures.size)
        assertEquals(listOf(1_000L), pauses)
    }

    @Test fun repeatedStorageFailureCannotReturnSuccessAndRetryBackoffIsBounded() = runBlocking {
        var attempts = 0
        var completed = false
        val pauses = mutableListOf<Long>()
        val writer = TrackingEventWriter(enqueue = { _, _, _ ->
            attempts++
            throw IllegalStateException("Disk unavailable")
        }, onFailure = {}, pause = {
            pauses += it
            if (pauses.size == 8) throw CancellationException("Service stop timeout")
        })
        try { writer.write("location", JSONObject()); completed = true } catch (_: CancellationException) { }
        assertFalse(completed)
        assertEquals(8, attempts)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), pauses)
    }

    @Test fun cancellationIsNotReportedAsStorageFailureOrRetried() = runBlocking {
        var attempts = 0
        var failures = 0
        val writer = TrackingEventWriter(enqueue = { _, _, _ ->
            attempts++
            throw CancellationException("Stopped")
        }, onFailure = { failures++ }, pause = { fail("Cancellation must not retry") })
        try { writer.write("heartbeat", JSONObject()); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(1, attempts)
        assertEquals(0, failures)
    }
}
