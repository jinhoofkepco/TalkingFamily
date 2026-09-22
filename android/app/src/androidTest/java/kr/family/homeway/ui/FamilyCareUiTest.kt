package kr.family.homeway.ui

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kr.family.homeway.data.FamilyChatMember
import kr.family.homeway.data.FamilyChatRoom
import kr.family.homeway.data.Redemption
import kr.family.homeway.data.Reward
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic state only: no real family credentials, messages, or location collection. */
class FamilyCareUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val room = FamilyChatRoom.create("우리 네 가족", listOf(
        FamilyChatMember(101, "ui_mother_bot", "엄마", "mother"),
        FamilyChatMember(102, "ui_father_bot", "아빠", "father"),
        FamilyChatMember(103, "ui_son_bot", "민준", "son"),
        FamilyChatMember(104, "ui_daughter_bot", "서연", "daughter"),
    ), "2f46f0a2-c5e7-4e6b-b823-45a62d345ead")

    private fun parentState() = UiState(
        role = "guardian", configured = true, needsOnboarding = false, paired = false,
        room = room, selfBotId = 101, careEnabled = true, careReady = true,
        careChildren = room.members.filter { it.relationship in setOf("son", "daughter") },
        selectedChildBotId = 103, stickerBalance = 7,
        rewards = listOf(Reward("shared-reward-id", "민준의 책", 3)),
        redemptions = listOf(Redemption("son-request", "민준의 책", 3, "requested")),
    )

    private fun actions() = UiActions(
        configure = { _, _, _ -> }, startDemo = {}, sendChat = {}, shareCurrentLocation = {},
        awardSticker = {}, requestRedemption = {}, saveReward = { _, _, _ -> }, deleteReward = {},
        approveRedemption = { _, _ -> }, setSharing = {}, refresh = {}, clearNotice = {},
        resetConfiguration = {}, switchDemoRole = {},
    )

    private fun clickLazyItem(listTag: String, itemTag: String) {
        compose.onNodeWithTag(listTag).performScrollToNode(hasTestTag(itemTag))
        compose.onNodeWithTag(itemTag).performClick()
    }

    @Test fun roomOnlyParentSharesChildSelectionAcrossLocationAndPraiseTabs() {
        val state = mutableStateOf(parentState())
        compose.setContent {
            HomewayApp(state.value, actions().copy(selectCareChild = { id ->
                state.value = state.value.copy(selectedChildBotId = id,
                    stickerBalance = if (id == 103L) 7 else 2,
                    rewards = listOf(Reward("shared-reward-id", if (id == 103L) "민준의 책" else "서연의 색연필", 3)),
                    redemptions = if (id == 103L) parentState().redemptions else emptyList())
            }))
        }
        compose.onNodeWithTag("nav-location").assertIsDisplayed().performClick()
        compose.onNodeWithTag("care-child-103").assertIsSelected()
        compose.onNodeWithTag("care-child-104").performClick().assertIsSelected()
        compose.onNodeWithTag("nav-stickers").performClick()
        compose.onNodeWithTag("care-child-104").assertIsSelected()
        compose.onNodeWithTag("sticker-balance").assertTextEquals("2")
        compose.onNodeWithText("서연의 색연필").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("redemption-approve-son-request").assertDoesNotExist()
        compose.onNodeWithTag("nav-location").performClick()
        compose.onNodeWithTag("care-child-104").assertIsSelected()
    }

    @Test fun parentWithoutSnapshotSeesWaitingInsteadOfAnInventedZeroBalance() {
        compose.setContent { HomewayApp(parentState().copy(careReady = false, stickerBalance = 0), actions()) }
        compose.onNodeWithTag("nav-stickers").performClick()
        compose.onNodeWithTag("care-sync-status").assertIsDisplayed()
        compose.onNodeWithTag("sticker-balance").assertDoesNotExist()
        compose.onNodeWithTag("sticker-action").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("rewards-manage-button").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("redemption-approve-son-request").assertDoesNotExist()
    }

    @Test fun offlineChangesShowPendingAndBlockRepeatedFinancialActions() {
        val state = mutableStateOf(parentState().copy(carePending = true, careStatus = "전달 대기 · 민준 휴대폰을 기다리고 있어요."))
        val approvals = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { HomewayApp(state.value, actions().copy(approveRedemption = { id, accepted -> approvals.add(id to accepted) })) }
        compose.onNodeWithTag("nav-stickers").performClick()
        compose.onNodeWithText("전달 대기 · 민준 휴대폰을 기다리고 있어요.").assertIsDisplayed()
        compose.onNodeWithTag("sticker-action").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("redemption-approve-son-request").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(carePending = false, careStatus = null) }
        compose.onNodeWithTag("redemption-approve-son-request").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("son-request" to true), approvals) }
    }

    @Test fun changingChildClosesRewardEditorAndDiscardsTheOtherChildDraft() {
        val state = mutableStateOf(parentState())
        compose.setContent { HomewayApp(state.value, actions()) }
        compose.onNodeWithTag("nav-stickers").performClick()
        clickLazyItem("sticker-list", "rewards-manage-button")
        clickLazyItem("reward-manager-list", "reward-add")
        compose.onNodeWithTag("reward-name").performTextInput("민준의 새 약속")
        compose.runOnIdle {
            state.value = state.value.copy(selectedChildBotId = 104, stickerBalance = 2,
                rewards = listOf(Reward("shared-reward-id", "서연의 색연필", 3)), redemptions = emptyList())
        }
        compose.onNodeWithTag("reward-editor").assertDoesNotExist()
        compose.onNodeWithTag("care-child-104").assertIsSelected()
        compose.onNodeWithTag("sticker-balance").assertTextEquals("2")
        clickLazyItem("sticker-list", "rewards-manage-button")
        clickLazyItem("reward-manager-list", "reward-add")
        compose.onNodeWithTag("reward-name").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun roomOnlyChildCanShareWithParentsAndOpenOnlyTheirOwnPraise() {
        var shares = 0
        compose.setContent {
            HomewayApp(parentState().copy(role = "child", selfBotId = 103), actions().copy(shareCurrentLocation = { shares++ }))
        }
        compose.onNodeWithTag("nav-location").assertDoesNotExist()
        compose.onNodeWithTag("share-current-location").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, shares) }
        compose.onNodeWithContentDescription("현재 위치 공유 · 부모님에게만 한 번 보내요").assertIsDisplayed()
        compose.onNodeWithTag("child-sticker-button").performClick()
        compose.onNodeWithTag("care-child-selector").assertDoesNotExist()
        compose.onNodeWithTag("sticker-balance").assertTextEquals("7")
        compose.onNodeWithTag("redemption-approve-son-request").assertDoesNotExist()
    }

    @Test fun parentChildSelectorAndSeparatePraiseBoardCapturePreview() {
        compose.setContent { HomewayApp(parentState(), actions()) }
        compose.onNodeWithTag("nav-stickers").performClick()
        compose.onNodeWithTag("care-child-103").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("care-child-104").assertIsDisplayed()
        compose.onNodeWithTag("sticker-balance").assertTextEquals("7")
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "family-care-preview.png")
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun anotherParentsRewardChangeBlocksStaleSaveAndDeleteWithoutReplacingTheDraft() {
        val state = mutableStateOf(parentState().copy(rewards = listOf(Reward("shared-reward-id", "민준의 책", 3, version = 7))))
        var saved = 0
        var deleted = 0
        compose.setContent {
            HomewayApp(state.value, actions().copy(
                saveRewardVersioned = { _, _, _, _ -> saved++ },
                deleteRewardVersioned = { _, _ -> deleted++ },
            ))
        }
        compose.onNodeWithTag("nav-stickers").performClick()
        clickLazyItem("sticker-list", "rewards-manage-button")
        clickLazyItem("reward-manager-list", "reward-item-shared-reward-id")
        compose.onNodeWithTag("reward-name").performTextReplacement("내가 적던 새 약속")
        compose.onNodeWithTag("reward-save").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(rewards = listOf(Reward("shared-reward-id", "다른 부모님이 정한 책", 5, version = 8)))
        }
        compose.onNodeWithTag("reward-editor-changed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reward-name").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("내가 적던 새 약속")))
        compose.onNodeWithTag("reward-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("reward-delete").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saved); assertEquals(0, deleted) }
    }

    @Test fun rewardSaveCarriesTheVersionThatWasOpened() {
        val state = parentState().copy(rewards = listOf(Reward("shared-reward-id", "민준의 책", 3, version = 7)))
        val versions = mutableListOf<Long>()
        var legacySaves = 0
        compose.setContent {
            HomewayApp(state, actions().copy(saveReward = { _, _, _ -> legacySaves++ },
                saveRewardVersioned = { _, _, _, version -> versions.add(version) }))
        }
        compose.onNodeWithTag("nav-stickers").performClick()
        clickLazyItem("sticker-list", "rewards-manage-button")
        clickLazyItem("reward-manager-list", "reward-item-shared-reward-id")
        compose.onNodeWithTag("reward-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(7L), versions); assertEquals(0, legacySaves) }
    }
}
