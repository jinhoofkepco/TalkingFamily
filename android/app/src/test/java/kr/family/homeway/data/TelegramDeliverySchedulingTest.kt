package kr.family.homeway.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TelegramDeliverySchedulingTest {
    @Test fun receiverStartsImmediatelyThenUsesLongPollingWhenIdle() {
        val schedule = TelegramReceiveSchedule()
        assertEquals(0, schedule.nextTimeoutSeconds(false, 0))
        repeat(20) { assertEquals(10, schedule.nextTimeoutSeconds(false, 0)) }
    }

    @Test fun anOfflineRecipientCannotKeepTheReceiverInFastPolling() {
        val schedule = TelegramReceiveSchedule()
        repeat(TelegramReceiveSchedule.MAX_FAST_ROUNDS) { assertEquals(0, schedule.nextTimeoutSeconds(true, 0)) }
        repeat(20) { assertEquals(10, schedule.nextTimeoutSeconds(true, 0)) }
        assertEquals(0, schedule.nextTimeoutSeconds(true, 1))
    }

    @Test fun aNewPendingQueueGetsAFreshBoundedBurstAfterThePreviousQueueDrains() {
        val schedule = TelegramReceiveSchedule()
        repeat(TelegramReceiveSchedule.MAX_FAST_ROUNDS) { schedule.nextTimeoutSeconds(true, 0) }
        assertEquals(10, schedule.nextTimeoutSeconds(false, 0))
        repeat(TelegramReceiveSchedule.MAX_FAST_ROUNDS) { assertEquals(0, schedule.nextTimeoutSeconds(true, 0)) }
        assertEquals(10, schedule.nextTimeoutSeconds(true, 0))
    }

    @Test fun workerProcessesTheAckAndNextMessageInOneBoundedRun() = runBlocking {
        var remaining = 3
        var now = 0L
        val pauses = mutableListOf<Long>()
        val complete = TelegramOutboxDrain.run(
            configured = { true }, receiverRunning = { false }, hasPending = { remaining > 0 },
            retryDelayMillis = { 0 }, exchange = { remaining-- }, wakeReceiver = { fail("No receiver") },
            nowMillis = { now }, pause = { pauses += it; now += it },
        )
        assertTrue(complete)
        assertEquals(0, remaining)
        assertEquals(listOf(1_000L, 1_000L), pauses)
    }

    @Test fun workerHandsOffToTheRunningReceiverWithoutOpeningAnotherPoll() = runBlocking {
        var woken = 0
        assertFalse(TelegramOutboxDrain.run(
            configured = { true }, receiverRunning = { true }, hasPending = { true },
            retryDelayMillis = { 0 }, exchange = { fail("Receiver owns exchange") }, wakeReceiver = { woken++ },
            pause = { fail("No worker delay needed") },
        ))
        assertEquals(1, woken)
    }

    @Test fun workerHonorsGlobalBackoffWithoutAnyNetworkOrBusyWait() = runBlocking {
        assertFalse(TelegramOutboxDrain.run(
            configured = { true }, receiverRunning = { false }, hasPending = { true },
            retryDelayMillis = { 30_000 }, exchange = { fail("Backoff forbids requests") },
            wakeReceiver = { fail("No receiver") }, pause = { fail("WorkManager schedules retry") },
        ))
    }

    @Test fun workerStopsAfterBoundedOfflineAttempts() = runBlocking {
        var calls = 0
        var now = 0L
        assertFalse(TelegramOutboxDrain.run(
            configured = { true }, receiverRunning = { false }, hasPending = { true },
            retryDelayMillis = { 0 }, exchange = { calls++ }, wakeReceiver = { fail("No receiver") },
            nowMillis = { now }, pause = { now += it },
        ))
        assertEquals(TelegramOutboxDrain.MAX_ROUNDS, calls)
        assertEquals(7_000L, now)
    }

    @Test fun workerDoesNotStartAnotherRequestAfterASlowCallUsesTheWindow() = runBlocking {
        var calls = 0
        var now = 0L
        assertFalse(TelegramOutboxDrain.run(
            configured = { true }, receiverRunning = { false }, hasPending = { true },
            retryDelayMillis = { 0 }, exchange = { calls++; now += 9_000 }, wakeReceiver = {},
            nowMillis = { now }, pause = { fail("Window already elapsed") },
        ))
        assertEquals(1, calls)
    }

    @Test fun workerHonorsBackoffReportedDuringTheDrain() = runBlocking {
        var calls = 0
        assertFalse(TelegramOutboxDrain.run(
            configured = { true }, receiverRunning = { false }, hasPending = { true },
            retryDelayMillis = { if (calls == 0) 0 else 15_000 }, exchange = { calls++ },
            wakeReceiver = {}, pause = {},
        ))
        assertEquals(1, calls)
    }

    @Test fun workerCancellationPropagatesAndEmptyWorkFinishesWithoutNetwork() = runBlocking {
        assertTrue(TelegramOutboxDrain.run(
            configured = { true }, receiverRunning = { false }, hasPending = { false }, retryDelayMillis = { 0 },
            exchange = { fail("No work") }, wakeReceiver = {},
        ))
        try {
            TelegramOutboxDrain.run(
                configured = { true }, receiverRunning = { false }, hasPending = { true }, retryDelayMillis = { 0 },
                exchange = { throw CancellationException("Stopped") }, wakeReceiver = {},
            )
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
