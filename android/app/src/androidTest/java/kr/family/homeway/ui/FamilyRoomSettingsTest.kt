package kr.family.homeway.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.family.homeway.data.FamilyChatMember
import kr.family.homeway.data.FamilyChatRoom
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Form and navigation tests only. Synthetic credentials never leave these callbacks. */
class FamilyRoomSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val room = FamilyChatRoom.create("우리 네 가족", listOf(
        FamilyChatMember(101, "ui_mother_bot", "엄마", "mother"),
        FamilyChatMember(102, "ui_father_bot", "아빠", "father"),
        FamilyChatMember(103, "ui_son_bot", "아들", "son"),
        FamilyChatMember(104, "ui_daughter_bot", "딸", "daughter"),
    ), "2f46f0a2-c5e7-4e6b-b823-45a62d345ead")
    private fun actions() = UiActions(
        configure = { _, _, _ -> }, startDemo = {}, sendChat = {}, shareCurrentLocation = {},
        awardSticker = {}, requestRedemption = {}, saveReward = { _, _, _ -> }, deleteReward = {},
        approveRedemption = { _, _ -> }, setSharing = {}, refresh = {}, clearNotice = {},
        resetConfiguration = {}, switchDemoRole = {},
    )

    @Test fun newPhoneCanPreviewAndExplicitlyJoinWithoutConfiguringPrivatePair() {
        val joined = mutableListOf<Pair<String, String>>()
        var privateConfigurations = 0
        val code = room.toCode()
        compose.setContent {
            HomewayApp(UiState(paired = false), actions().copy(
                configure = { _, _, _ -> privateConfigurations++ },
                joinFamilyRoom = { token, roomCode -> joined.add(token to roomCode) },
            ))
        }
        compose.onNodeWithTag("onboarding-join-room").performScrollTo().performClick()
        compose.onNodeWithTag("peer-bot-input").assertDoesNotExist()
        compose.onNodeWithTag("room-token-input").performScrollTo().performTextInput("103:OnlySynthetic_NotARealToken")
        compose.onNodeWithTag("room-code-input").performScrollTo().performTextInput(code)
        compose.onNodeWithText("우리 네 가족").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, joined.size) }
        compose.onNodeWithTag("room-join").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("room-consent").performScrollTo().performClick()
        compose.onNodeWithTag("room-join").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("103:OnlySynthetic_NotARealToken" to code), joined)
            assertEquals(0, privateConfigurations)
        }
    }

    @Test fun roomOnlyGuardianStartsInChatAndCanReachReceptionSettings() {
        compose.setContent {
            HomewayApp(UiState(role = "guardian", configured = true, needsOnboarding = false,
                paired = false, room = room, selfBotId = 101, botUsername = "@ui_mother_bot"), actions())
        }
        compose.onNodeWithText("4명 · 가족 단체방").assertIsDisplayed()
        compose.onNodeWithTag("nav-location").assertDoesNotExist()
        compose.onNodeWithTag("nav-stickers").assertDoesNotExist()
        compose.onNodeWithTag("chat-room-menu").performClick()
        compose.onNodeWithTag("chat-open-settings").performClick()
        compose.onNodeWithTag("settings-location-menu-item").assertDoesNotExist()
        compose.onNodeWithTag("settings-room-menu-item").assertIsDisplayed()
        compose.onNodeWithTag("settings-family-menu-item").performScrollTo().performClick()
        compose.onNodeWithTag("telegram-receiving-switch").performScrollTo().assertIsDisplayed()
    }

    @Test fun leavingRoomRequiresConfirmationAndDoesNotResetPrivateConnection() {
        var leaves = 0
        var resets = 0
        compose.setContent {
            MaterialTheme {
                FamilyRoomSettings(UiState(configured = true, needsOnboarding = false, room = room, selfBotId = 103),
                    actions().copy(leaveFamilyRoom = { leaves++ }, resetConfiguration = { resets++ }))
            }
        }
        compose.onNodeWithText("아들 · 나").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("room-leave").performScrollTo().performClick()
        compose.onNodeWithTag("room-leave-dialog").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, leaves) }
        compose.onNodeWithTag("room-leave-confirm").performClick()
        compose.runOnIdle { assertEquals(1, leaves); assertEquals(0, resets) }
    }
}
