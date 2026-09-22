package kr.family.homeway.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.family.homeway.data.FamilyEvent
import kr.family.homeway.data.Redemption
import kr.family.homeway.data.Reward
import kr.family.homeway.data.ServiceNotificationSettings
import kr.family.homeway.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val Forest = Color(0xFF245B46)
private val Cream = Color(0xFFF8F6EE)
private val Ink = Color(0xFF24362D)
private val Muted = Color(0xFF657368)
private val Mint = Color(0xFFE4EFE5)
private val Gold = Color(0xFFB67918)
private val WarmGold = Color(0xFFFAE9BC)

@Composable
fun HomewayApp(state: UiState, actions: UiActions, onChatVisibilityChanged: (Boolean) -> Unit = {}) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Forest, onPrimary = Color.White, primaryContainer = Mint,
            onPrimaryContainer = Ink, background = Cream, onBackground = Ink,
            surface = Color.White, onSurface = Ink, surfaceVariant = Mint,
            onSurfaceVariant = Muted, secondary = Gold, secondaryContainer = WarmGold,
            outline = Color(0xFFBDC9BD), error = Color(0xFFAB3636),
        ),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = Cream) {
            if ((!state.configured || state.needsOnboarding) && !state.demoMode) {
                ReportChatVisibility(false, onChatVisibilityChanged)
                Onboarding(state, actions)
            } else {
                FamilyHome(state, actions, onChatVisibilityChanged)
                if (state.overlayPromptVisible) OverlayPermissionIntro(actions)
            }
        }
    }
}

@Composable
private fun ReportChatVisibility(visible: Boolean, onChanged: (Boolean) -> Unit) {
    val currentCallback by rememberUpdatedState(onChanged)
    SideEffect { currentCallback(visible) }
    DisposableEffect(Unit) { onDispose { currentCallback(false) } }
}

