package kr.family.homeway

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kr.family.homeway.data.FamilyEvent
import kr.family.homeway.ui.HomewayApp
import kr.family.homeway.ui.UiActions
import kr.family.homeway.ui.UiState
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Synthetic local records only; no account, sensors, or Telegram transport is started. */
class MapActivityTimelineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun liveLocationIsVisibleWhileHistoricalPageIsStillLoading() {
        compose.setContent {
            HomewayApp(UiState(role = "guardian", configured = true, demoMode = true, needsOnboarding = false,
                latestLocation = record("live", 15, "walking"), historyLoading = true,
                historyDay = "2026-09-22"), noActions())
        }
        compose.onNodeWithTag("location-list").performScrollToNode(hasTestTag("map-timeline-controls"))
        compose.onNodeWithTag("embedded-location-map").assertExists()
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("걷는 중 추정")
        compose.onNodeWithTag("map-vertical-no-location").assertDoesNotExist()
    }

    @Test fun manualHistoricalSelectionStaysFixedWhenANewerLivePointArrives() {
        val shown = mutableStateOf(UiState(role = "guardian", configured = true, demoMode = true,
            needsOnboarding = false, locationHistory = listOf(record("five", 5, "still"), record("ten", 10, "still")),
            latestLocation = record("live", 15, "walking"), historyDay = "2026-09-22"))
        compose.setContent { HomewayApp(shown.value, noActions()) }
        compose.onNodeWithTag("location-list").performScrollToNode(hasTestTag("map-timeline-controls"))
        compose.onNodeWithContentDescription("이전 시각의 위치").performClick()
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("정지 추정 · 약 10분")
        compose.runOnIdle { shown.value = shown.value.copy(latestLocation = record("new-live", 20, "running")) }
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("정지 추정 · 약 10분")
        compose.onNodeWithText("최신", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("달리는 중 추정")
    }

    @Test fun manuallyPinnedLivePointSurvivesAnEmptyHistoryRefreshAndANewerLiveHead() {
        val shown = mutableStateOf(UiState(role = "guardian", configured = true, demoMode = true,
            needsOnboarding = false, locationHistory = listOf(record("old", 5, "still")),
            latestLocation = record("live", 15, "walking"), historyDay = "2026-09-22"))
        compose.setContent { HomewayApp(shown.value, noActions()) }
        compose.onNodeWithTag("location-list").performScrollToNode(hasTestTag("map-timeline-controls"))
        // A continuous slider value rounds to the visible live record without selecting an older point.
        compose.onNodeWithTag("map-time-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.9f) }
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("걷는 중 추정")
        compose.runOnIdle { shown.value = shown.value.copy(locationHistory = emptyList(), historyLoading = true,
            latestLocation = record("new-live", 20, "running")) }
        compose.onNodeWithTag("embedded-location-map").assertExists()
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("걷는 중 추정")
        compose.onNodeWithText("최신", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("달리는 중 추정")
    }

    private fun noActions() = UiActions(configure = { _, _, _ -> }, startDemo = {}, sendChat = {},
        shareCurrentLocation = {}, awardSticker = {}, requestRedemption = {}, saveReward = { _, _, _ -> },
        deleteReward = {}, approveRedemption = { _, _ -> }, setSharing = {}, refresh = {}, clearNotice = {},
        resetConfiguration = {}, switchDemoRole = {})

    @Test fun selectedHistoricalRecordControlsDwellAndMovingStateInsideMap() {
        val records = listOf(record("five", 5, "still"), record("ten", 10, "still"), record("moving", 15, "walking"))
        compose.setContent {
            HomewayApp(
                UiState(role = "guardian", configured = true, demoMode = true, needsOnboarding = false,
                    locationHistory = records, historyDays = listOf("2026-09-22"), historyDay = "2026-09-22"),
                UiActions(configure = { _, _, _ -> }, startDemo = {}, sendChat = {}, shareCurrentLocation = {},
                    awardSticker = {}, requestRedemption = {}, saveReward = { _, _, _ -> }, deleteReward = {},
                    approveRedemption = { _, _ -> }, setSharing = {}, refresh = {}, clearNotice = {},
                    resetConfiguration = {}, switchDemoRole = {}),
            )
        }
        compose.onNodeWithTag("location-list").performScrollToNode(hasTestTag("map-timeline-controls"))
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("걷는 중 추정")
            .assert(hasAnyAncestor(hasTestTag("embedded-location-map")))
        compose.onNodeWithTag("map-position-adjusted").assertDoesNotExist()
        compose.onNodeWithContentDescription("이전 시각의 위치").performClick()
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("정지 추정 · 약 10분")
        compose.onNodeWithTag("map-position-adjusted").assertIsDisplayed()
        compose.onNodeWithContentDescription("이전 시각의 위치").performClick()
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("정지 추정 · 약 5분")
        compose.mainClock.advanceTimeBy(600_000)
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("정지 추정 · 약 5분")
        compose.onNodeWithText("최신", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("걷는 중 추정")
        compose.onNodeWithTag("map-position-adjusted").assertDoesNotExist()
    }

    @Test fun upwardAndDownwardEventsAppearInsideMapTimeBarWithReferenceGpsClearlyIdentified() {
        showRecords(listOf(record("gps", 5, "still"),
            vertical("up", 6, "ascent_started", 2.5), vertical("down", 7, "descent_finished", -2.8)))
        compose.onNodeWithTag("location-list").performScrollToNode(hasTestTag("map-timeline-controls"))
        compose.onNodeWithTag("map-selected-vertical").assertTextEquals("내려가기 종료 · 추정")
            .assert(hasAnyAncestor(hasTestTag("embedded-location-map")))
        compose.onNodeWithTag("map-selected-height").assertTextEquals("상대 높이 -2.8m")
        compose.onNodeWithTag("map-vertical-reference").assertTextContains("상하 이동 위치는 미확인", substring = true)
        compose.onNodeWithTag("map-selected-activity").assertDoesNotExist()
        compose.onNodeWithTag("map-position-adjusted").assertDoesNotExist()
        compose.onNodeWithContentDescription("이전 시각의 위치").performClick()
        compose.onNodeWithTag("map-selected-vertical").assertTextEquals("올라가기 시작 · 추정")
        compose.onNodeWithTag("map-selected-height").assertTextEquals("상대 높이 +2.5m")
        compose.onNodeWithContentDescription("이전 시각의 위치").performClick()
        compose.onNodeWithTag("map-selected-vertical").assertDoesNotExist()
        compose.onNodeWithTag("map-vertical-reference").assertDoesNotExist()
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("정지 추정 · 약 5분")
    }

    @Test fun timelineNavigationKeepsItsPositionAndTouchSizeAcrossDifferentRecordDetails() {
        val records = listOf(record("still", 5, "still"), record("walking", 6, "walking"),
            vertical("up", 7, "ascent_started", 2.5), vertical("down", 8, "descent_finished", -2.8))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                Box(Modifier.width(320.dp)) {
                    HomewayApp(UiState(role = "guardian", configured = true, demoMode = true, needsOnboarding = false,
                        locationHistory = records, historyDays = listOf("2026-09-22"), historyDay = "2026-09-22"), noActions())
                }
            }
        }
        compose.onNodeWithTag("location-list").performScrollToNode(hasTestTag("map-time-navigation"))
        val tags = listOf("map-previous-time", "map-next-time", "map-latest-time")
        val originalBounds = tags.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val minimumSize = 48f * compose.activity.resources.displayMetrics.density
        assertTrue("Navigation buttons must not overlap", originalBounds.getValue("map-previous-time").right <=
            originalBounds.getValue("map-latest-time").left && originalBounds.getValue("map-latest-time").right <=
            originalBounds.getValue("map-next-time").left)
        for (tag in listOf("map-previous-time", "map-next-time")) {
            val bounds = originalBounds.getValue(tag)
            assertTrue("$tag must have a 48dp touch target", bounds.width >= minimumSize - 1f && bounds.height >= minimumSize - 1f)
        }
        compose.onNodeWithTag("map-selected-vertical").assertTextEquals("내려가기 종료 · 추정")
        compose.onNodeWithTag("map-selected-date").assertTextContains("2026년 9월 22일", substring = true)
        compose.onNodeWithTag("map-next-time").assertIsNotEnabled()
        saveTimelinePreview("stairs")
        for ((selectedTag, previous) in listOf("map-selected-vertical" to "올라가기 시작 · 추정",
            "map-selected-activity" to "걷는 중 추정", "map-selected-activity" to "정지 추정 · 약 5분")) {
            compose.onNodeWithTag("map-previous-time").performClick()
            compose.onNodeWithTag(selectedTag).assertTextEquals(previous)
            for (tag in tags) {
                val expected = originalBounds.getValue(tag)
                val actual = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertEquals("$tag left", expected.left, actual.left, 1f)
                assertEquals("$tag top", expected.top, actual.top, 1f)
                assertEquals("$tag right", expected.right, actual.right, 1f)
                assertEquals("$tag bottom", expected.bottom, actual.bottom, 1f)
            }
        }
        compose.onNodeWithTag("map-previous-time").assertIsNotEnabled()
        compose.onNodeWithTag("map-next-time").performClick()
        compose.onNodeWithTag("map-selected-activity").assertTextEquals("걷는 중 추정")
        saveTimelinePreview("walking")
    }

    private fun saveTimelinePreview(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "timeline-$name-0.6.10.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun verticalWithoutGpsStillHasVisibleTimeControlsAndDoesNotPretendToHaveALocation() {
        showRecords(listOf(vertical("without-gps", 6, "ascent_finished", 3.1)))
        compose.onNodeWithTag("location-list").performScrollToNode(hasTestTag("map-vertical-no-location"))
        compose.onNodeWithTag("map-vertical-no-location").assertIsDisplayed()
        compose.onNodeWithTag("location-list").performScrollToNode(hasTestTag("map-timeline-controls"))
        compose.onNodeWithTag("map-selected-vertical").assertTextEquals("올라가기 종료 · 추정")
        compose.onNodeWithTag("map-selected-height").assertTextEquals("상대 높이 +3.1m")
        compose.onNodeWithTag("map-time-slider").assertExists()
        compose.onNodeWithTag("map-vertical-reference").assertDoesNotExist()
    }

    private fun showRecords(records: List<FamilyEvent>) {
        compose.setContent {
            HomewayApp(UiState(role = "guardian", configured = true, demoMode = true, needsOnboarding = false,
                locationHistory = records, historyDays = listOf("2026-09-22"), historyDay = "2026-09-22"),
                UiActions(configure = { _, _, _ -> }, startDemo = {}, sendChat = {}, shareCurrentLocation = {},
                    awardSticker = {}, requestRedemption = {}, saveReward = { _, _, _ -> }, deleteReward = {},
                    approveRedemption = { _, _ -> }, setSharing = {}, refresh = {}, clearNotice = {},
                    resetConfiguration = {}, switchDemoRole = {}))
        }
    }

    private fun vertical(id: String, minutes: Int, phase: String, meters: Double): FamilyEvent {
        val at = "2026-09-22T01:${minutes.toString().padStart(2, '0')}:00Z"
        return FamilyEvent(id, "vertical", JSONObject().put("measuredAt", at).put("relativeMeters", meters)
            .put("phase", phase).put("confidence", "estimated").put("evidence", "barometer_steps"), "child", at, "relayed")
    }

    private fun record(id: String, minutes: Int, motion: String): FamilyEvent {
        val capturedAt = "2026-09-22T01:${minutes.toString().padStart(2, '0')}:00Z"
        val still = motion == "still"
        val latitude = if (still) 37.5501 else 37.552
        val payload = JSONObject().put("latitude", latitude).put("longitude", 126.98).put("accuracy", 10.0)
            .put("capturedAt", capturedAt).put("source", "automatic")
            .put("displayLatitude", if (still) 37.55 else latitude).put("displayLongitude", 126.98)
            .put("displayAccuracy", if (still) 30.0 else 10.0).put("positionAdjusted", still).put("motion", motion)
        if (still) payload.put("stationarySince", "2026-09-22T01:00:00Z")
        return FamilyEvent(id, "location", payload, "child", capturedAt, "relayed")
    }
}
