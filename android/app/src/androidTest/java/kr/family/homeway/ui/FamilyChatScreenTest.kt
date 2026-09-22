package kr.family.homeway.ui

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kr.family.homeway.data.FamilyChatMember
import kr.family.homeway.data.FamilyChatRoom
import kr.family.homeway.data.FamilyEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Only synthetic state and callbacks: no real account, location collection, or network requests. */
class FamilyChatScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val room = FamilyChatRoom("test-room", "우리 네 가족", listOf(
        FamilyChatMember(101, "ui_mother_bot", "엄마", "mother"),
        FamilyChatMember(102, "ui_father_bot", "아빠", "father"),
        FamilyChatMember(103, "ui_son_bot", "서인", "son"),
        FamilyChatMember(104, "ui_daughter_bot", "서아", "daughter"),
    ))

    private fun state() = UiState(role = "child", configured = true, needsOnboarding = false,
        paired = true, room = room, selfBotId = 103, roomEvents = listOf(message(0)))

    private fun actions() = UiActions(
        configure = { _, _, _ -> }, startDemo = {}, sendChat = {}, shareCurrentLocation = {},
        awardSticker = {}, requestRedemption = {}, saveReward = { _, _, _ -> }, deleteReward = {},
        approveRedemption = { _, _ -> }, setSharing = {}, refresh = {}, clearNotice = {},
        resetConfiguration = {}, switchDemoRole = {},
    )

    @Test fun roomAndPrivateConversationDispatchSeparatelyAndKeepSeparateDrafts() {
        val roomSends = mutableListOf<String>()
        val privateSends = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                FamilyChatScreen(state(), actions().copy(sendChat = privateSends::add, sendRoomChat = roomSends::add), {}, {}, {})
            }
        }
        compose.onNodeWithText("4명 · 가족 단체방").assertIsDisplayed()
        compose.onNodeWithTag("share-current-location").assertDoesNotExist()
        compose.onNodeWithTag("chat-input").performTextInput("가족방에 보내요")
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals(listOf("가족방에 보내요"), roomSends)
            assertEquals(emptyList<String>(), privateSends)
        }
        compose.onNodeWithTag("chat-input").performTextInput("가족방 초안")
        compose.onNodeWithTag("chat-room-menu").performClick()
        compose.onNodeWithTag("chat-switch-conversation").performClick()
        compose.onNodeWithTag("share-current-location").assertIsDisplayed()
        compose.onNodeWithTag("chat-input")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .performTextInput("개인 대화에 보내요")
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle { assertEquals(listOf("개인 대화에 보내요"), privateSends) }
        compose.onNodeWithTag("chat-room-menu").performClick()
        compose.onNodeWithTag("chat-switch-conversation").performClick()
        compose.onNodeWithTag("chat-input").assertTextContains("가족방 초안")
    }

    @Test fun exactChildSettingsCommandRemainsLocalInsideRoom() {
        var settingsOpened = 0
        val roomSends = mutableListOf<String>()
        val privateSends = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                FamilyChatScreen(state(), actions().copy(sendChat = privateSends::add, sendRoomChat = roomSends::add),
                    { settingsOpened++ }, {}, {})
            }
        }
        compose.onNodeWithTag("chat-input").performTextInput("설정")
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals(1, settingsOpened)
            assertEquals(emptyList<String>(), roomSends)
            assertEquals(emptyList<String>(), privateSends)
        }
        compose.onNodeWithTag("chat-input").performTextInput(" 설정 ")
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals(1, settingsOpened)
            assertEquals(listOf(" 설정 "), roomSends)
        }
    }

    @Test fun newMessageKeepsOlderReadingPositionUntilRequested() {
        val current = mutableStateOf(state().copy(roomEvents = (0 until 40).map(::message)))
        compose.setContent {
            MaterialTheme { FamilyChatScreen(current.value, actions(), {}, {}, {}) }
        }
        compose.onNodeWithTag("chat-message-message-39").assertIsDisplayed()
        compose.onNodeWithTag("chat-list").performScrollToIndex(25)
        compose.onNodeWithTag("chat-message-message-14").assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(roomEvents = current.value.roomEvents + message(40)) }
        compose.onNodeWithTag("chat-message-message-14").assertIsDisplayed()
        compose.onNodeWithTag("chat-new-messages").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat-message-message-40").assertIsDisplayed()
        compose.onNodeWithTag("chat-new-messages").assertDoesNotExist()
    }

    @Test fun incomingBatchAtLatestAppearsWithoutAnExtraTap() {
        val current = mutableStateOf(state().copy(roomEvents = (0 until 40).map(::message)))
        compose.setContent {
            MaterialTheme { FamilyChatScreen(current.value, actions(), {}, {}, {}) }
        }
        compose.onNodeWithTag("chat-message-message-39").assertIsDisplayed()
        compose.runOnIdle {
            current.value = current.value.copy(roomEvents = current.value.roomEvents + (40 until 45).map(::message))
        }
        compose.onNodeWithTag("chat-message-message-44").assertIsDisplayed()
        compose.onNodeWithTag("chat-new-messages").assertDoesNotExist()
    }

    @Test fun deliveryAcknowledgementUpdatesExistingBubbleWithoutANewMessage() {
        val sent = message(2).copy(delivery = "pending", deliveredTo = 0, recipientCount = 3)
        val current = mutableStateOf(state().copy(roomEvents = listOf(sent)))
        compose.setContent {
            MaterialTheme { FamilyChatScreen(current.value, actions(), {}, {}, {}) }
        }
        compose.onNodeWithText("0/3 전달").assertIsDisplayed()
        compose.runOnIdle {
            current.value = current.value.copy(roomEvents = listOf(sent.copy(delivery = "relayed", deliveredTo = 3)))
        }
        compose.onNodeWithText("3/3 전달").assertIsDisplayed()
        compose.onNodeWithText("0/3 전달").assertDoesNotExist()
        compose.onNodeWithTag("chat-new-messages").assertDoesNotExist()
    }

    @Test fun roomOnlyMemberCannotAccidentallySendPrivateLocationOrOpenPraise() {
        compose.setContent {
            MaterialTheme { FamilyChatScreen(state().copy(paired = false), actions(), {}, {}, {}) }
        }
        compose.onNodeWithTag("child-sticker-button").assertDoesNotExist()
        compose.onNodeWithTag("share-current-location").assertDoesNotExist()
        compose.onNodeWithTag("chat-room-menu").performClick()
        compose.onNodeWithTag("chat-switch-conversation").assertDoesNotExist()
        compose.onNodeWithText("가족방 관리").assertIsDisplayed()
    }

    @Test fun fourFamilyMembersHaveSeparateVisibleIdentitiesAndCapturePreview() {
        compose.setContent { HomewayApp(state().copy(roomEvents = (0 until 12).map(::message)), actions()) }
        compose.onNodeWithTag("chat-message-message-8").assertIsDisplayed().assert(hasAnyDescendant(hasText("엄마")))
        compose.onNodeWithTag("chat-message-message-9").assertIsDisplayed().assert(hasAnyDescendant(hasText("아빠")))
        compose.onNodeWithTag("chat-message-message-10").assertIsDisplayed().assert(hasAnyDescendant(hasText("서인 메시지 10")))
        compose.onNodeWithTag("chat-message-message-11").assertIsDisplayed().assert(hasAnyDescendant(hasText("서아")))
            .assert(hasAnyDescendant(hasContentDescription("서아 프로필")))
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "family-room-preview.png")
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun message(index: Int): FamilyEvent {
        val member = room.members[index % room.members.size]
        return FamilyEvent(
            id = "message-$index", kind = "chat", payload = JSONObject().put("text", "${member.displayName} 메시지 $index"),
            sender = if (member.relationship in setOf("mother", "father")) "guardian" else "child",
            createdAt = Instant.parse("2026-09-22T03:00:00Z").plusSeconds(index * 12L).toString(), delivery = "relayed",
            senderId = member.botId, senderName = member.displayName, roomId = room.id,
            deliveredTo = if (member.botId == 103L) 3 else null, recipientCount = if (member.botId == 103L) 3 else null,
        )
    }
}
