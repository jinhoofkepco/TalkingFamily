package kr.family.homeway.ui

import kr.family.homeway.data.FamilyEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FamilyChatPresentationTest {
    @Test fun avatarNamesKeepDistinctSiblingsAndCompleteCharacters() {
        assertEquals("서아", chatAvatarLabel("서아"))
        assertEquals("서인", chatAvatarLabel("서인"))
        assertEquals("서아", chatAvatarLabel("김서아"))
        assertEquals("서인", chatAvatarLabel(" 김서인 "))
        assertEquals("아", chatAvatarLabel("아"))
        assertEquals("😀", chatAvatarLabel("😀"))
        assertEquals("e\u0301a", chatAvatarLabel("e\u0301a"))
    }

    private fun event(
        id: String = "first", senderId: Long? = 101L, sender: String = "guardian",
        at: String = "2026-09-22T03:00:10Z", delivery: String = "pending",
        deliveredTo: Int? = null, recipients: Int? = null,
    ) = FamilyEvent(id, "chat", JSONObject().put("text", "안녕"), sender, at, delivery,
        senderId = senderId, senderName = "엄마", roomId = "room", deliveredTo = deliveredTo, recipientCount = recipients)

    @Test fun parentsWithTheSameRoleHaveSeparateAvatarsAndNames() {
        assertFalse(sameChatGroup(event(senderId = 101), event("second", senderId = 102), room = true))
        assertTrue(sameChatGroup(event(senderId = 101), event("second", senderId = 101), room = true))
        assertFalse(sameChatGroup(event(senderId = 101), event("second", senderId = 101).copy(roomId = "other-room"), room = true))
    }

    @Test fun sameAuthorIsGroupedOnlyWithinOneMinute() {
        assertTrue(sameChatGroup(event(), event("second", at = "2026-09-22T03:00:59Z"), room = true))
        assertFalse(sameChatGroup(event(), event("second", at = "2026-09-22T03:01:00Z"), room = true))
        assertFalse(sameChatGroup(event(), event("second", at = "invalid-time"), room = true))
    }

    @Test fun unidentifiedRoomAuthorsAreNotMergedTogether() {
        assertFalse(sameChatGroup(event(senderId = null), event("second", senderId = null), room = true))
        assertFalse(sameChatGroup(null, event(), room = true))
        assertFalse(sameChatGroup(event(), null, room = true))
    }

    @Test fun existingPrivateConversationKeepsRoleBasedGrouping() {
        assertTrue(sameChatGroup(event(senderId = null), event("second", senderId = null), room = false))
        assertFalse(sameChatGroup(event(senderId = null), event("second", senderId = null, sender = "child"), room = false))
    }

    @Test fun roomDeliveryShowsActualDeviceAcknowledgementsWithoutClaimingReadStatus() {
        val partial = event(delivery = "telegram_sent", deliveredTo = 1, recipients = 3)
        assertEquals("1/3 전달", chatDeliveryText(partial, demo = false, group = true))
        assertEquals("3/3 전달", chatDeliveryText(partial.copy(deliveredTo = 3), demo = false, group = true))
        assertEquals("0/3 전달", chatDeliveryText(partial.copy(deliveredTo = 0), demo = false, group = true))
        assertEquals("텔레그램 전달", chatDeliveryText(partial, demo = false, group = false))
    }

    @Test fun pendingAndDemoRecordsDoNotInventAcknowledgements() {
        assertEquals("전송 대기", chatDeliveryText(event(), demo = false, group = true))
        assertEquals("전송 실패", chatDeliveryText(event(delivery = "failed"), demo = false, group = true))
        assertEquals("체험", chatDeliveryText(event(deliveredTo = 3, recipients = 3), demo = true, group = true))
    }
}