@Composable
private fun Onboarding(state: UiState, actions: UiActions) {
    var familyRoomFlow by rememberSaveable { mutableStateOf("") }
    if (familyRoomFlow.isNotEmpty()) {
        BackHandler { familyRoomFlow = "" }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ familyRoomFlow = "" }, Modifier.testTag("room-onboarding-back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "연결 방법으로 돌아가기") }
                Text("가족 단체방 연결", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            FamilyRoomSettings(state, actions, initialMode = familyRoomFlow)
        }
        return
    }
    var role by rememberSaveable { mutableStateOf(state.role) }
    var peerBotUsername by rememberSaveable { mutableStateOf(state.peerBotUsername) }
    var token by remember { mutableStateOf("") }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Image(painterResource(R.drawable.ic_homeway), null, Modifier.size(62.dp).clip(RoundedCornerShape(20.dp)))
        Text(stringResource(R.string.app_name), fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("대화는 가깝게,\n칭찬은 차곡차곡.", fontSize = 20.sp, lineHeight = 29.sp, color = Forest)
        Text("대화하고, 마음을 전하고, 칭찬을 모으는\n우리 가족만의 작은 공간이에요.", color = Muted, lineHeight = 23.sp)
        SectionCard {
            Text("온 가족이 한 방에서 대화해요", fontWeight = FontWeight.Bold)
            Text("엄마·아빠·아들·딸이 각자의 봇으로 함께 참여할 수 있어요. 기존 1:1 연결이 없어도 시작할 수 있어요.", fontSize = 13.sp, lineHeight = 21.sp)
            Button({ familyRoomFlow = "create" }, Modifier.fillMaxWidth().testTag("onboarding-create-room")) { Text("가족방 만들기") }
            OutlinedButton({ familyRoomFlow = "join" }, Modifier.fillMaxWidth().testTag("onboarding-join-room")) { Text("가족방 코드로 참여") }
        }
        Text("기존 방식으로 1:1 연결", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        SectionCard {
            Text("이 휴대폰은 누가 쓰나요?", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoleButton("child", role, "자녀", Icons.Outlined.Face, Modifier.weight(1f)) { role = "child" }
                RoleButton("guardian", role, "보호자", Icons.Outlined.PersonOutline, Modifier.weight(1f)) { role = "guardian" }
            }
        }
        SectionCard {
            Text("텔레그램 봇으로 가족을 연결해요", fontWeight = FontWeight.Bold)
            Text("별도의 가족 서버는 필요하지 않아요. 휴대폰마다 텔레그램 봇을 하나씩 연결해 대화와 위치를 주고받아요.", lineHeight = 23.sp)
            Text("1. 텔레그램 @BotFather에서 /newbot으로 자녀용·보호자용 봇을 각각 만드세요.", lineHeight = 23.sp)
            Text("2. 두 봇 모두 Bot Settings에서 Bot-to-Bot Communication Mode를 켜세요.", lineHeight = 23.sp)
            Text("3. 아래에 이 휴대폰 봇의 토큰과 상대방 봇의 사용자명을 넣으세요. 상대 휴대폰에서도 서로 반대로 연결하세요.", lineHeight = 23.sp)
            TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/BotFather"))) }) {
                Text("텔레그램 BotFather 열기")
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.PrivacyTip, null, tint = Forest)
                Text("처음에 함께 읽어 주세요", fontWeight = FontWeight.Bold)
            }
            Text(
                "대화·칭찬·위치 기록은 텔레그램 봇을 통해 연결된 상대 휴대폰으로 전달되고, 각 휴대폰에 저장됩니다. 텔레그램 봇 대화는 종단간 암호화되지 않습니다.",
                lineHeight = 23.sp,
            )
            Text(
                "자녀가 대화 화면에서 ‘현재 위치 공유’를 누르면 위치를 한 번 확인합니다. 자동 위치 공유는 자녀 대화에 정확히 ‘설정’을 보내고, 메뉴에서 별도로 동의하고 켤 수 있습니다.",
                lineHeight = 23.sp,
            )
            Text(
                "자동 공유를 켜면 이동 중 약 20초, 정지 중 약 5분마다 새 위치를 요청합니다. 위도·경도, 측정 시각, 정확도와 움직임 상태를 공유합니다. 기압계가 있는 기기는 기압에 따른 상대 높이 변화로 올라가기·내려가기의 시작과 종료를 추정해 공유합니다. 정확한 층수는 알 수 없습니다.",
                lineHeight = 23.sp,
            )
            Text(
                "위치는 텔레그램을 거쳐 연결된 보호자에게 전달됩니다. 자동 공유 중에는 휴대폰 알림이 표시되며 설정에서 언제든 끌 수 있습니다. 켠 설정은 앱을 다시 열어도 유지됩니다. 위치 신호·절전·통신 상태에 따라 기록과 전달이 늦어질 수 있습니다. 위치·신체 활동·알림 권한은 기능을 사용할 때 요청합니다.",
                lineHeight = 23.sp,
            )
            Text(
                "봇 토큰은 이 휴대폰에 Android Keystore로 보호해 저장합니다. 토큰을 아는 사람은 봇을 사용할 수 있으니 공유하지 마세요. 연결 해제는 이 휴대폰의 연결 정보와 앱 기록을 지웁니다. 상대 휴대폰과 텔레그램의 기록은 별도로 관리해야 합니다.",
                lineHeight = 23.sp, fontSize = 13.sp, color = Muted,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(acknowledged, { acknowledged = it })
                Text("보호자와 자녀가 위 내용을 함께 확인했어요.", Modifier.weight(1f), fontSize = 14.sp)
            }
        }
        SectionCard {
            Text("우리 가족 연결", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth().testTag("bot-token-input"), label = { Text("이 휴대폰의 텔레그램 봇 토큰") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text(if (role == "child") "BotFather에서 받은 자녀용 봇의 토큰" else "BotFather에서 받은 보호자용 봇의 토큰") },
            )
            OutlinedTextField(
                peerBotUsername, { peerBotUsername = it }, Modifier.fillMaxWidth().testTag("peer-bot-input"), label = { Text("상대방 봇 사용자명") },
                placeholder = { Text(if (role == "child") "@parent_family_bot" else "@child_family_bot") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                supportingText = { Text(if (role == "child") "보호자용 봇의 @사용자명 · 상대 토큰은 필요 없어요." else "자녀용 봇의 @사용자명 · 상대 토큰은 필요 없어요.") },
            )
            Text("화면을 닫아도 받으려면 두 휴대폰에서 ‘메시지 수신’을 켜 두세요. 수신 중에는 알림이 표시됩니다. 절전·강제 종료·네트워크 상태에 따라 수신이 늦어질 수 있어요.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
            Button(
                { actions.configure(role, token.trim(), peerBotUsername.trim()) }, Modifier.fillMaxWidth().height(52.dp).testTag("connect-family"),
                enabled = acknowledged && peerBotUsername.isNotBlank() && token.isNotBlank() && !state.loading,
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("우리 가족 연결하기", fontWeight = FontWeight.Bold)
            }
        }
        OutlinedButton({ actions.startDemo(role) }, Modifier.fillMaxWidth().height(50.dp), enabled = !state.loading) {
            Icon(Icons.Outlined.Explore, null, Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text("연결 없이 먼저 체험하기")
        }
        Text("체험 모드는 예시 화면입니다. 실제 메시지나 위치를 보내지 않아요.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RoleButton(value: String, selected: String, label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    if (value == selected) Button(onClick, modifier.height(50.dp)) {
        Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(label)
    } else OutlinedButton(onClick, modifier.height(50.dp)) {
        Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FamilyHome(state: UiState, actions: UiActions, onChatVisibilityChanged: (Boolean) -> Unit) {
    val parent = state.role == "guardian" || state.role == "parent"
    val paired = state.paired || state.demoMode
    val canUseCare = paired || state.careEnabled
    val careScope = careScopeKey(state)
    var tab by rememberSaveable(state.role, state.room?.id, canUseCare) { mutableStateOf(if (parent && paired && state.room == null) "location" else "chat") }
    var popup by rememberSaveable(state.role, careScope) { mutableStateOf("") }
    var selectedRewardId by rememberSaveable(state.role, careScope) { mutableStateOf<String?>(null) }
    var lastHandledChatRequestId by rememberSaveable { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val openPopup: (String) -> Unit = { keyboard?.hide(); popup = it }
    val chatScreen = !(parent && canUseCare && tab in setOf("location", "stickers"))
    ReportChatVisibility(chatScreen && popup.isEmpty() && !state.overlayPromptVisible &&
        state.configured && !state.demoMode, onChatVisibilityChanged)
    LaunchedEffect(state.openChatRequestId) {
        if (state.openChatRequestId > 0 && state.openChatRequestId != lastHandledChatRequestId) {
            lastHandledChatRequestId = state.openChatRequestId
            tab = "chat"
            popup = ""
        }
    }
    BackHandler(enabled = popup.isEmpty() && !state.overlayPromptVisible && state.overlayEnabled && state.overlayPermissionGranted) {
        keyboard?.hide()
        actions.returnToStar()
    }
    Scaffold(
        containerColor = Cream,
        topBar = {
            Column(Modifier.background(Cream).statusBarsPadding()) {
                if (state.demoMode && (!parent || tab != "location")) {
                    Text("체험 모드 · 실제 전송과 위치 공유는 하지 않아요", Modifier.fillMaxWidth().background(WarmGold).padding(horizontal = 20.dp, vertical = 8.dp), color = Ink, fontSize = 11.sp)
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest)
                if (state.error != null) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFFFCE9E4)).padding(start = 20.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(state.error, Modifier.weight(1f).padding(vertical = 10.dp), fontSize = 13.sp, lineHeight = 19.sp)
                        IconButton(actions.clearNotice) { Icon(Icons.Outlined.Close, "안내 닫기", Modifier.size(18.dp)) }
                    }
                }
            }
        },
        bottomBar = {
            if (parent && canUseCare) NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                NavigationBarItem(tab == "chat", { tab = "chat" }, modifier = Modifier.testTag("nav-chat"), icon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) }, label = { Text("대화") })
                NavigationBarItem(tab == "location", { tab = "location" }, modifier = Modifier.testTag("nav-location"), icon = { Icon(Icons.Outlined.LocationOn, null) }, label = { Text("자녀 위치") })
                NavigationBarItem(tab == "stickers", { tab = "stickers" }, modifier = Modifier.testTag("nav-stickers"), icon = { Icon(Icons.Outlined.Stars, null) }, label = { Text("칭찬판") })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                parent && canUseCare && tab == "stickers" -> key(careScope) {
                    StickerScreen(state, actions,
                        openRewards = { openPopup("reward-manager") },
                        selectReward = { selectedRewardId = it; openPopup("reward-confirm") },
                        openFamily = { openPopup("settings-family") },
                    )
                }
                parent && canUseCare && tab == "location" -> key(careScope) { LocationScreen(state, actions) }
                else -> FamilyChatScreen(state, actions, { openPopup("settings-menu") }, { openPopup("stickers") }, { openPopup("settings-room") })
            }
        }
    }
    if (popup.isNotEmpty()) {
        val back = when (popup) {
            "settings-location", "settings-info", "settings-family", "settings-overlay", "settings-room" -> "settings-menu"
            "first-guide" -> "settings-family"
            "reward-editor" -> "reward-manager"
            "reward-select", "reward-confirm" -> "stickers"
            else -> ""
        }
        val baseTitle = when (popup) {
            "stickers" -> "칭찬판"
            "settings-menu" -> "설정"
            "settings-location" -> "자동 위치 공유"
            "settings-info" -> "공유 정보 안내"
            "settings-family" -> "가족 연결"
            "settings-overlay" -> "별 아이콘"
            "settings-room" -> "가족 단체방"
            "first-guide" -> "처음 함께 읽기"
            "reward-manager" -> "우리의 약속"
            "reward-editor" -> if (selectedRewardId == null) "약속 추가" else "약속 수정"
            "reward-select" -> "어떤 약속으로 바꿀까요?"
            else -> "이 약속으로 바꿀까요?"
        }
        val childName = state.careChildren.firstOrNull { it.botId == state.selectedChildBotId }?.displayName
        val title = if (state.careEnabled && childName != null && popup in setOf("stickers", "reward-manager", "reward-editor", "reward-select", "reward-confirm")) "$baseTitle · $childName" else baseTitle
        ModalBottomSheet(
            onDismissRequest = { popup = "" },
            modifier = Modifier.testTag(if (popup == "stickers") "child-sticker-popup" else popup),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Cream,
        ) {
            // Keep the draggable sheet's bounds full-screen; constrain only its content.
            Column(Modifier.fillMaxWidth().fillMaxHeight(.82f)) {
                Row(Modifier.fillMaxWidth().padding(start = if (back.isEmpty()) 22.dp else 6.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (back.isNotEmpty()) IconButton({ popup = back }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "이전 메뉴로") }
                    Text(title, Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    IconButton({ popup = "" }, Modifier.testTag("sheet-close")) { Icon(Icons.Outlined.Close, "팝업 닫기") }
                }
                HorizontalDivider(color = Color(0xFFE6E8DF))
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest)
                state.error?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Box(Modifier.weight(1f)) {
                    when (popup) {
                        "stickers" -> StickerScreen(state, actions, popup = true,
                            openRewards = { popup = "reward-select" },
                            selectReward = { selectedRewardId = it; popup = "reward-confirm" },
                            openFamily = { popup = "settings-family" },
                        )
                        "settings-menu" -> SettingsMenu(state) { popup = it }
                        "settings-room" -> FamilyRoomSettings(state, actions,
                            openReceiveSettings = { popup = "settings-family" },
                            openOverlaySettings = { popup = "settings-overlay" },
                        )
                        "settings-location", "settings-info", "settings-family", "settings-overlay" -> SettingsScreen(state, actions, popup.removePrefix("settings-")) { popup = "first-guide" }
                        "first-guide" -> FirstGuide()
                        "reward-manager" -> RewardManager(state,
                            add = { selectedRewardId = null; popup = "reward-editor" },
                            edit = { selectedRewardId = it; popup = "reward-editor" },
                        )
                        "reward-editor" -> RewardEditor(state, selectedRewardId,
                            save = { name, cost, version ->
                                val versioned = actions.saveRewardVersioned
                                if (state.careEnabled && versioned != null) versioned(selectedRewardId, name, cost, version)
                                else actions.saveReward(selectedRewardId, name, cost)
                                popup = "reward-manager"
                            },
                            delete = { version ->
                                selectedRewardId?.let { id ->
                                    val versioned = actions.deleteRewardVersioned
                                    if (state.careEnabled && versioned != null) versioned(id, version)
                                    else actions.deleteReward(id)
                                }
                                popup = "reward-manager"
                            },
                        )
                        "reward-select" -> RewardSelection(state) { selectedRewardId = it; popup = "reward-confirm" }
                        "reward-confirm" -> RewardConfirmation(state, selectedRewardId) {
                            selectedRewardId?.let(actions.requestRedemption)
                            popup = "stickers"
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationScreen(state: UiState, actions: UiActions) {
    val now by produceState(Instant.now()) {
        while (true) { delay(30_000); value = Instant.now() }
    }
    val readings = state.locationHistory
    val careScope = careScopeKey(state)
    var selectedRecordId by rememberSaveable(careScope, state.historyDay) { mutableStateOf<String?>(null) }
    var focusRequest by rememberSaveable(careScope, state.historyDay) { mutableIntStateOf(0) }
    val timeline = remember(readings) { EmbeddedMapTimeline.from(readings) }
    val records = timeline.records
    val historyPoints = timeline.locations
    val selectedIndex = records.indexOfFirst { it.event.id == selectedRecordId }.takeIf { it >= 0 } ?: records.lastIndex
    val selectedRecord = records.getOrNull(selectedIndex)
    val selected = selectedRecord?.event
    val selectedMapIndex = selectedRecord?.locationIndex ?: -1
    val selectedMapPoint = historyPoints.getOrNull(selectedMapIndex)?.let(EmbeddedMapPolicy::historyPoint)
    val vertical = selectedRecord?.vertical
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var dateMenu by remember { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    var timelineInset by remember { mutableIntStateOf(132) }
    val selectPoint: (Int) -> Unit = { index ->
        records.getOrNull(index)?.event?.let { event ->
            if (selectedRecordId != event.id) { selectedRecordId = event.id; focusRequest++ }
        }
    }
    val dayIndex = state.historyDays.indexOf(state.historyDay)
    LazyColumn(Modifier.fillMaxSize().testTag("location-list"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.careEnabled) item(key = "care-child-selector") { CareChildSelector(state, actions) }
        item(key = "history-day") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ state.historyDays.getOrNull(dayIndex + 1)?.let(actions.selectHistoryDay) }, enabled = dayIndex >= 0 && dayIndex + 1 < state.historyDays.size) { Icon(Icons.Outlined.ChevronLeft, "이전 기록 날짜") }
                Box(Modifier.weight(1f)) {
                    OutlinedButton({ dateMenu = true }, Modifier.fillMaxWidth().testTag("history-date-selector"), enabled = state.historyDays.isNotEmpty()) {
                        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(historyDateLabel(state.historyDay)); Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.ExpandMore, null)
                    }
                    DropdownMenu(dateMenu, { dateMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        state.historyDays.forEach { day ->
                            DropdownMenuItem(text = { Text(historyDateLabel(day)) }, onClick = { dateMenu = false; actions.selectHistoryDay(day) })
                        }
                    }
                }
                IconButton({ state.historyDays.getOrNull(dayIndex - 1)?.let(actions.selectHistoryDay) }, enabled = dayIndex > 0) { Icon(Icons.Outlined.ChevronRight, "다음 기록 날짜") }
            }
            if (state.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest)
        }
        item {
            if (selected == null) EmptyCard(Icons.Outlined.LocationOn,
                if (state.historyLoading) "기록을 불러오고 있어요" else if (state.historyHasMore) "불러온 기록에는 위치가 없어요" else "이 날짜의 위치 기록이 없어요",
                if (state.historyHasMore) "아래의 ‘이전 기록 더 보기’에서 앞선 위치를 찾아볼 수 있어요." else "자녀가 공유한 위치를 날짜별로 모아 보여 드려요.")
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().height(500.dp).testTag("embedded-location-map")) {
                    Box(Modifier.fillMaxSize()) {
                        if (selectedMapPoint != null) key(careScope, state.historyDay) {
                            EmbeddedLocationMap(selectedMapPoint.location.latitude, selectedMapPoint.location.longitude,
                                selectedMapPoint.location.accuracy ?: Double.NaN, Modifier.fillMaxSize(),
                                focusToken = "$careScope/${state.historyDay}/${selectedRecordId?.takeIf { it == selected.id } ?: "latest"}/$focusRequest",
                                history = historyPoints, selectedHistoryIndex = selectedMapIndex, timelineInsetDp = timelineInset)
                        } else Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 24.dp, vertical = 68.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Height, null, Modifier.size(40.dp), tint = Gold)
                            Text("상하 이동 추정 기록", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text("불러온 기록에 이 시각 근처 GPS가 없어 지도 위치를 표시하지 않아요.",
                                color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 21.sp,
                                modifier = Modifier.testTag("map-vertical-no-location"))
                        }
                        Surface(shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = .97f), shadowElevation = 3.dp,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth()
                                .onSizeChanged { size -> timelineInset = with(density) { size.height.toDp().value.roundToInt() } + 24 }
                                .testTag("map-timeline-controls")) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton({ selectPoint(selectedIndex - 1) }, enabled = selectedIndex > 0, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Outlined.ChevronLeft, "이전 시각의 위치")
                                    }
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(historyClock(eventTime(selected)), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.testTag("map-selected-time"))
                                        if (vertical != null) {
                                            Text(vertical.label, fontSize = 12.sp, color = Forest, modifier = Modifier.testTag("map-selected-vertical"))
                                            Text("상대 높이 ${if (vertical.relativeMeters >= 0) "+" else ""}${String.format(Locale.KOREA, "%.1f", vertical.relativeMeters)}m",
                                                fontSize = 11.sp, color = Muted, modifier = Modifier.testTag("map-selected-height"))
                                        } else selectedMapPoint?.activityLabel()?.let { label ->
                                            Text(label, fontSize = 11.sp, color = Forest, modifier = Modifier.testTag("map-selected-activity"))
                                        }
                                        Text("${selectedIndex + 1} / ${records.size}${if (state.historyHasMore) "+" else ""} ${if (records.any { it.vertical != null }) "기록" else "위치"}", fontSize = 11.sp, color = Muted)
                                    }
                                    IconButton({ selectPoint(selectedIndex + 1) }, enabled = selectedIndex < records.lastIndex, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Outlined.ChevronRight, "다음 시각의 위치")
                                    }
                                    TextButton({
                                        selectedRecordId = null; focusRequest++
                                        val latestDay = state.historyDays.firstOrNull()
                                        if (latestDay != null && latestDay != state.historyDay) actions.selectHistoryDay(latestDay)
                                    }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("최신") }
                                }
                                if (vertical != null && selectedMapPoint != null) {
                                    Text("참고 GPS ${historyClock(Instant.ofEpochMilli(selectedMapPoint.measuredAtMillis).toString())} · 상하 이동 위치는 미확인",
                                        fontSize = 10.sp, color = Muted, modifier = Modifier.testTag("map-vertical-reference"))
                                }
                                if (vertical == null && selectedMapPoint?.positionAdjusted == true) {
                                    Text("GPS 흔들림 보정", fontSize = 10.sp, color = Muted,
                                        modifier = Modifier.align(Alignment.CenterHorizontally).testTag("map-position-adjusted"))
                                }
                                Slider(value = selectedIndex.coerceAtLeast(0).toFloat(), onValueChange = { selectPoint(it.roundToInt()) },
                                    valueRange = 0f..records.lastIndex.coerceAtLeast(1).toFloat(),
                                    steps = if (records.size in 3..102) records.size - 2 else 0, enabled = records.size > 1,
                                    modifier = Modifier.fillMaxWidth().height(32.dp).semantics {
                                        contentDescription = "위치 기록 시간"
                                        stateDescription = historyClock(eventTime(selected))
                                    }.testTag("map-time-slider"))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(displayTime(eventTime(records.first().event)), fontSize = 11.sp, color = Muted)
                                    Text(if (records.any { it.vertical != null }) "위치·상하 이동 기록" else "시간 막대로 위치 보기", fontSize = 11.sp, color = Forest)
                                    Text(displayTime(eventTime(records.last().event)), fontSize = 11.sp, color = Muted)
                                }
                            }
                        }
                    }
                }
                Text("점선은 기록된 위치를 이은 선이며 실제 이동 경로와 다를 수 있어요.", fontSize = 11.sp, color = Muted, lineHeight = 17.sp)
                if (state.historyHasMore) Text("이 날짜의 이전 기록은 ‘이전 기록 더 보기’로 지도에 추가할 수 있어요.", fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
                if (vertical != null) {
                    vertical.evidenceLabel?.let { Text(it, fontSize = 12.sp, color = Forest, modifier = Modifier.testTag("map-vertical-evidence")) }
                    Text("기압과 움직임으로 추정한 상하 이동이에요. 계단·엘리베이터 여부와 정확한 층수는 알 수 없어요.", fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
                }
                val displayed = selectedMapPoint?.location
                if (displayed != null) Text(coordinateText(displayed.latitude, displayed.longitude), fontSize = 12.sp, color = Muted)
                val rawAccuracy = historyPoints.getOrNull(selectedMapIndex)?.accuracy?.takeIf { it.isFinite() && it >= 0.0 }
                rawAccuracy?.let { accuracy ->
                    Text("GPS 측정 오차 약 ${accuracy.roundToInt()}m", fontSize = 12.sp, color = Muted)
                }
                if (vertical == null && selectedMapPoint?.stationarySinceMillis != null && displayed?.accuracy != null) {
                    Text("보정 표시 범위 약 ${displayed.accuracy.roundToInt()}m · 정지 기준점과의 차이 포함", fontSize = 12.sp, color = Muted)
                }
                val locationAge = selectedMapPoint?.let { elapsedMinutes(Instant.ofEpochMilli(it.measuredAtMillis).toString(), now) }
                if (!state.demoMode && locationAge != null && locationAge >= 10) {
                    Text("저장된 과거 위치입니다. 현재 위치와 다를 수 있어요.", fontSize = 12.sp, color = Gold, lineHeight = 19.sp)
                }
                if (state.demoMode) Text("체험 위치 · 실제 위치를 수집하지 않아요", fontSize = 12.sp, color = Muted)
            }
        }
        item {
            OutlinedButton({ detailsExpanded = !detailsExpanded }, Modifier.fillMaxWidth().testTag("history-details-toggle")) {
                Text("기록 상세 ${readings.size}${if (state.historyHasMore) "+" else ""}개", Modifier.weight(1f))
                Icon(if (detailsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (detailsExpanded) "기록 상세 접기" else "기록 상세 펼치기")
            }
        }
        if (readings.isEmpty() && !state.historyLoading) item { Text("이 날짜에 받은 기록이 아직 없어요.", color = Muted, fontSize = 13.sp) }
        if (detailsExpanded) readings.groupBy { historyHour(eventTime(it)) }.forEach { (hour, events) ->
            item(key = "hour-$hour") { Text(hour, fontWeight = FontWeight.Bold, color = Forest, modifier = Modifier.padding(top = 8.dp)) }
            items(events, key = { it.id }) { event ->
                TimelineCard(event, selected = event.id == selected?.id, onSelect = if (records.any { it.event.id == event.id }) ({
                    selectedRecordId = event.id
                    focusRequest++
                    scope.launch { listState.animateScrollToItem(if (state.careEnabled) 2 else 1) }
                }) else null)
            }
        }
        if (state.historyHasMore) item { OutlinedButton(actions.loadMoreHistory, Modifier.fillMaxWidth(), enabled = !state.historyLoading) { Text("이전 기록 더 보기") } }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun TimelineCard(event: FamilyEvent, selected: Boolean = false, onSelect: (() -> Unit)? = null) {
    val vertical = event.kind == "vertical"
    val phase = event.payload.optString("phase")
    val label = when (phase) {
        "ascent_started" -> "올라가기 시작"
        "ascent_finished" -> "올라가기 종료"
        "descent_started" -> "내려가기 시작"
        "descent_finished" -> "내려가기 종료"
        else -> "높이 변화"
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(displayTime(eventTime(event)), Modifier.width(45.dp).padding(top = 16.dp), fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Muted)
        Box(Modifier.width(12.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(Mint))
            Box(Modifier.padding(top = 21.dp).size(10.dp).background(if (selected) Forest else Gold, CircleShape))
        }
        Surface(onClick = { onSelect?.invoke() }, enabled = onSelect != null, shape = RoundedCornerShape(18.dp), color = if (selected) Mint else Color.White,
            border = BorderStroke(1.dp, if (selected) Forest else Color(0xFFE0E7DE)), modifier = Modifier.weight(1f).testTag("history-record-${event.id}")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(if (vertical) label else if (event.payload.optString("source") == "automatic") "자동 위치 기록" else "직접 공유한 위치", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (vertical) Pill("추정", gold = true)
                }
                Text(historyClock(eventTime(event)), color = Muted, fontSize = 12.sp)
                if (vertical) {
                    val delta = event.payload.optDouble("relativeMeters", Double.NaN)
                    if (delta.isFinite()) Text("상대 높이 변화 ${if (delta >= 0) "+" else ""}${String.format(Locale.KOREA, "%.1f", delta)}m", fontSize = 12.sp, color = Muted)
                    event.verticalMapActivity()?.evidenceLabel?.let { Text(it, fontSize = 11.sp, color = Muted) }
                } else {
                    locationPoint(event)?.let { Text(coordinateText(it.first, it.second), fontSize = 12.sp, color = Muted) }
                    val accuracy = event.payload.optDouble("accuracy", Double.NaN)
                    if (accuracy.isFinite()) Text("위치 오차 약 ${accuracy.toInt()}m", fontSize = 12.sp, color = Muted)
                }
                Text(if (selected) { if (vertical) "시간 막대에서 선택됨" else "지도에 표시 중" } else if (onSelect != null) "눌러서 시각 보기" else "높이 변화 추정", fontSize = 11.sp, color = if (selected) Forest else Muted)
            }
        }
    }
}

