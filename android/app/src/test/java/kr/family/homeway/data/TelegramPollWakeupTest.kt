package kr.family.homeway.data

import org.junit.Assert.*
import org.junit.Test

class TelegramPollWakeupTest {
    @Test fun `enqueue wakes the registered poll exactly once`() {
        val controller = TelegramPollWakeController()
        var interrupts = 0
        val poll = controller.register(controller.revision) { interrupts++ }
        controller.signal()
        controller.signal()
        assertTrue(poll.interrupted)
        assertEquals(1, interrupts)
        assertEquals(2L, controller.changes.value)
    }

    @Test fun `enqueue before registration cannot be lost`() {
        val controller = TelegramPollWakeController()
        val before = controller.revision
        controller.signal()
        val poll = controller.register(before) { error("There is no connected poll to close") }
        assertTrue(poll.interrupted)
        poll.close()
    }

    @Test fun `closing old registration cannot unregister a newer poll`() {
        val controller = TelegramPollWakeController()
        val old = controller.register(controller.revision) { }
        controller.signal()
        var newInterrupts = 0
        val next = controller.register(controller.revision) { newInterrupts++ }
        old.close()
        controller.signal()
        assertTrue(next.interrupted)
        assertEquals(1, newInterrupts)
    }

    @Test fun `completed polls do not disconnect a later send and callback errors do not lose revision`() {
        val controller = TelegramPollWakeController()
        controller.register(controller.revision) { error("Completed request must not be interrupted") }.close()
        controller.signal()
        val failing = controller.register(controller.revision) { throw IllegalStateException("socket already closed") }
        controller.signal()
        assertTrue(failing.interrupted)
        assertEquals(2L, controller.revision)
        controller.register(controller.revision) { }.close()
    }
}
