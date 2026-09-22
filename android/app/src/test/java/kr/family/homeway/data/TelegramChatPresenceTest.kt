package kr.family.homeway.data

import org.junit.Assert.*
import org.junit.Test

class TelegramChatPresenceTest {
    @Test fun `recomposition and an already hidden owner do not wake polling again`() {
        var changes = 0
        val tracker = TelegramChatPresenceTracker { changes++ }
        val owner = Any()
        tracker.setVisible(owner, false)
        assertFalse(tracker.visible.value)
        assertEquals(0, changes)
        tracker.setVisible(owner, true)
        tracker.setVisible(owner, true)
        assertTrue(tracker.visible.value)
        assertEquals(1, changes)
        tracker.setVisible(owner, false)
        tracker.setVisible(owner, false)
        assertFalse(tracker.visible.value)
        assertEquals(2, changes)
    }

    @Test fun `one activity closing cannot hide another resumed conversation`() {
        var changes = 0
        val tracker = TelegramChatPresenceTracker { changes++ }
        val first = Any()
        val second = Any()
        tracker.setVisible(first, true)
        tracker.setVisible(second, true)
        tracker.setVisible(first, false)
        assertTrue(tracker.visible.value)
        assertEquals(1, changes)
        tracker.setVisible(second, false)
        assertFalse(tracker.visible.value)
        assertEquals(2, changes)
    }

    @Test fun `owner identity is retained even when two owner values compare equal`() {
        data class Owner(val value: Int)
        val tracker = TelegramChatPresenceTracker {}
        val first = Owner(1)
        val second = Owner(1)
        tracker.setVisible(first, true)
        tracker.setVisible(second, true)
        tracker.setVisible(first, false)
        assertTrue(tracker.visible.value)
        tracker.setVisible(second, false)
        assertFalse(tracker.visible.value)
    }

    @Test fun `chat requires resumed interactive unlocked screen and no collapse`() {
        assertTrue(isTelegramChatVisible(true, true, true, false, false))
        assertFalse(isTelegramChatVisible(false, true, true, false, false))
        assertFalse(isTelegramChatVisible(true, false, true, false, false))
        assertFalse(isTelegramChatVisible(true, true, false, false, false))
        assertFalse(isTelegramChatVisible(true, true, true, true, false))
        assertFalse(isTelegramChatVisible(true, true, true, false, true))
    }
}
