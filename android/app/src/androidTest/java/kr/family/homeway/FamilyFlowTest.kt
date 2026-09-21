package kr.family.homeway

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import kr.family.homeway.data.DemoState
import kr.family.homeway.data.DemoStore
import kr.family.homeway.data.LocalStore
import kr.family.homeway.overlay.FloatingStarService
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class FamilyFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule(order = 0) val clearLocalState = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                FloatingStarService.stop(context)
                FloatingStarService.setMessengerVisible(false)
            }
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW deny")
            listOf("homeway_settings", "homeway_credentials", "homeway_demo", "floating_star").forEach {
                context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
            }
            LocalStore.get(context).clear()
        }
        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                FloatingStarService.stop(context)
                FloatingStarService.setMessengerVisible(false)
            }
            context.getSharedPreferences("floating_star", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun enterDemo(guardian: Boolean = false) {
        if (guardian) compose.onNodeWithText("보호자", useUnmergedTree = true).performClick()
        compose.onNodeWithText("연결 없이 먼저 체험하기").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag(if (guardian) "nav-location" else "child-sticker-button").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("overlay-permission-prompt").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("overlay-prompt-dismiss").performClick()
        dismissNotice()
    }

    private fun dismissNotice() {
        if (compose.onAllNodesWithContentDescription("안내 닫기").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("안내 닫기").performClick()
        }
    }

    private fun send(text: String) {
        compose.onNodeWithTag("chat-input").performTextReplacement(text)
        compose.onNodeWithTag("chat-send").performClick()
        compose.waitForIdle()
    }

    private fun snapshot() = DemoStore(context).read()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Window-manager dialog/IME animations are outside the Compose test clock.
        android.os.SystemClock.sleep(650)
        compose.waitForIdle()
        val dir = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun telegramOnboardingRequiresConsentAndDoesNotRestoreSecretToken() {
        compose.onNodeWithText("가족 서버 주소").assertDoesNotExist()
        compose.onNodeWithText("가족 연결 키").assertDoesNotExist()
        compose.onNodeWithTag("connect-family").performScrollTo().assertIsNotEnabled()

        compose.onNodeWithTag("bot-token-input").performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextInput("123456:OnlyForUiTest_NotARealToken")
        compose.onNodeWithTag("peer-bot-input").performScrollTo()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
            .performTextInput("@parent_family_bot")
        compose.onNodeWithTag("connect-family").performScrollTo().assertIsNotEnabled()

        compose.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("connect-family").performScrollTo().assertIsEnabled()
        // Do not connect: the test must never submit the synthetic token to Telegram.
        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag("bot-token-input").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("peer-bot-input").performScrollTo().assertTextContains("@parent_family_bot")
        compose.onNode(isToggleable()).performScrollTo().assertIsOn()
        compose.onNodeWithTag("connect-family").performScrollTo().assertIsNotEnabled()
        screenshot("telegram-onboarding")
    }

    @Test fun childHasFullChatAndStickerPopupPreservesDraft() {
        enterDemo()
        compose.onNodeWithTag("nav-chat").assertDoesNotExist()
        compose.onNodeWithTag("nav-stickers").assertDoesNotExist()
        compose.onNodeWithText("자녀 위치").assertDoesNotExist()
        compose.onNodeWithText("우리 오는 길", useUnmergedTree = true).assertDoesNotExist()
        screenshot("child-chat")
        compose.onNodeWithTag("share-current-location").assertIsDisplayed().performClick()
        compose.onNodeWithText("체험 위치를 대화에 표시했어요. 실제로 전송하지 않았어요.").assertExists()
        dismissNotice()
        compose.onNodeWithTag("chat-input").performTextInput("아빠 기다려 줘")
        compose.onNodeWithTag("child-sticker-button").performClick()
        compose.onNodeWithTag("child-sticker-popup").assertIsDisplayed()
        compose.onNodeWithText("모은 스티커 사용하기").performScrollTo().assertIsDisplayed()
        screenshot("child-stickers")
        compose.onNodeWithTag("sheet-close").performClick()
        compose.onNodeWithTag("chat-input").assertTextContains("아빠 기다려 줘")
        val manager = compose.activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        assertTrue(manager.getRunningServices(Int.MAX_VALUE).none { it.service.className.endsWith("TrackingService") })
    }

    @Test fun onlyExactChildSettingsCommandOpensLocalMenu() {
        enterDemo()
        val before = snapshot().events.count { it.kind == "chat" }
        listOf("설정해줘", "설정!", " 설정 ").forEach { text ->
            send(text)
            compose.onNodeWithTag("settings-menu").assertDoesNotExist()
        }
        assertEquals(before + 3, snapshot().events.count { it.kind == "chat" })
        send("설정")
        compose.onNodeWithTag("settings-menu").assertIsDisplayed()
        assertEquals(before + 3, snapshot().events.count { it.kind == "chat" })
        screenshot("child-settings-menu")
        compose.onNodeWithTag("sheet-close").performClick()
        compose.onNodeWithTag("chat-input").assertIsDisplayed()
    }

    @Test fun guardianShowsInMapTimelineAndExpandableMovementDetails() {
        enterDemo(guardian = true)
        // Keep every phase on one local date, including runs just after midnight.
        val day = LocalDate.of(2026, 9, 21)
        val seed = DemoState.initial(day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant())
        context.getSharedPreferences("homeway_demo", Context.MODE_PRIVATE).edit().putString("state", seed.toString()).commit()
        lateinit var model: HomewayViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[HomewayViewModel::class.java]
            model.refresh()
            model.selectHistoryDay(day.toString())
        }
        compose.waitUntil(5000) {
            val state = model.state.value
            !state.historyLoading && state.historyDay == day.toString() && state.locationHistory.size == 6 &&
                compose.onAllNodesWithTag("embedded-location-map").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("nav-location").assertIsSelected()
        compose.onNodeWithText("우리 오는 길", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("보호자의 공간").assertDoesNotExist()
        compose.onNodeWithText("아이의 오는 길").assertDoesNotExist()
        compose.onNodeWithContentDescription("설정").assertDoesNotExist()
        compose.onNodeWithTag("location-list").performScrollToNode(hasTestTag("map-timeline-controls"))
        compose.onNodeWithTag("map-timeline-controls").assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("embedded-location-map")))
        compose.onNodeWithTag("map-selected-time").assertIsDisplayed()
        compose.onNodeWithTag("map-time-slider").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("이전 시각의 위치").assertIsDisplayed()
        compose.onNodeWithContentDescription("다음 시각의 위치").assertIsDisplayed()
        screenshot("guardian-location")
        compose.onNodeWithText("내려가기 종료").assertDoesNotExist()
        compose.onNodeWithTag("location-list").performScrollToNode(hasTestTag("history-details-toggle"))
        compose.onNodeWithTag("history-details-toggle").performClick()
        listOf("내려가기 종료", "내려가기 시작", "올라가기 종료", "올라가기 시작").forEach { title ->
            compose.onNodeWithTag("location-list").performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        compose.onNodeWithTag("nav-chat").performClick()
        val before = snapshot().events.count { it.kind == "chat" }
        send("설정")
        compose.onNodeWithTag("settings-menu").assertDoesNotExist()
        assertEquals(before + 1, snapshot().events.count { it.kind == "chat" })
    }

    @Test fun guardianManagesPromiseThenChildRequestsAndGuardianApproves() {
        enterDemo(guardian = true)
        compose.onNodeWithTag("nav-stickers").performClick()
        compose.onNodeWithText("칭찬 스티커 1개 주기").performScrollTo().performClick()
        compose.onNodeWithText("칭찬하고 싶은 일").performTextInput("약속을 잘 지켰어요")
        compose.onNodeWithText("스티커 1개 주기").performClick()
        compose.waitUntil(5000) { snapshot().stickerBalance == 9 }
        dismissNotice()
        compose.onNodeWithTag("sticker-list").performScrollToNode(hasTestTag("rewards-manage-button"))
        compose.onNodeWithTag("rewards-manage-button").performClick()
        compose.onNodeWithTag("reward-add").performClick()
        compose.onNodeWithTag("reward-name").performTextReplacement("아빠랑 자전거 타기")
        compose.onNodeWithTag("reward-cost").performTextReplacement("3")
        screenshot("guardian-promise-editor")
        compose.onNodeWithTag("reward-save").performScrollTo().performClick()
        compose.waitUntil(5000) { snapshot().rewards.any { it.name == "아빠랑 자전거 타기" } }
        val rewardId = snapshot().rewards.single { it.name == "아빠랑 자전거 타기" }.id
        compose.onNodeWithTag("reward-manager").assertIsDisplayed()
        screenshot("guardian-promises")
        compose.onNodeWithTag("sheet-close").performClick()
        switchToChild()
        compose.onNodeWithTag("child-sticker-button").performClick()
        compose.onNodeWithTag("sticker-list").performScrollToNode(hasTestTag("reward-item-$rewardId"))
        compose.onNodeWithTag("reward-item-$rewardId").performClick()
        compose.onNodeWithTag("reward-confirm").assertIsDisplayed()
        compose.onNodeWithTag("reward-cost").assertDoesNotExist()
        compose.onNodeWithTag("reward-request").performClick()
        compose.waitUntil(5000) { snapshot().redemptions.any { it.reward == "아빠랑 자전거 타기" && it.cost == 3 } }
        compose.onNodeWithTag("sheet-close").performClick()
        switchToGuardian()
        compose.onNodeWithTag("nav-stickers").performClick()
        compose.onNodeWithTag("sticker-list").performScrollToNode(hasText("사용 승인"))
        compose.onNodeWithText("사용 승인").performClick()
        compose.waitUntil(5000) { snapshot().stickerBalance == 6 }
        assertEquals("approved", snapshot().redemptions.last().status)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("nav-location").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(snapshot().rewards.any { it.id == rewardId && it.cost == 3 })
        assertEquals(6, snapshot().stickerBalance)
    }

    private fun switchToChild() {
        dismissNotice()
        compose.onNodeWithTag("sticker-list").performScrollToNode(hasTestTag("parent-family-connection"))
        compose.onNodeWithTag("parent-family-connection").performClick()
        compose.onNodeWithText("자녀 화면 체험").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("child-sticker-button").fetchSemanticsNodes().isNotEmpty() }
        dismissNotice()
    }

    private fun switchToGuardian() {
        send("설정")
        compose.onNodeWithTag("settings-family-menu-item").performClick()
        compose.onNodeWithText("보호자 화면 체험").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("nav-location").fetchSemanticsNodes().isNotEmpty() }
        dismissNotice()
    }
}
