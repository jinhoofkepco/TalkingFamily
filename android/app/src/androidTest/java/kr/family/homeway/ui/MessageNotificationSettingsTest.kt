package kr.family.homeway.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.family.homeway.data.FamilyChatMember
import kr.family.homeway.data.FamilyChatRoom
import kr.family.homeway.data.FamilyEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic state only: no actual notification, Telegram account, or device settings change. */
class MessageNotificationSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val room = FamilyChatRoom.create("시험 가족", listOf(
        FamilyChatMember(101, "notification_parent_bot", "엄마", "mother"),
        FamilyChatMember(102, "notification_child_bot", "서아", "daughter"),
    ))
    private fun state() = UiState(role = "guardian", configured = true, needsOnboarding = false,
        paired = true, room = room, selfBotId = 101, sharingEnabled = true, telegramReceiving = true)
    private fun actions() = UiActions(configure = { _, _, _ -> }, startDemo = {}, sendChat = {},
        shareCurrentLocation = {}, awardSticker = {}, requestRedemption = {}, saveReward = { _, _, _ -> },
        deleteReward = {}, approveRedemption = { _, _ -> }, setSharing = {}, refresh = {}, clearNotice = {},
        resetConfiguration = {}, switchDemoRole = {})

    private fun openSettings() {
        compose.onNodeWithTag("chat-room-menu").performClick()
        compose.onNodeWithTag("chat-open-settings").performClick()
    }

    @Test fun diagnosticBannersStayOffHomeAndMessageStatusRemainsVisible() {
        val diagnostic = "시험용 연결 오류 상세"
        val notice = "시험용 일시 안내"
        val pending = FamilyEvent(id = "pending-message", kind = "chat",
            payload = JSONObject().put("text", "가족에게 보내는 메시지"), sender = "guardian",
            createdAt = "2026-09-23T03:00:00Z", delivery = "pending", senderId = 101,
            senderName = "엄마", roomId = room.id, deliveredTo = 0, recipientCount = 1,
            deliveryError = diagnostic)
        compose.setContent { HomewayApp(state().copy(error = diagnostic, notice = notice, roomEvents = listOf(pending)), actions()) }
        compose.onNodeWithText(diagnostic).assertDoesNotExist()
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.onNodeWithContentDescription("안내 닫기").assertDoesNotExist()
        compose.onNodeWithText("0/1 전달").assertIsDisplayed()
        compose.onNodeWithTag("nav-location").performClick()
        compose.onNodeWithText(diagnostic).assertDoesNotExist()
        compose.onNodeWithTag("nav-stickers").performClick()
        compose.onNodeWithText(diagnostic).assertDoesNotExist()
        compose.onNodeWithTag("nav-chat").performClick()
        openSettings()
        compose.onNodeWithText(diagnostic).assertDoesNotExist()
        compose.onNodeWithTag("settings-family-menu-item").performScrollTo().performClick()
        // Explicit connection/permission work keeps its actionable error inside the settings form.
        compose.onNodeWithText(diagnostic).assertIsDisplayed()
    }

    @Test fun soundIsOffByDefaultAndItsOptionDoesNotChangeReceivingOrLocationSharing() {
        val current = mutableStateOf(state())
        val selections = mutableListOf<Boolean>()
        var receivingChanges = 0
        var sharingChanges = 0
        val callbacks = actions().copy(
            setMessageNotificationSoundEnabled = { enabled ->
                selections += enabled
                current.value = current.value.copy(messageNotificationSoundEnabled = enabled)
            },
            setTelegramReceiving = { receivingChanges++ },
            setSharing = { sharingChanges++ },
        )
        compose.setContent { HomewayApp(current.value, callbacks) }
        openSettings()
        compose.onNodeWithTag("settings-notifications-menu-item").performScrollTo().performClick()
        compose.onNodeWithText("대화 화면에서는 알리지 않아요. 밖에서는 알림 하나를 갱신해요.").assertIsDisplayed()
        compose.onNodeWithTag("sharing-switch").assertDoesNotExist()
        compose.onNodeWithTag("telegram-receiving-switch").assertDoesNotExist()
        compose.onNodeWithTag("message-notification-sound-switch").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("sheet-close").performClick()
        openSettings()
        compose.onNodeWithTag("settings-notifications-menu-item").performScrollTo().performClick()
        compose.onNodeWithTag("message-notification-sound-switch").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(listOf(true, false), selections)
            assertEquals(0, receivingChanges)
            assertEquals(0, sharingChanges)
            assertTrue(current.value.sharingEnabled)
            assertTrue(current.value.telegramReceiving)
        }
    }
}
