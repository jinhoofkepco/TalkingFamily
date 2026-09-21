package kr.family.homeway.ui

import kr.family.homeway.data.FamilyEvent
import kr.family.homeway.data.Redemption
import kr.family.homeway.data.Reward

data class UiState(
    val role: String = "child",
    val configured: Boolean = false,
    val demoMode: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val events: List<FamilyEvent> = emptyList(),
    val stickerBalance: Int = 0,
    val redemptions: List<Redemption> = emptyList(),
    val rewards: List<Reward> = emptyList(),
    val sharingEnabled: Boolean = false,
    val trackingStatus: String = "위치 공유 꺼짐",
    val transport: String = "연결 전",
    val pushConfigured: Boolean = false,
    val serverUrl: String = "",
    val needsOnboarding: Boolean = true,
    val overlayEnabled: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val openChatRequestId: Int = 0,
    val overlayPromptVisible: Boolean = false,
)

data class UiActions(
    val configure: (role: String, url: String, token: String) -> Unit,
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
)
