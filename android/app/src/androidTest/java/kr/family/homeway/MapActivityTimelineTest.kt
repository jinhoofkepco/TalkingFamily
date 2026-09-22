package kr.family.homeway

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.family.homeway.data.FamilyEvent
import kr.family.homeway.ui.HomewayApp
import kr.family.homeway.ui.UiActions
import kr.family.homeway.ui.UiState
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Synthetic local records only; no account, sensors, or Telegram transport is started. */
class MapActivityTimelineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