private fun historyDateLabel(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREA))
}.getOrDefault("날짜 선택")

private fun historyHour(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("HH시", Locale.KOREA).withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("시각 정보 없음")

private fun historyClock(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.KOREA).withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

private fun careScopeKey(state: UiState): String =
    if (state.careEnabled) "${state.room?.id}/${state.selectedChildBotId}" else "private-${state.role}"

private fun careActionsEnabled(state: UiState): Boolean =
    !state.loading && (!state.careEnabled || (state.careReady && !state.carePending && state.selectedChildBotId != null))

@Composable
private fun CareChildSelector(state: UiState, actions: UiActions) {
    if (!state.careEnabled || state.role !in setOf("guardian", "parent")) return
    Column(Modifier.fillMaxWidth().testTag("care-child-selector"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("자녀 선택", fontSize = 12.sp, color = Muted)
        if (state.careChildren.isEmpty()) {
            Text("가족방 명단에 아들 또는 딸로 등록된 자녀가 없어요.", fontSize = 13.sp, color = Muted)
        } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.careChildren.forEach { child ->
                FilterChip(
                    selected = child.botId == state.selectedChildBotId,
                    onClick = { actions.selectCareChild(child.botId) },
                    label = { Text(child.displayName) },
                    modifier = Modifier.testTag("care-child-${child.botId}"),
                    leadingIcon = if (child.botId == state.selectedChildBotId) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null,
                )
            }
        }
    }
}

@Composable
private fun CareSyncStatus(state: UiState) {
    if (!state.careEnabled) return
    val message = state.careStatus ?: when {
        !state.careReady -> "자녀의 칭찬판을 기다리고 있어요. 자녀 휴대폰에서 앱을 업데이트하고 메시지 수신을 켜 주세요."
        state.carePending -> "전달 대기 · 자녀 휴대폰에서 확인하면 부모의 칭찬판에도 함께 반영돼요."
        else -> null
    }
    if (message != null) Surface(color = WarmGold, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("care-sync-status")) {
        Text(message, Modifier.padding(14.dp), fontSize = 13.sp, lineHeight = 20.sp, color = Ink)
    }
}

@Composable
private fun StickerScreen(
    state: UiState,
    actions: UiActions,
    popup: Boolean = false,
    openRewards: () -> Unit,
    selectReward: (String) -> Unit,
    openFamily: () -> Unit,
) {
    var awardDialog by rememberSaveable(careScopeKey(state)) { mutableStateOf(false) }
    val parent = state.role == "guardian" || state.role == "parent"
    val ready = !state.careEnabled || state.careReady
    val actionsEnabled = careActionsEnabled(state)
    LazyColumn(Modifier.fillMaxSize().testTag("sticker-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (state.careEnabled && parent) item(key = "care-child-selector") { CareChildSelector(state, actions) }
        if (!popup) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("차곡차곡, 칭찬판", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("작은 노력도 반짝이는 별이 돼요.", color = Muted, fontSize = 13.sp)
            }
        }
        if (state.careEnabled) item { CareSyncStatus(state) }
        if (ready) item {
            Surface(color = Forest, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("지금 모은 칭찬", color = Color(0xFFDCEBDF), fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(state.stickerBalance.toString(), modifier = Modifier.testTag("sticker-balance"), fontSize = 52.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(" 개", Modifier.padding(bottom = 8.dp), fontSize = 20.sp, color = Color.White)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(48.dp), tint = WarmGold)
                    }
                    repeat(2) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            repeat(6) { column ->
                                val filled = row * 6 + column < state.stickerBalance
                                Box(Modifier.size(38.dp).background(if (filled) WarmGold else Color(0xFF3E715C), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Star, null, Modifier.size(23.dp), tint = if (filled) Gold else Color(0xFF82A18A))
                                }
                            }
                        }
                    }
                    Text(if (state.stickerBalance > 12) "별 12개와 ${state.stickerBalance - 12}개의 칭찬이 더 있어요." else "하나씩 쌓이는 마음, 참 잘하고 있어요.", color = Color(0xFFDCEBDF), fontSize = 12.sp)
                }
            }
        }
        item {
            Button(if (parent) { { awardDialog = true } } else openRewards, Modifier.fillMaxWidth().height(52.dp).testTag("sticker-action"), enabled = actionsEnabled) {
                Icon(if (parent) Icons.Outlined.Add else Icons.Outlined.Redeem, null, Modifier.size(21.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (parent) "칭찬 스티커 1개 주기" else "모은 스티커 사용하기", fontWeight = FontWeight.Bold)
            }
        }
        if (ready && state.redemptions.isNotEmpty()) item { Text(if (parent && state.redemptions.any { it.status == "pending" || it.status == "requested" }) "아이의 사용 요청" else "스티커 사용 기록", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        items(if (ready) state.redemptions else emptyList(), key = { it.id }) { redemption -> RedemptionCard(redemption, parent, !actionsEnabled, actions) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("우리의 약속", Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                if (parent) TextButton(openRewards, Modifier.testTag("rewards-manage-button"), enabled = actionsEnabled) { Text("관리") }
            }
        }
        if (ready && state.rewards.isEmpty()) item { EmptyCard(Icons.Outlined.Redeem, "아직 정한 약속이 없어요", if (parent) "관리에서 아이와 정한 선물을 추가해 주세요." else "부모님과 어떤 선물을 받을지 함께 정해 보세요.") }
        items(if (ready) state.rewards else emptyList(), key = { "reward-${it.id}" }) { reward ->
            RewardCard(reward, if (parent) null else state.stickerBalance, actionsEnabled && (parent || state.stickerBalance >= reward.cost), if (parent) "reward-summary-${reward.id}" else "reward-item-${reward.id}") {
                if (parent) openRewards() else selectReward(reward.id)
            }
        }
        val awards = if (ready) state.events.filter { it.kind == "sticker_award" } else emptyList()
        if (awards.isNotEmpty()) {
            item { Text("도착한 칭찬", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
            items(awards.reversed(), key = { "award-${it.id}" }) { event ->
                SectionCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.Star, null, tint = Gold)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(event.payload.optString("reason").ifBlank { "오늘도 참 잘했어요!" }, fontWeight = FontWeight.Medium)
                            Text("스티커 1개 · ${displayTime(event.createdAt, true)}", color = Muted, fontSize = 12.sp)
                            Text(deliveryLabel(event.delivery, state.demoMode), color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        if (parent) item {
            TextButton(openFamily, Modifier.fillMaxWidth().testTag("parent-family-connection")) { Text("가족 연결", color = Muted, fontSize = 12.sp) }
        }
    }
    if (awardDialog && actionsEnabled) AwardDialog({ awardDialog = false }) { reason -> actions.awardSticker(reason); awardDialog = false }
}

@Composable
private fun RedemptionCard(item: Redemption, parent: Boolean, loading: Boolean, actions: UiActions) {
    val pending = item.status == "pending" || item.status == "requested"
    val status = when (item.status) { "approved" -> "사용 완료"; "rejected", "declined" -> "다시 의논해요"; "cancelled" -> "취소됨"; else -> "보호자 확인 대기" }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.reward, fontWeight = FontWeight.Bold)
                Text("스티커 ${item.cost}개", fontSize = 13.sp, color = Muted)
            }
            Pill(status, gold = pending)
        }
        if (parent && pending) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ actions.approveRedemption(item.id, false) }, Modifier.weight(1f).testTag("redemption-reject-${item.id}"), enabled = !loading) { Text("다시 의논하기", fontSize = 12.sp) }
            Button({ actions.approveRedemption(item.id, true) }, Modifier.weight(1f).testTag("redemption-approve-${item.id}"), enabled = !loading) { Text("사용 승인", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun AwardDialog(dismiss: () -> Unit, submit: (String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Outlined.Stars, null, tint = Gold) }, title = { Text("어떤 점이 기특했나요?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("칭찬 한마디와 함께 스티커 1개를 전해요.")
            OutlinedTextField(reason, { if (it.length <= 200) reason = it }, label = { Text("칭찬하고 싶은 일") }, placeholder = { Text("도착했다고 알려 줘서 고마워!") }, maxLines = 4, supportingText = { Text("${reason.length}/200") })
        }
    }, confirmButton = { TextButton({ submit(reason.trim()) }, enabled = reason.isNotBlank()) { Text("스티커 1개 주기") } }, dismissButton = { TextButton(dismiss) { Text("취소") } })
}

@Composable
private fun RewardCard(reward: Reward, balance: Int?, enabled: Boolean = true, tag: String = "reward-item-${reward.id}", onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).background(WarmGold, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Redeem, null, tint = Gold) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(reward.name, fontWeight = FontWeight.Medium)
                Text("스티커 ${reward.cost}개", color = Muted, fontSize = 13.sp)
                if (balance != null && reward.cost > balance) Text("${reward.cost - balance}개 더 모으면 돼요", color = Gold, fontSize = 12.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RewardManager(state: UiState, add: () -> Unit, edit: (String) -> Unit) {
    val ready = !state.careEnabled || state.careReady
    val actionsEnabled = careActionsEnabled(state)
    val changes = state.events.filter { it.kind in setOf("reward_upsert", "reward_delete") && it.delivery.lowercase() !in setOf("relayed", "telegram_sent", "delivered") }
    LazyColumn(Modifier.fillMaxSize().testTag("reward-manager-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("아이와 함께 정한 선물과 스티커 개수예요.", fontSize = 14.sp, color = Muted) }
        if (state.careEnabled) item { CareSyncStatus(state) }
        if (changes.isNotEmpty()) item {
            SectionCard {
                Text("약속 변경 전달 상태", fontWeight = FontWeight.Medium)
                changes.takeLast(3).forEach { event ->
                    Text("${if (event.kind == "reward_delete") "약속 삭제" else event.payload.optString("name", "약속 저장")} · ${deliveryLabel(event.delivery, state.demoMode)}", color = Gold, fontSize = 12.sp)
                    event.deliveryError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
                Text("전달이 완료되면 약속 목록에 반영돼요.", fontSize = 12.sp, color = Muted)
            }
        }
        items(if (ready) state.rewards else emptyList(), key = { it.id }) { reward -> RewardCard(reward, null, actionsEnabled) { edit(reward.id) } }
        if (ready && state.rewards.isEmpty()) item { Text("첫 약속을 추가해 보세요.", color = Muted, fontSize = 14.sp) }
        item { Button(add, Modifier.fillMaxWidth().height(50.dp).testTag("reward-add"), enabled = actionsEnabled) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("약속 추가") } }
        item { Text("이미 요청한 선물은 요청 당시의 이름과 스티커 개수로 남아요.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp) }
    }
}

@Composable
private fun RewardEditor(state: UiState, rewardId: String?, save: (String, Int, Long) -> Unit, delete: (Long) -> Unit) {
    val reward = state.rewards.firstOrNull { it.id == rewardId }
    val careScope = careScopeKey(state)
    val actionsEnabled = careActionsEnabled(state)
    var name by rememberSaveable(careScope, rewardId) { mutableStateOf(reward?.name.orEmpty()) }
    var costText by rememberSaveable(careScope, rewardId) { mutableStateOf(reward?.cost?.toString().orEmpty()) }
    var deleteDialog by rememberSaveable(careScope, rewardId) { mutableStateOf(false) }
    val openedVersion by rememberSaveable(careScope, rewardId) { mutableLongStateOf(reward?.version ?: 0L) }
    val cost = costText.toIntOrNull()
    val missing = rewardId != null && reward == null
    val changed = state.careEnabled && reward != null && reward.version != openedVersion
    val valid = name.trim().isNotEmpty() && name.trim().length <= 60 && cost != null && cost in 1..999 && !missing && !changed
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CareSyncStatus(state)
        Text("아이와 정한 선물 이름과 필요한 스티커 개수만 적어 주세요.", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        if (missing) Text("이 약속이 삭제되었어요. 목록으로 돌아가 다시 골라 주세요.", color = MaterialTheme.colorScheme.error)
        if (changed) Text("다른 부모님이 이 약속을 바꿨어요. 입력한 내용은 남겨 두었으니 목록으로 돌아가 최신 약속을 다시 열어 주세요.",
            Modifier.testTag("reward-editor-changed"), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp)
        OutlinedTextField(name, { if (it.length <= 60) name = it }, Modifier.fillMaxWidth().testTag("reward-name"), label = { Text("선물 이름") }, placeholder = { Text("함께 아이스크림 먹기") }, maxLines = 2, supportingText = { Text("${name.length}/60") }, enabled = !missing)
        OutlinedTextField(costText, { if (it.length <= 3 && it.all(Char::isDigit)) costText = it }, Modifier.fillMaxWidth().testTag("reward-cost"), label = { Text("필요한 스티커 개수") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("1개부터 999개까지 정할 수 있어요.") }, isError = costText.isNotEmpty() && (cost == null || cost !in 1..999), enabled = !missing)
        Button({ cost?.let { save(name.trim(), it, openedVersion) } }, Modifier.fillMaxWidth().height(50.dp).testTag("reward-save"), enabled = valid && actionsEnabled) { Text("약속 저장") }
        if (rewardId != null) TextButton({ deleteDialog = true }, Modifier.fillMaxWidth().testTag("reward-delete"), enabled = actionsEnabled && !missing && !changed) { Text("이 약속 삭제", color = MaterialTheme.colorScheme.error) }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("이 약속을 삭제할까요?") }, text = { Text("새 사용 요청 목록에서 지워집니다. 이미 요청한 선물과 사용 기록은 그대로 남아요.") }, confirmButton = { TextButton({ deleteDialog = false; delete(openedVersion) }, Modifier.testTag("reward-delete-confirm"), enabled = actionsEnabled && !missing && !changed) { Text("삭제") } }, dismissButton = { TextButton({ deleteDialog = false }) { Text("취소") } })
}

@Composable
private fun RewardSelection(state: UiState, select: (String) -> Unit) {
    val ready = !state.careEnabled || state.careReady
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.careEnabled) item { CareSyncStatus(state) }
        if (ready) item { Text("지금 모은 스티커 ${state.stickerBalance}개", color = Muted, fontSize = 14.sp) }
        if (ready && state.rewards.isEmpty()) item { EmptyCard(Icons.Outlined.Redeem, "아직 정한 약속이 없어요", "부모님과 선물과 필요한 스티커 개수를 정해 보세요.") }
        items(if (ready) state.rewards else emptyList(), key = { it.id }) { reward -> RewardCard(reward, state.stickerBalance, careActionsEnabled(state) && state.stickerBalance >= reward.cost) { select(reward.id) } }
    }
}

@Composable
private fun RewardConfirmation(state: UiState, rewardId: String?, request: () -> Unit) {
    val reward = state.rewards.firstOrNull { it.id == rewardId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CareSyncStatus(state)
        if (state.careEnabled && !state.careReady) {
            Text("칭찬판을 받은 뒤 다시 골라 주세요.", color = Muted)
        } else if (reward == null) {
            Text("약속이 바뀌었어요. 칭찬판에서 다시 골라 주세요.", color = Muted)
        } else {
            SectionCard {
                Icon(Icons.Outlined.Redeem, null, Modifier.size(36.dp), tint = Gold)
                Text(reward.name, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("스티커 ${reward.cost}개", color = Forest, fontSize = 17.sp)
            }
            Text("지금 모은 스티커 ${state.stickerBalance}개\n부모님이 승인하면 ${reward.cost}개가 사용돼요.", color = Muted, lineHeight = 24.sp)
            if (state.stickerBalance < reward.cost) Text("${reward.cost - state.stickerBalance}개 더 모으면 돼요.", color = Gold)
            Button(request, Modifier.fillMaxWidth().height(52.dp).testTag("reward-request"), enabled = careActionsEnabled(state) && state.stickerBalance >= reward.cost) { Text("부모님에게 사용 요청하기") }
        }
    }
}

@Composable
private fun SettingsMenu(state: UiState, select: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("원하는 항목을 골라 주세요.", fontSize = 14.sp, color = Muted)
        SettingsMenuItem("가족 단체방", state.room?.let { "${it.title} · ${it.members.size}명" } ?: "가족방 만들기 · 코드로 참여", Icons.Outlined.Groups, "settings-room-menu-item") { select("settings-room") }
        if (state.paired || state.demoMode || state.careEnabled) {
            SettingsMenuItem("자동 위치 공유", "현재 ${if (state.sharingEnabled) "켜짐" else "꺼짐"} · 공유 켜기 / 끄기", Icons.Outlined.MyLocation, "settings-location-menu-item") { select("settings-location") }
            SettingsMenuItem("공유 정보 안내", "공유하는 정보와 기록 보관", Icons.Outlined.Shield, "settings-info-menu-item") { select("settings-info") }
        }
        SettingsMenuItem(if (state.paired || state.demoMode || state.careEnabled) "가족 연결" else "메시지 수신", "연결 상태 · 수신 설정", Icons.Outlined.FavoriteBorder, "settings-family-menu-item") { select("settings-family") }
        SettingsMenuItem("별 아이콘", when {
            state.overlayEnabled && state.overlayPermissionGranted -> "켜짐 · 다른 앱에서 대화 열기"
            state.overlaySavedEnabled -> "다시 표시 대기"
            else -> "화면 위에 작게 띄워 두기"
        }, Icons.Outlined.StarOutline, "settings-overlay-menu-item") { select("settings-overlay") }
    }
}

@Composable
private fun SettingsMenuItem(title: String, subtitle: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = Forest)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 12.sp, color = Muted)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, actions: UiActions, section: String, firstGuide: () -> Unit) {
    var consentDialog by rememberSaveable { mutableStateOf(false) }
    var disconnectDialog by rememberSaveable { mutableStateOf(false) }
    val child = state.role == "child"
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (section == "location" && (state.paired || state.demoMode || state.careEnabled)) SectionCard {
            if (state.careEnabled) {
                state.careChildren.firstOrNull { it.botId == state.selectedChildBotId }?.let { Text("${it.displayName}의 위치 공유", fontWeight = FontWeight.Medium) }
                Text("가족방의 엄마·아빠에게 위치를 공유해요. 다른 자녀나 대화 전용 가족에게는 보내지 않아요.", fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("자동 위치 공유", fontWeight = FontWeight.Medium)
                    Text(if (child) "이동 중 약 20초, 정지 중 약 5분마다 위치를 확인해요." else "자녀 휴대폰에서 직접 켜고 끌 수 있어요.", fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
                }
                Switch(state.sharingEnabled, onCheckedChange = { enabled -> if (enabled) consentDialog = true else actions.setSharing(false) }, enabled = child && !state.loading, modifier = Modifier.testTag("sharing-switch"))
            }
            Text(if (child) state.trackingStatus else "마지막으로 전달받은 자녀의 공유 설정입니다.", color = Forest, fontSize = 13.sp)
            if (child && !state.demoMode) {
                val latestAutomatic = state.events.asSequence()
                    .filter { it.kind == "location" && it.sender == "child" && it.payload.optString("source") == "automatic" }
                    .maxByOrNull { runCatching { Instant.parse(eventTime(it)) }.getOrDefault(Instant.MIN) }
                val measuredAt = latestAutomatic?.let { event ->
                    runCatching { Instant.parse(eventTime(event)).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M월 d일 HH:mm:ss")) }
                        .getOrDefault("시각 확인 중")
                }
                LabelValue("최근 자동 측정", measuredAt ?: "표시할 기록 없음")
                latestAutomatic?.let { LabelValue("보호자에게 전달", deliveryLabel(it.delivery, false)) }
            }
            if (child) {
                Text("움직임 인식으로 GPS 흔들림을 줄여요.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                if (!state.demoMode && state.sharingEnabled && !state.motionRecognitionAllowed) {
                    OutlinedButton(actions.requestMotionRecognition,
                        Modifier.fillMaxWidth().testTag("motion-recognition-permission"), enabled = !state.loading) {
                        Text("움직임 인식 허용")
                    }
                }
            }
            HorizontalDivider(color = Cream)
            Text("언제든 이 설정에서 위치 공유를 끌 수 있어요. 공유를 끄면 새 자동 수집을 멈춥니다.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
            if (child) Text("공유 설정을 켜 두면 앱을 다시 열 때 이어서 공유해요. 이동 중 약 20초, 정지 중 약 5분은 요청 간격이며 위치 신호·절전·통신 상태에 따라 기록과 전달이 늦어질 수 있어요.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            if (!state.demoMode && child) {
                OutlinedButton({ ServiceNotificationSettings.open(context, ServiceNotificationSettings.Kind.LOCATION) }, Modifier.fillMaxWidth().testTag("location-notification-settings")) { Text("위치 공유 알림 표시 설정") }
                Text("열린 Android 설정에서 이 알림의 허용을 끄면 실행 알림을 숨길 수 있어요. 대화 알림과 위치 공유는 계속 유지됩니다.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            }
            Text("현재 위치를 한 번 보내는 기능은 대화 화면에서 별도로 사용할 수 있어요.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        }
        if (section == "info") SharedInformation()
        if (section == "overlay") OverlaySettings(state, actions)
        if (section == "family") {
            SectionCard {
                LabelValue("내 역할", if (child) "자녀" else "보호자")
                LabelValue("연결 상태", if (state.demoMode) "체험 모드 · 실제 전송 없음" else transportLabel(state.transport))
                if (!state.demoMode) {
                    LabelValue("내 봇", "@${state.botUsername.removePrefix("@")}")
                    if (state.paired) LabelValue("상대 봇", "@${state.peerBotUsername.removePrefix("@")}")
                    state.room?.let { LabelValue("가족 단체방", "${it.title} · ${it.members.size}명") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("메시지 수신", fontWeight = FontWeight.Medium)
                            Text(if (state.telegramReceiving) "화면을 닫아도 수신 대기 중" else "꺼짐 · 앱을 열어 새로 고침", fontSize = 12.sp, color = Muted)
                        }
                        Switch(state.telegramReceiving, actions.setTelegramReceiving, enabled = !state.loading, modifier = Modifier.testTag("telegram-receiving-switch"))
                    }
                    Text("각 가족 휴대폰에서 수신을 켜 두면 메시지를 계속 받을 수 있어요. 수신 중에는 배터리를 사용합니다. 절전·강제 종료·인터넷 끊김으로 수신이 멈출 수 있으니 앱을 다시 열어 확인해 주세요.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                    Text("‘상대 기기 수신’은 상대 앱이 메시지를 받은 상태예요. 사람이 읽었다는 뜻은 아니에요. 상대 앱에서 받을 때까지 전송 대기로 표시될 수 있어요.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                }
                if (state.paired || state.demoMode || state.careEnabled) TextButton(firstGuide, Modifier.testTag("first-guide-button")) { Icon(Icons.Outlined.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("처음 안내 다시 보기") }
                OutlinedButton(actions.refresh, Modifier.fillMaxWidth(), enabled = !state.loading) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("새로 고침") }
            }
            if (!state.demoMode) SectionCard {
                Text("실행 알림 표시 설정", fontWeight = FontWeight.Bold)
                Text("대화 메시지 알림은 유지하고, 아래 실행 알림만 따로 숨길 수 있어요. 열린 Android 설정에서 해당 알림의 허용을 꺼 주세요.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                if (child && (state.paired || state.careEnabled)) OutlinedButton({ ServiceNotificationSettings.open(context, ServiceNotificationSettings.Kind.LOCATION) }, Modifier.fillMaxWidth()) { Text("위치 공유 알림") }
                OutlinedButton({ ServiceNotificationSettings.open(context, ServiceNotificationSettings.Kind.RECEIVING) }, Modifier.fillMaxWidth()) { Text("메시지 수신 대기 알림") }
                OutlinedButton({ ServiceNotificationSettings.open(context, ServiceNotificationSettings.Kind.STAR) }, Modifier.fillMaxWidth()) { Text("별 아이콘 실행 알림") }
            }
            if (!child) OverlaySettings(state, actions)
            if (state.demoMode) SectionCard {
                Text("다른 가족 화면도 둘러보기", fontWeight = FontWeight.Bold)
                Text("체험 화면에서는 실제 센서를 켜거나 메시지를 보내지 않아요.", fontSize = 13.sp, color = Muted)
                OutlinedButton({ actions.switchDemoRole(if (child) "guardian" else "child") }, Modifier.fillMaxWidth().testTag("switch-demo-role")) { Text(if (child) "보호자 화면 체험" else "자녀 화면 체험") }
            }
            if (!state.demoMode && child && state.sharingEnabled) Text("연결을 해제하려면 먼저 자동 위치 공유를 꺼 주세요.", fontSize = 12.sp, color = Muted)
            OutlinedButton({ disconnectDialog = true }, Modifier.fillMaxWidth(), enabled = state.demoMode || !child || !state.sharingEnabled) { Text(if (state.demoMode) "체험 종료하고 가족 연결하기" else "이 휴대폰의 연결 해제") }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (consentDialog) AlertDialog(
        onDismissRequest = { consentDialog = false },
        icon = { Icon(Icons.Outlined.MyLocation, null, tint = Forest) },
        title = { Text("자동 위치를 공유할까요?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.careEnabled) Text("수신하는 부모님: ${state.room?.members?.filter { it.relationship in setOf("mother", "father") }?.joinToString { it.displayName }.orEmpty()}", fontWeight = FontWeight.Medium)
            Text("이동 중 약 20초, 정지 중 약 5분마다 새 위치를 요청해 좌표·측정 시각·정확도와 움직임 상태를 보호자에게 보냅니다. 기압계가 있으면 상대 높이 변화로 오르내림 시작과 종료도 추정해 보냅니다.", lineHeight = 22.sp)
            Text("화면이 꺼져 있어도 공유를 이어가며, 켠 설정은 직접 끄기 전까지 기억해 앱을 다시 열면 이어서 공유합니다. 위치 신호·절전·통신 상태에 따라 기록과 전달이 늦어질 수 있어요.", lineHeight = 22.sp)
            Text("텔레그램 봇을 통해 보호자의 앱으로 전달되며 공유 중에는 휴대폰 알림이 표시됩니다. 언제든 이 설정에서 끌 수 있어요. 보호자 휴대폰에서 메시지 수신을 켜 두어야 빠르게 받을 수 있어요.", lineHeight = 22.sp)
            if (state.demoMode) Text("지금은 체험 모드여서 실제로 수집하거나 공유하지 않아요.", color = Gold, fontWeight = FontWeight.Medium)
        } },
        confirmButton = { TextButton({ consentDialog = false; actions.setSharing(true) }) { Text(if (state.demoMode) "공유 켜기 체험" else "동의하고 공유 켜기") } },
        dismissButton = { TextButton({ consentDialog = false }) { Text("나중에") } },
    )
    if (disconnectDialog) AlertDialog(onDismissRequest = { disconnectDialog = false }, title = { Text(if (state.demoMode) "체험을 마칠까요?" else "이 휴대폰 연결을 해제할까요?") }, text = { Text(if (state.demoMode) "처음 화면에서 텔레그램 봇으로 가족을 연결할 수 있어요." else "이 휴대폰의 봇 토큰·연결 정보와 앱 기록을 지웁니다. 상대 휴대폰과 텔레그램의 기록은 삭제되지 않습니다.") }, confirmButton = { TextButton({ disconnectDialog = false; actions.resetConfiguration() }) { Text(if (state.demoMode) "체험 마치기" else "연결 해제") } }, dismissButton = { TextButton({ disconnectDialog = false }) { Text("취소") } })
}

@Composable
private fun OverlayPermissionIntro(actions: UiActions) {
    AlertDialog(
        onDismissRequest = actions.dismissOverlayPrompt,
        modifier = Modifier.testTag("overlay-permission-prompt"),
        icon = { Icon(Icons.Filled.Star, null, tint = Gold) },
        title = { Text("별을 눌러 대화를 열어요") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("다른 앱 위에 작은 반투명 별 아이콘을 띄워 둘 수 있어요. 별은 손가락으로 옮길 수 있고, 누르면 대화가 열려요.", lineHeight = 22.sp)
                Text("대화 화면의 닫기 버튼을 누르면 다시 별 아이콘으로 돌아갑니다. 사용하려면 휴대폰 설정에서 ‘다른 앱 위에 표시’를 허용해 주세요.", lineHeight = 22.sp)
                Text("별 아이콘 자체는 GPS를 켜거나 위치 공유를 시작하지 않아요. 별 아이콘 설정에서 언제든 끌 수 있어요.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
            }
        },
        confirmButton = { TextButton(actions.enableOverlay, Modifier.testTag("overlay-permission-open")) { Text("권한 설정 열기") } },
        dismissButton = { TextButton(actions.dismissOverlayPrompt, Modifier.testTag("overlay-prompt-dismiss")) { Text("지금은 앱으로 사용") } },
    )
}

@Composable
private fun OverlaySettings(state: UiState, actions: UiActions) {
    val active = state.overlayEnabled && state.overlayPermissionGranted
    var disableDialog by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, null, Modifier.size(22.dp), tint = Gold)
            Spacer(Modifier.width(9.dp))
            Text("별 아이콘", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Pill(if (active) "켜짐" else if (state.overlaySavedEnabled) "다시 표시 대기" else if (state.overlayEnabled) "권한 필요" else "꺼짐")
        }
        Text("다른 앱 위에 작은 반투명 별을 띄워 둬요. 별을 끌어 옮기거나 눌러서 대화를 열 수 있어요.", fontSize = 13.sp, color = Muted, lineHeight = 21.sp)
        Text("켜 둔 별 설정은 기억해요. 일시적으로 별이 사라져도 앱을 다시 열면 표시를 다시 시도해요. 대화의 닫기 버튼은 별로 돌아가는 버튼이에요.", fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
        LabelValue("화면 위 표시", if (state.overlayPermissionGranted) "허용됨" else "권한 필요")
        Text("별 아이콘을 켜거나 꺼도 자동 위치 공유 설정은 바뀌지 않아요.", fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
        if (state.demoMode) Text("체험 모드에서도 별 아이콘은 실제 화면 위에 표시돼요. 메시지·위치 전송은 하지 않아요.", fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
        if (active) {
            Button(actions.returnToStar, Modifier.fillMaxWidth().testTag("overlay-return")) { Text("별 아이콘으로 돌아가기") }
        } else {
            Button(actions.enableOverlay, Modifier.fillMaxWidth().testTag("overlay-enable")) {
                Text(if (!state.overlayPermissionGranted) "권한 설정 열기" else if (state.overlaySavedEnabled) "별 아이콘 다시 표시" else "별 아이콘 켜기")
            }
        }
        if (state.overlayEnabled || state.overlaySavedEnabled) OutlinedButton({ disableDialog = true }, Modifier.fillMaxWidth().testTag("overlay-disable")) { Text("별 아이콘 끄기") }
    }
    if (disableDialog) AlertDialog(
        onDismissRequest = { disableDialog = false },
        modifier = Modifier.testTag("overlay-disable-dialog"),
        icon = { Icon(Icons.Outlined.StarOutline, null, tint = Gold) },
        title = { Text("별 아이콘을 끌까요?") },
        text = { Text("다른 앱 위의 별이 사라져요. 대화는 앱을 열어 사용할 수 있고, 자동 위치 공유 설정은 그대로 유지돼요.", lineHeight = 22.sp) },
        confirmButton = { TextButton({ disableDialog = false; actions.disableOverlay() }, Modifier.testTag("overlay-disable-confirm")) { Text("별 끄기") } },
        dismissButton = { Button({ disableDialog = false }, Modifier.testTag("overlay-disable-cancel")) { Text("켜 두기") } },
    )
}

@Composable
private fun SharedInformation() {
    SectionCard {
        Text("위치와 측정 시각", fontWeight = FontWeight.Medium)
        Text("GPS 좌표 · 측정 시각 · 위치 정확도 · 움직임 상태", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        Text("올라가고 내려가는 움직임", fontWeight = FontWeight.Medium)
        Text("기압계가 있으면 기압과 상대 높이 변화를 활용해 오르내림의 시작과 종료를 추정해요. 정확한 층수는 알 수 없고, 실내에서는 GPS 위치도 부정확할 수 있어요.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        Text("연결된 보호자에게 전달", fontWeight = FontWeight.Medium)
        Text("위치와 대화·칭찬 기록은 텔레그램 봇을 거쳐 연결된 상대 앱으로 전달되고 각 휴대폰에 저장돼요. 봇 대화는 종단간 암호화되지 않아요. 연결 해제는 이 휴대폰의 연결 정보와 앱 기록만 지우며, 상대 휴대폰과 텔레그램의 기록은 별도로 관리해야 해요.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun FirstGuide() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("오는 길을 함께 알 수 있어요.", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        SectionCard {
            Text("텔레그램으로 직접 연결해요", fontWeight = FontWeight.Medium)
            Text("휴대폰마다 자기 봇의 토큰과 상대방 봇의 사용자명을 입력해요. 두 봇 모두 BotFather의 Bot-to-Bot Communication Mode가 켜져 있어야 해요.", fontSize = 14.sp, lineHeight = 22.sp)
            Text("가족 연결 설정에서 ‘메시지 수신’을 켜 두세요. 화면을 닫아도 받을 수 있지만, 절전이나 강제 종료로 중단되면 앱을 다시 열어야 해요.", fontSize = 14.sp, lineHeight = 22.sp)
        }
        SectionCard {
            Text("대화하면서 위치를 보낼 수 있어요", fontWeight = FontWeight.Medium)
            Text("대화의 ‘현재 위치 공유’를 누르면 그때의 위치를 한 번 부모님에게 보내요.", fontSize = 14.sp, lineHeight = 22.sp)
            Text("자동 공유는 따로 켜요", fontWeight = FontWeight.Medium)
            Text("자녀 대화에 정확히 ‘설정’을 보내고 자동 위치 공유를 고르세요. 동의하고 켜면 이동 중 약 20초, 정지 중 약 5분마다 새 위치를 요청해요. 화면이 꺼져 있어도 공유를 이어가고, 앱을 다시 열면 켜 둔 공유 설정을 이어가요. 필요한 위치·신체 활동·알림 권한을 요청해요.", fontSize = 14.sp, lineHeight = 22.sp)
            Text("위치 신호·절전·통신 상태에 따라 기록과 전달이 늦어질 수 있어요. 자동 위치 공유 설정에서 최근 측정 시각과 전달 상태를 확인할 수 있어요.", fontSize = 13.sp, color = Muted, lineHeight = 21.sp)
            Text("공유 중임을 항상 알려요", fontWeight = FontWeight.Medium)
            Text("휴대폰 알림에서 공유 상태를 확인하고, 언제든 설정이나 알림에서 자동 공유를 끌 수 있어요.", fontSize = 14.sp, lineHeight = 22.sp)
        }
        SharedInformation()
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(13.dp), content = content)
    }
}

@Composable
private fun EmptyCard(icon: ImageVector, title: String, detail: String) {
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(58.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Forest, modifier = Modifier.size(28.dp)) }
            Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Pill(text: String, gold: Boolean = false) {
    Surface(color = if (gold) WarmGold else Mint, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = if (gold) Gold else Forest, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.width(78.dp))
        Text(value, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

private fun locationPoint(event: FamilyEvent): Pair<Double, Double>? {
    val latitude = event.payload.optDouble("latitude", event.payload.optDouble("lat", Double.NaN))
    val longitude = event.payload.optDouble("longitude", event.payload.optDouble("lng", event.payload.optDouble("lon", Double.NaN)))
    return if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) latitude to longitude else null
}

private fun eventTime(event: FamilyEvent): String = when (event.kind) {
    "location" -> event.payload.optString("capturedAt").ifBlank { event.createdAt }
    "vertical" -> event.payload.optString("measuredAt").ifBlank { event.createdAt }
    else -> event.createdAt
}

private fun coordinateText(latitude: Double, longitude: Double): String = String.format(Locale.KOREA, "%.6f, %.6f", latitude, longitude)

private fun displayTime(value: String, includeDate: Boolean = false): String = runCatching {
    val instant = runCatching { Instant.parse(value) }.getOrElse { OffsetDateTime.parse(value).toInstant() }
    DateTimeFormatter.ofPattern(if (includeDate) "M월 d일 HH:mm:ss" else "HH:mm", Locale.KOREA).withZone(ZoneId.systemDefault()).format(instant)
}.getOrElse { value.ifBlank { "시각 정보 없음" } }

private fun deliveryLabel(delivery: String, demo: Boolean): String {
    if (demo) return "체험 기록 · 실제 전송 없음"
    return when (delivery.lowercase()) {
        "pending", "queued" -> "전송 대기"
        "sending" -> "전송 중"
        "relayed" -> "상대 기기 수신"
        "telegram_sent", "delivered" -> "텔레그램 전달됨"
        "failed", "error" -> "전송 실패 · 재시도 필요"
        else -> "전송 상태 확인 중"
    }
}

private fun locationMessageTitle(delivery: String, demo: Boolean): String {
    if (demo) return "체험 위치"
    return when (delivery.lowercase()) {
        "pending", "queued" -> "현재 위치 전송 대기"
        "sending" -> "현재 위치 전송 중"
        "relayed", "telegram_sent", "delivered" -> "현재 위치를 보냈어요"
        "failed", "error" -> "위치 전송 실패"
        else -> "현재 위치 · 전송 확인 중"
    }
}

private fun elapsedMinutes(value: String, now: Instant): Long? = runCatching {
    Duration.between(Instant.parse(value), now).toMinutes().coerceAtLeast(0)
}.getOrNull()

private fun transportLabel(transport: String): String = when (transport.lowercase()) {
    "telegram", "telegram_direct" -> "텔레그램 봇 직접 연결"
    "unconfigured", "" -> "가족 연결 전"
    "offline" -> "연결을 기다리는 중"
    else -> transport
}
