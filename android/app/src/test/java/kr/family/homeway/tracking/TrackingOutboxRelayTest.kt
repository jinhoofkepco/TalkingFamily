package kr.family.homeway.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrackingOutboxRelayTest {
    @Test fun receiverOwnsExchangeUntilItStops() = runBlocking {
        var enabled = true
        var receiver = true
        val actions = mutableListOf<String>()
        TrackingOutboxRelay.run(
            enabled = { enabled }, receiverRunning = { receiver }, hasPending = { true },
            exchange = { actions += "exchange" },
            pause = { millis ->
                actions += "wait:$millis"
                if (receiver) receiver = false else enabled = false
            },
        )
        assertEquals(listOf("wait:5000", "exchange", "wait:5000"), actions)
    }

    @Test fun anEmptyQueueDoesNotCreateBackgroundTelegramPolling() = runBlocking {
        var enabled = true
        var polls = 0
        TrackingOutboxRelay.run(
            enabled = { enabled }, receiverRunning = { false }, hasPending = { false },
            exchange = { polls++ }, pause = { enabled = false },
        )
        assertEquals(0, polls)
    }

    @Test fun transientFailureRetriesWithoutLosingTheActiveSession() = runBlocking {
        var enabled = true
        var attempts = 0
        val delays = mutableListOf<Long>()
        TrackingOutboxRelay.run(
            enabled = { enabled }, receiverRunning = { false }, hasPending = { true },
            exchange = { if (++attempts == 1) throw IllegalStateException("temporary failure") },
            pause = { millis -> delays += millis; if (attempts == 2) enabled = false },
        )
        assertEquals(2, attempts)
        assertEquals(listOf(15_000L, 5_000L), delays)
    }

    @Test fun stoppingDuringExchangePropagatesCancellationWithoutRetry() {
        var waits = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                TrackingOutboxRelay.run(
                    enabled = { true }, receiverRunning = { false }, hasPending = { true },
                    exchange = { throw CancellationException("session stopped") },
                    pause = { waits++ },
                )
            }
        }
        assertEquals(0, waits)
    }
}
