package kr.family.homeway.ui

import kr.family.homeway.data.FamilyEvent
import kr.family.homeway.data.Redemption
import kr.family.homeway.data.Reward
import java.time.LocalDate

data class UiState(
    val role: String = "child",
    val configured: Boolean = false,
    val demoMode: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val events: List<FamilyEvent> = emptyList(),
    val locationHistory: List<FamilyEvent> = emptyList(),
    val historyDays: List<String> = emptyList(),
    val historyDay: String = LocalDate.now().toString(),
    val historyHasMore: Boolean = false,
    val historyLoading: Boolean = false,
    val stickerBalance: Int = 0,
    val redemptions: List<Redemption> = emptyList(),
    val rewards: List<Reward> = emptyList(),
    val sharingEnabled: Boolean = false,
    val trackingStatus: String = "위치 공유 꺼짐",
    val transport: String = "연결 전",
    val botUsername: String = "",
    val peerBotUsername: String = "",
    val telegramReceiving: Boolean = false,
    val needsOnboarding: Boolean = true,
    val overlayEnabled: Boolean = false,
    val overlaySavedEnabled: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val openChatRequestId: Int = 0,
    val overlayPromptVisible: Boolean = false,
)

data class UiActions(
    val configure: (role: String, botToken: String, peerBotUsername: String) -> Unit,
    val startDemo: (role: String) -> Unit,
    val sendChat: (text: String) -> Unit,
    val shareCurrentLocation: () -> Unit,
    val awardSticker: (reason: String) -> Unit,
    val requestRedemption: (rewardId: String) -> Unit,
    val saveReward: (id: String?, name: String, cost: Int) -> Unit,
    val deleteReward: (id: String) -> Unit,
    val approveRedemption: (id: String, accepted: Boolean) -> Unit,
    val setSharing: (enabled: Boolean) -> Unit,
    val refresh: () -> Unit,
    val clearNotice: () -> Unit,
    val resetConfiguration: () -> Unit,
    val switchDemoRole: (role: String) -> Unit,
    val returnToStar: () -> Unit = {},
    val enableOverlay: () -> Unit = {},
    val disableOverlay: () -> Unit = {},
    val dismissOverlayPrompt: () -> Unit = {},
    val setTelegramReceiving: (enabled: Boolean) -> Unit = {},
    val selectHistoryDay: (String) -> Unit = {},
    val loadMoreHistory: () -> Unit = {},
)
