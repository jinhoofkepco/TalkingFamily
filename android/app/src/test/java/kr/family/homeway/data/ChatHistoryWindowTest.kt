package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ChatHistoryWindowTest {
    private fun event(number: Int, at: String = Instant.parse("2026-09-24T00:00:00Z").plusSeconds(number.toLong()).toString(),
        roomId: String? = "room") = FamilyEvent("message-%06d".format(number), "chat", JSONObject().put("text", "메시지 $number"),
        "child", at, "pending", roomId = roomId)

    @Test fun `first open returns just the requested page and indicates older history`() {
        val page = (800 until 1000).map { event(it) }.reversed()
        val result = ChatHistoryPaging.merge(emptyList(), page, append = false, moreAvailable = true)
        assertEquals(200, result.events.size)
        assertEquals(event(800).id, result.next?.id)
        assertEquals(page.map { it.id }.reversed(), result.events.map { it.id })
    }

    @Test fun `repeated refresh with an overlapping SQL page never expands extra old rows`() {
        var previous = (800 until 1000).map { event(it) }
        for (newest in 1000 until 1040) {
            // Querying two pages is necessary to reach the previous oldest row after one new message.
            val loaded = ((newest - 399)..newest).map { event(it) }.reversed()
            val result = ChatHistoryPaging.merge(previous, loaded, append = false, moreAvailable = true)
            assertEquals(newest - 800 + 1, result.events.size)
            assertEquals(event(800).id, result.events.first().id)
            assertEquals(event(newest).id, result.events.last().id)
            assertEquals(event(800).id, result.next?.id)
            previous = result.events
        }
    }

    @Test fun `same timestamp cursor ties preserve every id exactly once when paging and refreshing`() {
        val at = "2026-09-24T00:00:00Z"
        val all = (0 until 401).map { event(it, at) }
        val first = ChatHistoryPaging.merge(emptyList(), all.takeLast(200).reversed(), false, true)
        val next = ChatHistoryPaging.merge(first.events, all.dropLast(200).takeLast(200).reversed(), true, true)
        assertEquals(all.drop(1).map { it.id }, next.events.map { it.id })
        assertEquals(event(1, at).id, next.next?.id)
        val refresh = ChatHistoryPaging.merge(next.events, all.reversed(), false, false)
        assertEquals(next.events.map { it.id }, refresh.events.map { it.id })
        assertEquals(event(1, at).id, refresh.next?.id) // Row 0 was fetched only for overlap, not opened.
        val last = ChatHistoryPaging.merge(refresh.events, listOf(all.first()), true, false)
        assertEquals(all.map { it.id }, last.events.map { it.id })
        assertNull(last.next)
    }

    @Test fun `refreshed ACK projection replaces stale bubble without duplicate or reordered id`() {
        val previous = (0 until 400).map { event(it) }
        val refreshed = previous.map { if (it.id == event(3).id) it.copy(delivery = "relayed", recipientCount = 3, deliveredTo = 3) else it }
        val result = ChatHistoryPaging.merge(previous, refreshed.reversed(), false, false)
        assertEquals(previous.map { it.id }, result.events.map { it.id })
        assertEquals(3, result.events.single { it.id == event(3).id }.deliveredTo)
        assertEquals("relayed", result.events.single { it.id == event(3).id }.delivery)
    }

    @Test fun `large newly received batch retains an expanded boundary without retaining stale deliveries`() {
        val previous = (0 until 800).map { event(it) }
        val received = (0 until 10800).map { event(it).copy(delivery = "relayed") }.reversed()
        val result = ChatHistoryPaging.merge(previous, received, false, false)
        assertEquals(10800, result.events.size)
        assertEquals(previous.first().id, result.events.first().id)
        assertEquals(10800, result.events.map { it.id }.toSet().size)
        assertTrue(result.events.all { it.delivery == "relayed" })
        assertNull(result.next)
    }

    @Test fun `room ordering compares actual instants including fractional seconds`() {
        val boundary = event(9, "2026-09-24T00:00:00Z")
        val fractional = event(1, "2026-09-24T00:00:00.000000001Z")
        assertEquals(listOf(boundary.id, fractional.id), ChatHistoryPaging.ordered(listOf(fractional, boundary)).map { it.id })
    }

    @Test fun `private ordering uses the same millisecond and id cursor as SQLite`() {
        val lowerIdLaterNanos = event(1, "2026-09-24T00:00:00.000000009Z", roomId = null)
        val higherIdEarlierNanos = event(9, "2026-09-24T00:00:00.000000001Z", roomId = null)
        assertEquals(listOf(lowerIdLaterNanos.id, higherIdEarlierNanos.id),
            ChatHistoryPaging.ordered(listOf(higherIdEarlierNanos, lowerIdLaterNanos)).map { it.id })
    }

    @Test fun `resetting previous history does not carry an older room boundary into a new room`() {
        val old = (0 until 400).map { event(it, roomId = "old-room") }
        val nextRoom = (1000 until 1200).map { event(it, roomId = "new-room") }
        val reset = ChatHistoryPaging.merge(emptyList(), nextRoom, false, false)
        assertEquals(nextRoom.map { it.id }, reset.events.map { it.id })
        assertTrue(reset.events.none { it.id in old.map(FamilyEvent::id) })
        assertNull(reset.next)
    }
}
