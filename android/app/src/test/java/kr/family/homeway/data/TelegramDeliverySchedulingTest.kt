package kr.family.homeway.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TelegramDeliverySchedulingTest {
    @Test fun backgroundProcessStartsAtSixtySecondsAndStaysThereWithoutChats() {
        val schedule = TelegramChatReceiveSchedule(false, 1_000)
        assertEquals(60_000L, schedule.delayMillis(1_000))
        assertEquals(1L, schedule.delayMillis(60_999))
        assertEquals(0L, schedule.delayMillis(61_000))
        schedule.onPollCompleted(schedule.beginPoll(), 61_020)
        assertEquals(60_000L, schedule.delayMillis(61_020))
    }

    @Test fun closingChatStartsAtFiveSecondsAndOnlyEmptyResponsesIncreaseTheInterval() {
        val schedule = TelegramChatReceiveSchedule(true, 0)
        schedule.onVisibilityChanged(false, 3_000)
        assertEquals(5_000L, schedule.delayMillis(3_000))
        var now = 8_000L
        for (interval in listOf(10_000L, 20_000L, 30_000L, 60_000L, 60_000L)) {
            assertEquals(0L, schedule.delayMillis(now))
            schedule.onPollCompleted(schedule.beginPoll(), now)
            assertEquals(interval, schedule.delayMillis(now))
            now += interval
        }
    }

    @Test fun newChatAtEveryIntervalRestartsFiveSecondsFromItsCommitAndNotPollCompletion() {
        for (emptyCount in 0..5) {
            val schedule = TelegramChatReceiveSchedule(true, 0)
            schedule.onVisibilityChanged(false, 0)
            var now = 5_000L
            repeat(emptyCount) {
                schedule.onPollCompleted(schedule.beginPoll(), now)
                now += schedule.delayMillis(now)
            }
            val poll = schedule.beginPoll()
            schedule.onNewChatCommitted(now + 100)
            schedule.onPollCompleted(poll, now + 900)
            assertEquals(4_200L, schedule.delayMillis(now + 900))
            schedule.onNewChatCommitted(now + 5_200)
            assertEquals(5_000L, schedule.delayMillis(now + 5_200))
        }
    }

    @Test fun closeDuringForegroundPollCannotLoseTheFiveSecondDeadline() {
        val schedule = TelegramChatReceiveSchedule(true, 0)
        val foregroundPoll = schedule.beginPoll()
        schedule.onVisibilityChanged(false, 1_000)
        schedule.onPollCompleted(foregroundPoll, 1_500)
        assertEquals(4_500L, schedule.delayMillis(1_500))
        assertFalse(schedule.isCurrent(foregroundPoll))
    }

    @Test fun openThenCloseDuringBackgroundRequestPreservesTheLatestTransition() {
        val schedule = TelegramChatReceiveSchedule(false, 0)
        val backgroundPoll = schedule.beginPoll()
        schedule.onVisibilityChanged(true, 60_005)
        assertEquals(0L, schedule.delayMillis(60_005))
        schedule.onVisibilityChanged(false, 60_010)
        schedule.onPollCompleted(backgroundPoll, 60_100)
        assertEquals(4_910L, schedule.delayMillis(60_100))
    }

    @Test fun foregroundStaysImmediatelyEligibleAndRepeatedSamePresenceDoesNotResetBackground() {
        val schedule = TelegramChatReceiveSchedule(true, 0)
        repeat(5) { schedule.onPollCompleted(schedule.beginPoll(), it * 10_000L) }
        assertEquals(0L, schedule.delayMillis(60_000))
        schedule.onVisibilityChanged(false, 60_000)
        schedule.onVisibilityChanged(false, 64_000)
        assertEquals(1_000L, schedule.delayMillis(64_000))
    }

    @Test fun interruptedOrFailedRequestWithoutCompletionCannotAdvanceBackgroundDeadline() {
        val schedule = TelegramChatReceiveSchedule(true, 0)
        schedule.onVisibilityChanged(false, 0)
        schedule.beginPoll() // no successful HTTP/commit callback
        assertEquals(0L, schedule.delayMillis(5_000))
        assertEquals(0L, schedule.delayMillis(20_000))
        schedule.onNewChatCommitted(20_100)
        // A later receipt exception does not call poll completion or erase this deadline.
        assertEquals(4_900L, schedule.delayMillis(20_200))
    }

    @Test fun fullPagesAndFinalPartialPageDrainWithoutConsumingTheSavedIdleInterval() {
        val schedule = TelegramChatReceiveSchedule(false, 0)
        schedule.onPollCompleted(schedule.beginPoll(), 60_000)
        schedule.onPollProgress(TelegramReceiveProgress(100), 60_000)
        assertEquals(1_000L, schedule.delayMillis(60_000))
        assertTrue(schedule.beginPoll().catchingUp)
        for ((time, count) in listOf(61_000L to 100, 62_000L to 8, 63_000L to 0, 64_000L to 0)) {
            schedule.onPollCompleted(schedule.beginPoll(), time)
            schedule.onPollProgress(TelegramReceiveProgress(count), time)
        }
        assertFalse(schedule.catchUpState(64_000).active)
        assertEquals(56_000L, schedule.delayMillis(64_000))
        assertEquals(30_000L, schedule.catchUpState(64_000).cooldownRemainingMillis)
    }

    @Test fun offlineOutgoingAndUnrelatedTelemetryDoNotStartFastReceivePolling() {
        val schedule = TelegramChatReceiveSchedule(false, 0)
        schedule.onPollCompleted(schedule.beginPoll(), 60_000)
        schedule.onPollProgress(TelegramReceiveProgress(0, pendingOutgoing = true), 60_000)
        schedule.onPollProgress(TelegramReceiveProgress(1, pendingOutgoing = true), 60_001)
        assertFalse(schedule.catchUpState(60_001).active)
        assertEquals(59_999L, schedule.delayMillis(60_001))
    }

    @Test fun confirmationProgressWithRemainingWorkStartsCatchUpButTheFinalAckAloneDoesNot() {
        val schedule = TelegramChatReceiveSchedule(false, 0)
        schedule.onPollProgress(TelegramReceiveProgress(1, acknowledgedCount = 1), 60_000)
        assertFalse(schedule.catchUpState(60_000).active)
        schedule.onPollProgress(TelegramReceiveProgress(1, acknowledgedCount = 1, pendingOutgoing = true), 60_001)
        assertTrue(schedule.catchUpState(60_001).active)
        assertEquals(1_000L, schedule.delayMillis(60_001))
    }

    @Test fun readyReceiptsDrainQuicklyButNoMoreThanTwentyPollsBeforeCooldown() {
        val schedule = TelegramChatReceiveSchedule(false, 0)
        schedule.onPollCompleted(schedule.beginPoll(), 60_000)
        repeat(TelegramChatReceiveSchedule.CATCH_UP_MAX_POLLS) { round ->
            val now = 60_000L + round * 1_000L
            schedule.onPollCompleted(schedule.beginPoll(), now)
            schedule.onPollProgress(TelegramReceiveProgress(0, pendingReceipts = true), now)
        }
        assertFalse(schedule.catchUpState(79_000).active)
        assertEquals(TelegramChatReceiveSchedule.CATCH_UP_MAX_POLLS, schedule.catchUpState(79_000).polls)
        schedule.onPollProgress(TelegramReceiveProgress(100), 80_000)
        assertFalse(schedule.catchUpState(80_000).active)
        assertEquals(40_000L, schedule.delayMillis(80_000))
        schedule.onPollProgress(TelegramReceiveProgress(100), 109_000)
        assertTrue(schedule.catchUpState(109_000).active)
        assertEquals(1_000L, schedule.delayMillis(109_000))
    }

    @Test fun slowRequestsOrTelegramBackoffCannotExtendTheThirtySecondCatchUpWindow() {
        val schedule = TelegramChatReceiveSchedule(false, 0)
        schedule.onPollCompleted(schedule.beginPoll(), 60_000)
        schedule.onPollProgress(TelegramReceiveProgress(100), 60_000)
        assertTrue(schedule.catchUpState(89_999).active)
        schedule.onPollProgress(TelegramReceiveProgress(100), 90_000)
        assertFalse(schedule.catchUpState(90_000).active)
        assertEquals(30_000L, schedule.delayMillis(90_000))
    }

    @Test fun newChatDuringBacklogKeepsItsFiveSecondDeadlineForAfterTheDrain() {
        val schedule = TelegramChatReceiveSchedule(false, 0)
        schedule.onPollCompleted(schedule.beginPoll(), 60_000)
        schedule.onPollProgress(TelegramReceiveProgress(100), 60_000)
        schedule.onNewChatCommitted(61_000)
        for ((time, count) in listOf(62_000L to 3, 63_000L to 0, 64_000L to 0)) {
            schedule.onPollCompleted(schedule.beginPoll(), time)
            schedule.onPollProgress(TelegramReceiveProgress(count), time)
        }
        assertEquals(2_000L, schedule.delayMillis(64_000))
        schedule.onPollCompleted(schedule.beginPoll(), 66_000)
        assertEquals(10_000L, schedule.delayMillis(66_000))
    }

    @Test fun openAndCloseDuringCatchUpPreserveTheNewBackgroundTransition() {
        val schedule = TelegramChatReceiveSchedule(false, 0)
        schedule.onPollProgress(TelegramReceiveProgress(100), 60_000)
        val stale = schedule.beginPoll()
        schedule.onVisibilityChanged(true, 60_500)
        schedule.onVisibilityChanged(false, 61_000)
        schedule.onPollCompleted(stale, 61_100)
        schedule.onPollProgress(TelegramReceiveProgress(0), 62_000)
        schedule.onPollProgress(TelegramReceiveProgress(0), 63_000)
        assertFalse(schedule.catchUpState(63_000).active)
        assertEquals(3_000L, schedule.delayMillis(63_000))
    }

    @Test fun deferredFetchedDocumentsKeepCatchUpWithoutPretendingAnOfflinePeerHasUpdates() {
        val schedule = TelegramChatReceiveSchedule(false, 0)
        schedule.onPollCompleted(schedule.beginPoll(), 60_000)
        schedule.onPollProgress(TelegramReceiveProgress(2, hasMoreUpdates = true), 60_000)
        assertTrue(schedule.catchUpState(60_000).active)
        assertEquals(1_000L, schedule.delayMillis(60_000))
        schedule.onPollProgress(TelegramReceiveProgress(0, hasMoreUpdates = true), 61_000)
        schedule.onPollProgress(TelegramReceiveProgress(0, hasMoreUpdates = true), 62_000)
        assertTrue(schedule.catchUpState(62_000).active)
        schedule.onPollProgress(TelegramReceiveProgress(0), 63_000)
        schedule.onPollProgress(TelegramReceiveProgress(0), 64_000)
        assertFalse(schedule.catchUpState(64_000).active)
        assertEquals(56_000L, schedule.delayMillis(64_000))
    }

    @Test fun consumedOrStalePageWithoutDeferredFreshUpdatesDoesNotEnterCatchUp() {
        val schedule = TelegramChatReceiveSchedule(false, 0)
        schedule.onPollCompleted(schedule.beginPoll(), 60_000)
        schedule.onPollProgress(TelegramReceiveProgress(0, pendingOutgoing = true, hasMoreUpdates = false), 60_000)
        assertFalse(schedule.catchUpState(60_000).active)
        assertEquals(60_000L, schedule.delayMillis(60_000))
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

    @Test fun backgroundAckWaitingDoesNotCauseOneSecondWorkerPolling() = runBlocking {
        var calls = 0
        assertFalse(TelegramOutboxDrain.run(
            configured = { true }, receiverRunning = { false }, hasPending = { true },
            retryDelayMillis = { 0 }, exchange = { calls++ }, wakeReceiver = {},
            nextDelayMillis = { 60_000 }, nowMillis = { 0 }, pause = { fail("No early receive") },
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
