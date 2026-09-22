package kr.family.homeway.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.family.homeway.data.FamilyChatMember
import kr.family.homeway.data.FamilyChatRoom
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Reports synthetic navigation only. No MainActivity, real account, service or network starts. */
class ChatPresenceScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val room = FamilyChatRoom.create("시험 가족", listOf(
        FamilyChatMember(101, "presence_mother_bot", "엄마", "mother"),
        FamilyChatMember(102, "presence_child_bot", "딸", "daughter")))
    private fun state() = UiState(role = "guardian", configured = true, needsOnboarding = false,
        paired = true, room = room, selfBotId = 101)
    private fun actions() = UiActions(configure = { _, _, _ -> }, startDemo = {}, sendChat = {},
        shareCurrentLocation = {}, awardSticker = {}, requestRedemption = {}, saveReward = { _, _, _ -> },
        deleteReward = {}, approveRedemption = { _, _ -> }, setSharing = {}, refresh = {}, clearNotice = {},
        resetConfiguration = {}, switchDemoRole = {})

    @Test fun onlyTheActualChatTabReportsVisibleAndBothConversationTypesQualify() {
        var visible = false
        compose.setContent { HomewayApp(state(), actions()) { visible = it } }
        compose.runOnIdle { assertTrue(visible) }
        compose.onNodeWithTag("nav-location").performClick()
        compose.runOnIdle { assertFalse(visible) }
        compose.onNodeWithTag("nav-stickers").performClick()
        compose.runOnIdle { assertFalse(visible) }
        compose.onNodeWithTag("nav-chat").performClick()
        compose.runOnIdle { assertTrue(visible) }
        compose.onNodeWithTag("chat-room-menu").performClick()
        compose.onNodeWithTag("chat-switch-conversation").performClick()
        compose.onNodeWithText("2명 · 개인 대화").assertIsDisplayed()
        compose.runOnIdle { assertTrue(visible) }
        compose.onNodeWithTag("chat-room-menu").performClick()
        compose.onNodeWithTag("chat-open-settings").performClick()
        compose.runOnIdle { assertFalse(visible) }
        compose.onNodeWithTag("sheet-close").performClick()
        compose.runOnIdle { assertTrue(visible) }
    }

    @Test fun childPraisePopupAndStarPromptSuppressPresenceAndDisposalClearsIt() {
        val current = mutableStateOf(state().copy(role = "child", selfBotId = 102))
        val mounted = mutableStateOf(true)
        var visible = false
        compose.setContent { if (mounted.value) HomewayApp(current.value, actions()) { visible = it } }
        compose.runOnIdle { assertTrue(visible) }
        compose.onNodeWithTag("child-sticker-button").performClick()
        compose.runOnIdle { assertFalse(visible) }
        compose.onNodeWithTag("sheet-close").performClick()
        compose.runOnIdle { assertTrue(visible); current.value = current.value.copy(overlayPromptVisible = true) }
        compose.runOnIdle { assertFalse(visible); current.value = current.value.copy(overlayPromptVisible = false) }
        compose.runOnIdle { assertTrue(visible); mounted.value = false }
        compose.runOnIdle { assertFalse(visible) }
    }

    @Test fun demoAndOnboardingNeverReportAnActiveTelegramConversation() {
        val current = mutableStateOf(state())
        var visible = false
        compose.setContent { HomewayApp(current.value, actions()) { visible = it } }
        compose.runOnIdle { assertTrue(visible); current.value = current.value.copy(demoMode = true) }
        compose.runOnIdle { assertFalse(visible); current.value = UiState() }
        compose.runOnIdle { assertFalse(visible) }
    }
}
