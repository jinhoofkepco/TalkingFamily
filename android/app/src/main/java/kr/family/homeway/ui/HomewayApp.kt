package kr.family.homeway.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kr.family.homeway.BuildConfig
import kr.family.homeway.data.FamilyEvent
import kr.family.homeway.data.Redemption
import kr.family.homeway.data.Reward
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Forest = Color(0xFF245B46)
private val Cream = Color(0xFFF8F6EE)
private val Ink = Color(0xFF24362D)
private val Muted = Color(0xFF657368)
private val Mint = Color(0xFFE4EFE5)
private val Gold = Color(0xFFB67918)
private val WarmGold = Color(0xFFFAE9BC)

@Composable
fun HomewayApp(state: UiState, actions: UiActions) {
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
                Onboarding(state, actions)
            } else {
                FamilyHome(state, actions)
                if (state.overlayPromptVisible) OverlayPermissionIntro(actions)
            }
        }
    }
}

@Composable
private fun Onboarding(state: UiState, actions: UiActions) {
    var role by rememberSaveable { mutableStateOf(state.role) }
    var url by rememberSaveable { mutableStateOf(state.serverUrl) }
    var token by remember { mutableStateOf("") }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(62.dp).background(Forest, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Cottage, null, Modifier.size(34.dp), tint = Color.White)
        }
        Text("우리 오는 길", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("집으로 오는 길에도,\n우리의 대화는 이어져요.", fontSize = 20.sp, lineHeight = 29.sp, color = Forest)
        Text("대화하고, 마음을 전하고, 칭찬을 모으는\n우리 가족만의 작은 공간이에요.", color = Muted, lineHeight = 23.sp)
        SectionCard {
            Text("이 휴대폰은 누가 쓰나요?", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoleButton("child", role, "자녀", Icons.Outlined.Face, Modifier.weight(1f)) { role = "child" }
                RoleButton("guardian", role, "보호자", Icons.Outlined.PersonOutline, Modifier.weight(1f)) { role = "guardian" }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.PrivacyTip, null, tint = Forest)
                Text("처음에 함께 읽어 주세요", fontWeight = FontWeight.Bold)
            }
            Text(
                "대화 내용과 칭찬 기록은 가족의 중계 서버에 저장되고, 텔레그램 봇을 통해 보호자에게 전달됩니다.",
                lineHeight = 23.sp,
            )
            Text(
                "자녀가 대화 화면에서 ‘현재 위치 공유’를 누르면 위치를 한 번 확인합니다. 자동 위치 공유는 자녀 대화에 정확히 ‘설정’을 보내고, 메뉴에서 별도로 동의하고 켤 수 있습니다.",
                lineHeight = 23.sp,
            )
            Text(
                "자동 공유 중에는 움직임을 감지해 이동 중 5분 간격으로 GPS 위치를 확인합니다. 위도·경도, 측정 시각, 정확도와 움직임 상태를 공유합니다. 기압계가 있는 기기는 기압에 따른 상대 높이 변화로 올라가기·내려가기의 시작과 종료를 추정해 공유합니다. 정확한 층수는 알 수 없습니다.",
                lineHeight = 23.sp,
            )
            Text(
                "이 정보는 중계 서버와 텔레그램을 거쳐 연결된 보호자에게 전달됩니다. 자동 공유 중에는 휴대폰 알림이 표시되며 설정에서 언제든 끌 수 있습니다. 위치·신체 활동·알림 권한은 기능을 사용할 때 요청합니다.",
                lineHeight = 23.sp,
            )
            Text(
                "현재 버전의 기록은 서버 운영자가 삭제할 때까지 서버에 보관됩니다. 텔레그램의 기록은 별도로 관리해야 합니다. 앱 연결을 해제해도 기존 서버·텔레그램 기록은 삭제되지 않습니다.",
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
                url, { url = it }, Modifier.fillMaxWidth(), label = { Text("가족 서버 주소") },
                placeholder = { Text("https://family.example.com") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth(), label = { Text("가족 연결 키") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("이 휴대폰 역할에 맞는 연결 키를 입력해 주세요.") },
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
            Button(
                { actions.configure(role, url.trim(), token.trim()) }, Modifier.fillMaxWidth().height(52.dp),
                enabled = acknowledged && url.isNotBlank() && token.isNotBlank() && !state.loading,
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
private fun FamilyHome(state: UiState, actions: UiActions) {
    val parent = state.role == "guardian" || state.role == "parent"
    var tab by rememberSaveable(state.role) { mutableStateOf(if (parent) "location" else "chat") }
    var popup by rememberSaveable(state.role) { mutableStateOf("") }
    var selectedRewardId by rememberSaveable(state.role) { mutableStateOf<String?>(null) }
    var lastHandledChatRequestId by rememberSaveable { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val openPopup: (String) -> Unit = { keyboard?.hide(); popup = it }
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
                if (state.error != null || state.notice != null) {
                    Row(
                        Modifier.fillMaxWidth().background(if (state.error != null) Color(0xFFFCE9E4) else Mint).padding(start = 20.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(state.error ?: state.notice.orEmpty(), Modifier.weight(1f).padding(vertical = 10.dp), fontSize = 13.sp, lineHeight = 19.sp)
                        IconButton(actions.clearNotice) { Icon(Icons.Outlined.Close, "안내 닫기", Modifier.size(18.dp)) }
                    }
                }
            }
        },
        bottomBar = {
            if (parent) NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                NavigationBarItem(tab == "chat", { tab = "chat" }, modifier = Modifier.testTag("nav-chat"), icon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) }, label = { Text("대화") })
                NavigationBarItem(tab == "location", { tab = "location" }, modifier = Modifier.testTag("nav-location"), icon = { Icon(Icons.Outlined.LocationOn, null) }, label = { Text("자녀 위치") })
                NavigationBarItem(tab == "stickers", { tab = "stickers" }, modifier = Modifier.testTag("nav-stickers"), icon = { Icon(Icons.Outlined.Stars, null) }, label = { Text("칭찬판") })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                parent && tab == "stickers" -> StickerScreen(state, actions,
                    openRewards = { openPopup("reward-manager") },
                    selectReward = { selectedRewardId = it; openPopup("reward-confirm") },
                    openFamily = { openPopup("settings-family") },
                )
                parent && tab == "location" -> LocationScreen(state)
                else -> ChatScreen(state, actions, { openPopup("settings-menu") }, { openPopup("stickers") })
            }
        }
    }
    if (popup.isNotEmpty()) {
        val back = when (popup) {
            "settings-location", "settings-info", "settings-family", "settings-overlay" -> if (parent) "" else "settings-menu"
            "first-guide" -> "settings-family"
            "reward-editor" -> "reward-manager"
            "reward-select", "reward-confirm" -> "stickers"
            else -> ""
        }
        val title = when (popup) {
            "stickers" -> "칭찬판"
            "settings-menu" -> "설정"
            "settings-location" -> "자동 위치 공유"
            "settings-info" -> "공유 정보 안내"
            "settings-family" -> "가족 연결"
            "settings-overlay" -> "별 아이콘"
            "first-guide" -> "처음 함께 읽기"
            "reward-manager" -> "우리의 약속"
            "reward-editor" -> if (selectedRewardId == null) "약속 추가" else "약속 수정"
            "reward-select" -> "어떤 약속으로 바꿀까요?"
            else -> "이 약속으로 바꿀까요?"
        }
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
                        "settings-location", "settings-info", "settings-family", "settings-overlay" -> SettingsScreen(state, actions, popup.removePrefix("settings-")) { popup = "first-guide" }
                        "first-guide" -> FirstGuide()
                        "reward-manager" -> RewardManager(state,
                            add = { selectedRewardId = null; popup = "reward-editor" },
                            edit = { selectedRewardId = it; popup = "reward-editor" },
                        )
                        "reward-editor" -> RewardEditor(state, selectedRewardId,
                            save = { name, cost -> actions.saveReward(selectedRewardId, name, cost); popup = "reward-manager" },
                            delete = { selectedRewardId?.let(actions.deleteReward); popup = "reward-manager" },
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
private fun ChatScreen(state: UiState, actions: UiActions, openSettings: () -> Unit, openStickers: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    var message by rememberSaveable(state.role) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val parent = state.role == "guardian" || state.role == "parent"
    val events = state.events.filter { it.kind == "chat" || (it.kind == "location" && it.payload.optString("source") == "manual") }
    LaunchedEffect(events.lastOrNull()?.id) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (parent) Icons.Outlined.Face else Icons.Outlined.FavoriteBorder, null, tint = Forest)
            }
            Spacer(Modifier.width(10.dp))
            Text(if (parent) "우리 아이" else "아빠", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (!parent) FilledTonalButton(openStickers, Modifier.testTag("child-sticker-button"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.Star, null, Modifier.size(18.dp), tint = Gold)
                Spacer(Modifier.width(5.dp))
                Text("칭찬판 ${state.stickerBalance}", fontSize = 13.sp)
            }
            if (state.overlayEnabled && state.overlayPermissionGranted) IconButton(
                onClick = { keyboard?.hide(); actions.returnToStar() },
                modifier = Modifier.testTag("return-to-star"),
            ) { Icon(Icons.Outlined.Close, "별 아이콘으로 돌아가기", Modifier.size(21.dp), tint = Forest) }
        }
        HorizontalDivider(color = Color(0xFFE6E8DF))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat-list"), state = listState, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (events.isEmpty()) item { EmptyCard(Icons.AutoMirrored.Outlined.Chat, "첫 인사를 건네 볼까요?", "‘지금 출발해요’처럼 짧게 보내도 좋아요.") }
            items(events, key = { it.id }) { event -> ChatBubble(event, state.role, state.demoMode) }
        }
        if (!parent) Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("출발했어" to "지금 출발했어!", "도착했어" to "도착했어!", "전화해 줘" to "아빠, 전화해 줘").forEach { (label, text) ->
                AssistChip(onClick = { actions.sendChat(text) }, label = { Text(label, fontSize = 12.sp) }, enabled = !state.loading)
            }
        }
        if (!parent) OutlinedButton(actions.shareCurrentLocation, Modifier.fillMaxWidth().padding(horizontal = 18.dp).testTag("share-current-location"), enabled = !state.loading) {
            Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("현재 위치 공유", fontSize = 14.sp)
            Spacer(Modifier.width(10.dp))
            Text(if (state.demoMode) "체험" else "한 번만 보내요", color = Muted, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(message, { message = it }, Modifier.weight(1f).testTag("chat-input"), placeholder = { Text("마음을 담아 보내요") }, maxLines = 4, shape = RoundedCornerShape(24.dp), isError = message.length > 1500, supportingText = if (message.length > 1500) { { Text("1,500자 이내로 적어 주세요.") } } else null, colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White))
            FilledIconButton(
                onClick = {
                    // Check the raw input before trimming. Only the child's exact command opens local settings.
                    if (!parent && message == "설정") openSettings()
                    else if (message.isNotBlank()) actions.sendChat(message)
                    message = ""
                },
                modifier = Modifier.size(54.dp).testTag("chat-send"), enabled = message.isNotBlank() && message.length <= 1500 && !state.loading,
            ) { Icon(Icons.AutoMirrored.Outlined.Send, "메시지 보내기") }
        }
    }
}

@Composable
private fun ChatBubble(event: FamilyEvent, role: String, demo: Boolean) {
    val mine = event.sender == role
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine) Text(if (role != "child") "우리 아이" else "보호자", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, bottom = 5.dp))
        Surface(color = if (mine) Forest else Color.White, shape = RoundedCornerShape(22.dp, 22.dp, if (mine) 5.dp else 22.dp, if (mine) 22.dp else 5.dp), modifier = Modifier.widthIn(max = 290.dp)) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (event.kind == "location") {
                    Text("📍 ${locationMessageTitle(event.delivery, demo)}", fontWeight = FontWeight.Medium, color = if (mine) Color.White else Ink)
                    val point = locationPoint(event)
                    Text(point?.let { coordinateText(it.first, it.second) } ?: "좌표 확인 중", fontSize = 12.sp, color = if (mine) Color(0xFFD9EADD) else Muted)
                    Text("측정 ${displayTime(eventTime(event))}", fontSize = 12.sp, color = if (mine) Color(0xFFD9EADD) else Muted)
                } else Text(event.payload.optString("text", ""), color = if (mine) Color.White else Ink, fontSize = 16.sp, lineHeight = 24.sp)
            }
        }
        Text("${displayTime(event.createdAt)} · ${deliveryLabel(event.delivery, demo)}", Modifier.padding(horizontal = 5.dp, vertical = 5.dp), fontSize = 10.sp, color = Muted)
    }
}

@Composable
private fun LocationScreen(state: UiState) {
    val now by produceState(Instant.now()) {
        while (true) { delay(30_000); value = Instant.now() }
    }
    val readings = state.events.filter { it.kind == "location" || it.kind == "vertical" }.sortedByDescending { eventTime(it) }
    val latest = readings.firstOrNull { it.kind == "location" && locationPoint(it) != null }
    val point = latest?.let(::locationPoint)
    val heartbeat = state.events.filter { it.kind == "heartbeat" }.maxByOrNull { it.payload.optString("recordedAt") }
    val heartbeatTime = heartbeat?.payload?.optString("recordedAt").orEmpty()
    val heartbeatAge = elapsedMinutes(heartbeatTime, now)
    LazyColumn(Modifier.fillMaxSize().testTag("location-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, null, tint = Forest, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.sharingEnabled) "자동 공유 설정 켜짐" else "자동 공유 설정 꺼짐", fontWeight = FontWeight.Bold)
                }
                Text(if (state.demoMode) "체험 기록 · 실제 위치를 수집하지 않아요" else "자녀에게서 마지막으로 받은 공유 설정입니다.", fontSize = 13.sp, color = Muted)
                Text("이동 중 5분 간격의 위치 기록과 높이 변화 알림을 확인해요.", fontSize = 13.sp, color = Muted, lineHeight = 20.sp)
                if (heartbeat != null) {
                    Text("최근 기기 상태 ${displayTime(heartbeatTime, true)}", fontSize = 12.sp, color = Muted)
                    val battery = heartbeat.payload.optInt("batteryPercent", -1)
                    if (battery in 0..100) Text("기록 당시 배터리 ${battery}%", fontSize = 12.sp, color = Muted)
                }
                if (!state.demoMode && state.sharingEnabled && (heartbeatAge == null || heartbeatAge >= 10)) {
                    Text(
                        if (heartbeatAge == null) "아직 자녀의 최근 기기 상태가 확인되지 않았어요. 공유 설정과 실제 연결 상태는 다를 수 있어요."
                        else "기기 상태가 ${heartbeatAge}분 동안 갱신되지 않았어요. 마지막 기록만 표시하고 있으니 자녀에게 확인해 주세요.",
                        color = Gold, fontSize = 12.sp, lineHeight = 19.sp,
                    )
                }
            }
        }
        item {
            if (point == null) EmptyCard(Icons.Outlined.LocationOn, "아직 공유된 위치가 없어요", "자녀가 대화에서 현재 위치를 보내거나\n설정에서 자동 공유를 켜면 표시됩니다.")
            else SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.demoMode) "예시 위치" else "마지막으로 확인한 위치", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Pill(if (latest.payload.optString("source") == "automatic") "자동 기록" else "직접 공유")
                }
                if (BuildConfig.MAPS_API_KEY.isNotBlank() && !state.demoMode) {
                    Surface(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().height(240.dp)) {
                        LocationMap(point.first, point.second, latest.payload.optDouble("accuracy", Double.NaN))
                    }
                } else {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Mint) {
                        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(Icons.Outlined.Map, null, tint = Forest, modifier = Modifier.size(38.dp))
                            Text(coordinateText(point.first, point.second), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(if (state.demoMode) "체험 모드의 예시 좌표입니다." else "지도 연결 전에도 측정 좌표를 확인할 수 있어요.", fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center)
                        }
                    }
                }
                Text("측정 ${displayTime(eventTime(latest), includeDate = true)}", fontWeight = FontWeight.Medium)
                val locationAge = elapsedMinutes(eventTime(latest), now)
                if (!state.demoMode && locationAge != null && locationAge >= 10) {
                    Text("${locationAge}분 전에 측정한 위치입니다. 현재 위치와 다를 수 있어요.", fontSize = 12.sp, color = Gold, lineHeight = 19.sp)
                }
                val accuracy = latest.payload.optDouble("accuracy", Double.NaN)
                Text(if (accuracy.isFinite()) "위치 오차 약 ${accuracy.toInt()}m · ${deliveryLabel(latest.delivery, state.demoMode)}" else "위치 정확도 정보 없음 · ${deliveryLabel(latest.delivery, state.demoMode)}", fontSize = 12.sp, color = Muted)
                MapsButton(point.first, point.second, state.demoMode)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("오는 길 기록", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("GPS 위치와 오르내림은 따로 기록돼요.\n높이 변화는 추정이며, 현재 층수를 뜻하지 않아요.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            }
        }
        if (readings.isEmpty()) item { Text("새 기록이 도착하면 여기에 모아 보여 드릴게요.", color = Muted, fontSize = 13.sp) }
        items(readings, key = { it.id }) { event -> TimelineCard(event, state.demoMode) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun LocationMap(latitude: Double, longitude: Double, accuracy: Double) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context).apply { onCreate(Bundle()) } }
    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    LaunchedEffect(mapView, latitude, longitude, accuracy) {
        mapView.getMapAsync { map ->
            val position = LatLng(latitude, longitude)
            map.clear()
            map.uiSettings.isMapToolbarEnabled = false
            map.uiSettings.isZoomControlsEnabled = true
            map.addMarker(MarkerOptions().position(position).title("마지막 측정 위치"))
            if (accuracy.isFinite() && accuracy > 0) map.addCircle(CircleOptions().center(position).radius(accuracy).strokeColor(0x99245B46.toInt()).fillColor(0x22245B46).strokeWidth(2f))
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 16f))
        }
    }
}

@Composable
private fun MapsButton(latitude: Double, longitude: Double, demo: Boolean) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude")
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }, modifier = Modifier.fillMaxWidth(), enabled = !demo,
    ) {
        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Google 지도에서 열기")
    }
}

@Composable
private fun TimelineCard(event: FamilyEvent, demo: Boolean) {
    val vertical = event.kind == "vertical"
    val phase = event.payload.optString("phase")
    val ascent = phase.startsWith("ascent")
    val label = when (phase) {
        "ascent_started" -> "올라가기 시작"
        "ascent_finished" -> "올라가기 종료"
        "descent_started" -> "내려가기 시작"
        "descent_finished" -> "내려가기 종료"
        else -> "높이 변화"
    }
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).background(if (vertical) WarmGold else Mint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (!vertical) Icons.Outlined.LocationOn else if (ascent) Icons.Outlined.NorthEast else Icons.Outlined.SouthEast, null, Modifier.size(20.dp), tint = if (vertical) Gold else Forest)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(if (vertical) label else if (event.payload.optString("source") == "automatic") "5분 자동 위치 기록" else "현재 위치 직접 공유", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (vertical) Pill("추정", gold = true)
                }
                Text(displayTime(eventTime(event), includeDate = true), color = Muted, fontSize = 12.sp)
                if (vertical) {
                    val delta = event.payload.optDouble("relativeMeters", Double.NaN)
                    if (delta.isFinite()) Text("상대 높이 변화 ${if (delta >= 0) "+" else ""}${String.format(Locale.KOREA, "%.1f", delta)}m", fontSize = 12.sp, color = Muted)
                } else {
                    locationPoint(event)?.let { Text(coordinateText(it.first, it.second), fontSize = 12.sp, color = Muted) }
                    val accuracy = event.payload.optDouble("accuracy", Double.NaN)
                    if (accuracy.isFinite()) Text("위치 오차 약 ${accuracy.toInt()}m", fontSize = 12.sp, color = Muted)
                }
                Text(deliveryLabel(event.delivery, demo), fontSize = 11.sp, color = Muted)
            }
        }
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
    var awardDialog by rememberSaveable { mutableStateOf(false) }
    val parent = state.role == "guardian" || state.role == "parent"
    LazyColumn(Modifier.fillMaxSize().testTag("sticker-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (!popup) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("차곡차곡, 칭찬판", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("작은 노력도 반짝이는 별이 돼요.", color = Muted, fontSize = 13.sp)
            }
        }
        item {
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
            Button(if (parent) { { awardDialog = true } } else openRewards, Modifier.fillMaxWidth().height(52.dp), enabled = !state.loading) {
                Icon(if (parent) Icons.Outlined.Add else Icons.Outlined.Redeem, null, Modifier.size(21.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (parent) "칭찬 스티커 1개 주기" else "모은 스티커 사용하기", fontWeight = FontWeight.Bold)
            }
        }
        if (state.redemptions.isNotEmpty()) item { Text(if (parent && state.redemptions.any { it.status == "pending" || it.status == "requested" }) "아이의 사용 요청" else "스티커 사용 기록", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        items(state.redemptions, key = { it.id }) { redemption -> RedemptionCard(redemption, parent, state.loading, actions) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("우리의 약속", Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                if (parent) TextButton(openRewards, Modifier.testTag("rewards-manage-button")) { Text("관리") }
            }
        }
        if (state.rewards.isEmpty()) item { EmptyCard(Icons.Outlined.Redeem, "아직 정한 약속이 없어요", if (parent) "관리에서 아이와 정한 선물을 추가해 주세요." else "아빠와 어떤 선물을 받을지 함께 정해 보세요.") }
        items(state.rewards, key = { "reward-${it.id}" }) { reward ->
            RewardCard(reward, if (parent) null else state.stickerBalance, !state.loading && (parent || state.stickerBalance >= reward.cost), if (parent) "reward-summary-${reward.id}" else "reward-item-${reward.id}") {
                if (parent) openRewards() else selectReward(reward.id)
            }
        }
        val awards = state.events.filter { it.kind == "sticker_award" }
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
    if (awardDialog) AwardDialog({ awardDialog = false }) { reason -> actions.awardSticker(reason); awardDialog = false }
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
            OutlinedButton({ actions.approveRedemption(item.id, false) }, Modifier.weight(1f), enabled = !loading) { Text("다시 의논하기", fontSize = 12.sp) }
            Button({ actions.approveRedemption(item.id, true) }, Modifier.weight(1f), enabled = !loading) { Text("사용 승인", fontSize = 12.sp) }
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
    val changes = state.events.filter { it.kind in setOf("reward_upsert", "reward_delete") && it.delivery.lowercase() !in setOf("relayed", "telegram_sent", "delivered") }
    LazyColumn(Modifier.fillMaxSize().testTag("reward-manager-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("아이와 함께 정한 선물과 스티커 개수예요.", fontSize = 14.sp, color = Muted) }
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
        items(state.rewards, key = { it.id }) { reward -> RewardCard(reward, null, !state.loading) { edit(reward.id) } }
        if (state.rewards.isEmpty()) item { Text("첫 약속을 추가해 보세요.", color = Muted, fontSize = 14.sp) }
        item { Button(add, Modifier.fillMaxWidth().height(50.dp).testTag("reward-add"), enabled = !state.loading) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("약속 추가") } }
        item { Text("이미 요청한 선물은 요청 당시의 이름과 스티커 개수로 남아요.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp) }
    }
}

@Composable
private fun RewardEditor(state: UiState, rewardId: String?, save: (String, Int) -> Unit, delete: () -> Unit) {
    val reward = state.rewards.firstOrNull { it.id == rewardId }
    var name by rememberSaveable(rewardId) { mutableStateOf(reward?.name.orEmpty()) }
    var costText by rememberSaveable(rewardId) { mutableStateOf(reward?.cost?.toString().orEmpty()) }
    var deleteDialog by rememberSaveable(rewardId) { mutableStateOf(false) }
    val cost = costText.toIntOrNull()
    val missing = rewardId != null && reward == null
    val valid = name.trim().isNotEmpty() && name.trim().length <= 60 && cost != null && cost in 1..999 && !missing
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("아이와 정한 선물 이름과 필요한 스티커 개수만 적어 주세요.", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        if (missing) Text("이 약속이 삭제되었어요. 목록으로 돌아가 다시 골라 주세요.", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(name, { if (it.length <= 60) name = it }, Modifier.fillMaxWidth().testTag("reward-name"), label = { Text("선물 이름") }, placeholder = { Text("함께 아이스크림 먹기") }, maxLines = 2, supportingText = { Text("${name.length}/60") }, enabled = !missing)
        OutlinedTextField(costText, { if (it.length <= 3 && it.all(Char::isDigit)) costText = it }, Modifier.fillMaxWidth().testTag("reward-cost"), label = { Text("필요한 스티커 개수") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("1개부터 999개까지 정할 수 있어요.") }, isError = costText.isNotEmpty() && (cost == null || cost !in 1..999), enabled = !missing)
        Button({ cost?.let { save(name.trim(), it) } }, Modifier.fillMaxWidth().height(50.dp).testTag("reward-save"), enabled = valid && !state.loading) { Text("약속 저장") }
        if (rewardId != null) TextButton({ deleteDialog = true }, Modifier.fillMaxWidth().testTag("reward-delete"), enabled = !state.loading && !missing) { Text("이 약속 삭제", color = MaterialTheme.colorScheme.error) }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("이 약속을 삭제할까요?") }, text = { Text("새 사용 요청 목록에서 지워집니다. 이미 요청한 선물과 사용 기록은 그대로 남아요.") }, confirmButton = { TextButton({ deleteDialog = false; delete() }, Modifier.testTag("reward-delete-confirm")) { Text("삭제") } }, dismissButton = { TextButton({ deleteDialog = false }) { Text("취소") } })
}

@Composable
private fun RewardSelection(state: UiState, select: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("지금 모은 스티커 ${state.stickerBalance}개", color = Muted, fontSize = 14.sp) }
        if (state.rewards.isEmpty()) item { EmptyCard(Icons.Outlined.Redeem, "아직 정한 약속이 없어요", "아빠와 선물과 필요한 스티커 개수를 정해 보세요.") }
        items(state.rewards, key = { it.id }) { reward -> RewardCard(reward, state.stickerBalance, !state.loading && state.stickerBalance >= reward.cost) { select(reward.id) } }
    }
}

@Composable
private fun RewardConfirmation(state: UiState, rewardId: String?, request: () -> Unit) {
    val reward = state.rewards.firstOrNull { it.id == rewardId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (reward == null) {
            Text("약속이 바뀌었어요. 칭찬판에서 다시 골라 주세요.", color = Muted)
        } else {
            SectionCard {
                Icon(Icons.Outlined.Redeem, null, Modifier.size(36.dp), tint = Gold)
                Text(reward.name, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("스티커 ${reward.cost}개", color = Forest, fontSize = 17.sp)
            }
            Text("지금 모은 스티커 ${state.stickerBalance}개\n아빠가 승인하면 ${reward.cost}개가 사용돼요.", color = Muted, lineHeight = 24.sp)
            if (state.stickerBalance < reward.cost) Text("${reward.cost - state.stickerBalance}개 더 모으면 돼요.", color = Gold)
            Button(request, Modifier.fillMaxWidth().height(52.dp).testTag("reward-request"), enabled = !state.loading && state.stickerBalance >= reward.cost) { Text("아빠에게 사용 요청하기") }
        }
    }
}

@Composable
private fun SettingsMenu(state: UiState, select: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("원하는 항목을 골라 주세요.", fontSize = 14.sp, color = Muted)
        SettingsMenuItem("자동 위치 공유", "현재 ${if (state.sharingEnabled) "켜짐" else "꺼짐"} · 공유 켜기 / 끄기", Icons.Outlined.MyLocation, "settings-location-menu-item") { select("settings-location") }
        SettingsMenuItem("공유 정보 안내", "공유하는 정보와 기록 보관", Icons.Outlined.Shield, "settings-info-menu-item") { select("settings-info") }
        SettingsMenuItem("가족 연결", "연결 상태 · 처음 안내 다시 보기", Icons.Outlined.FavoriteBorder, "settings-family-menu-item") { select("settings-family") }
        SettingsMenuItem("별 아이콘", if (state.overlayEnabled && state.overlayPermissionGranted) "켜짐 · 다른 앱에서 대화 열기" else "화면 위에 작게 띄워 두기", Icons.Outlined.StarOutline, "settings-overlay-menu-item") { select("settings-overlay") }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (section == "location") SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("자동 위치 공유", fontWeight = FontWeight.Medium)
                    Text(if (child) "움직일 때 5분 간격으로 아빠에게 알려요." else "자녀 휴대폰에서 직접 켜고 끌 수 있어요.", fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
                }
                Switch(state.sharingEnabled, onCheckedChange = { enabled -> if (enabled) consentDialog = true else actions.setSharing(false) }, enabled = child && !state.loading, modifier = Modifier.testTag("sharing-switch"))
            }
            Text(if (child) state.trackingStatus else "마지막으로 전달받은 자녀의 공유 설정입니다.", color = Forest, fontSize = 13.sp)
            HorizontalDivider(color = Cream)
            Text("공유 중에는 휴대폰에 알림이 표시돼요. 언제든 이 설정이나 알림에서 끌 수 있어요. 공유를 끄면 새 자동 수집을 멈춥니다.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
            Text("현재 위치를 한 번 보내는 기능은 대화 화면에서 별도로 사용할 수 있어요.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        }
        if (section == "info") SharedInformation()
        if (section == "overlay") OverlaySettings(state, actions)
        if (section == "family") {
            SectionCard {
                LabelValue("내 역할", if (child) "자녀" else "보호자")
                LabelValue("연결 상태", if (state.demoMode) "체험 모드 · 실제 전송 없음" else transportLabel(state.transport))
                if (!state.demoMode) {
                    LabelValue("서버", state.serverUrl)
                    LabelValue("메시지 알림", if (state.pushConfigured) "알림 연결됨" else "알림 연결 전 · 앱에서 새로 고침")
                    if (!state.pushConfigured) Text("앱이 닫혀 있을 때 알림을 받으려면 푸시 알림 연결이 필요해요. 지금은 앱을 열고 새로 고침해 주세요.", color = Gold, fontSize = 12.sp, lineHeight = 19.sp)
                    Text("‘텔레그램 경유 완료’는 받는 쪽 봇까지 전달되었다는 뜻이에요. 상대 휴대폰에 도착했거나 읽었다는 뜻은 아니에요.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                }
                TextButton(firstGuide, Modifier.testTag("first-guide-button")) { Icon(Icons.Outlined.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("처음 안내 다시 보기") }
                OutlinedButton(actions.refresh, Modifier.fillMaxWidth(), enabled = !state.loading) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("새로 고침") }
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
            Text("이동 중 5분 간격으로 GPS 좌표·측정 시각·정확도와 움직임 상태를 보호자에게 보냅니다. 기압계가 있으면 상대 높이 변화로 오르내림 시작과 종료도 추정해 보냅니다.", lineHeight = 22.sp)
            Text("화면이 꺼져 있어도 공유가 계속됩니다. 가족 중계 서버와 텔레그램을 통해 전달되며, 공유 중에는 휴대폰 알림이 표시됩니다. 언제든 이 설정에서 끌 수 있어요.", lineHeight = 22.sp)
            if (state.demoMode) Text("지금은 체험 모드여서 실제로 수집하거나 공유하지 않아요.", color = Gold, fontWeight = FontWeight.Medium)
        } },
        confirmButton = { TextButton({ consentDialog = false; actions.setSharing(true) }) { Text(if (state.demoMode) "공유 켜기 체험" else "동의하고 공유 켜기") } },
        dismissButton = { TextButton({ consentDialog = false }) { Text("나중에") } },
    )
    if (disconnectDialog) AlertDialog(onDismissRequest = { disconnectDialog = false }, title = { Text(if (state.demoMode) "체험을 마칠까요?" else "이 휴대폰 연결을 해제할까요?") }, text = { Text(if (state.demoMode) "처음 화면에서 우리 가족 서버에 연결할 수 있어요." else "이 휴대폰의 연결 정보를 지웁니다. 서버와 텔레그램에 저장된 기존 기록은 삭제되지 않습니다.") }, confirmButton = { TextButton({ disconnectDialog = false; actions.resetConfiguration() }) { Text(if (state.demoMode) "체험 마치기" else "연결 해제") } }, dismissButton = { TextButton({ disconnectDialog = false }) { Text("취소") } })
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
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, null, Modifier.size(22.dp), tint = Gold)
            Spacer(Modifier.width(9.dp))
            Text("별 아이콘", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Pill(if (active) "켜짐" else if (state.overlayEnabled) "권한 필요" else "꺼짐")
        }
        Text("다른 앱 위에 작은 반투명 별을 띄워 둬요. 별을 끌어 옮기거나 눌러서 대화를 열 수 있어요.", fontSize = 13.sp, color = Muted, lineHeight = 21.sp)
        LabelValue("화면 위 표시", if (state.overlayPermissionGranted) "허용됨" else "권한 필요")
        Text("별 아이콘을 켜거나 꺼도 자동 위치 공유 설정은 바뀌지 않아요.", fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
        if (state.demoMode) Text("체험 모드에서도 별 아이콘은 실제 화면 위에 표시돼요. 메시지·위치 전송은 하지 않아요.", fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
        if (active) {
            Button(actions.returnToStar, Modifier.fillMaxWidth().testTag("overlay-return")) { Text("별 아이콘으로 돌아가기") }
        } else {
            Button(actions.enableOverlay, Modifier.fillMaxWidth().testTag("overlay-enable")) { Text(if (state.overlayPermissionGranted) "별 아이콘 켜기" else "권한 설정 열기") }
        }
        if (state.overlayEnabled) OutlinedButton(actions.disableOverlay, Modifier.fillMaxWidth().testTag("overlay-disable")) { Text("별 아이콘 끄기") }
    }
}

@Composable
private fun SharedInformation() {
    SectionCard {
        Text("위치와 측정 시각", fontWeight = FontWeight.Medium)
        Text("GPS 좌표 · 측정 시각 · 위치 정확도 · 움직임 상태", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        Text("올라가고 내려가는 움직임", fontWeight = FontWeight.Medium)
        Text("기압계가 있으면 기압과 상대 높이 변화를 활용해 오르내림의 시작과 종료를 추정해요. 정확한 층수는 알 수 없고, 실내에서는 GPS 위치도 부정확할 수 있어요.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        Text("연결된 보호자에게 전달", fontWeight = FontWeight.Medium)
        Text("위치와 대화·칭찬 기록은 가족 중계 서버와 텔레그램을 통해 전달돼요. 기록은 서버 운영자가 삭제할 때까지 보관돼요. 텔레그램 기록은 별도로 관리해야 하며, 앱 연결 해제만으로 기존 기록이 삭제되지는 않아요.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun FirstGuide() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("오는 길을 함께 알 수 있어요.", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        SectionCard {
            Text("대화하면서 위치를 보낼 수 있어요", fontWeight = FontWeight.Medium)
            Text("대화의 ‘현재 위치 공유’를 누르면 그때의 위치를 한 번 아빠에게 보내요.", fontSize = 14.sp, lineHeight = 22.sp)
            Text("자동 공유는 따로 켜요", fontWeight = FontWeight.Medium)
            Text("자녀 대화에 정확히 ‘설정’을 보내고 자동 위치 공유를 고르세요. 동의하고 켜면 화면이 꺼져 있어도 움직임이 있는 5분 구간마다 위치를 기록해요. 필요한 위치·신체 활동·알림 권한을 요청해요.", fontSize = 14.sp, lineHeight = 22.sp)
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
        "relayed" -> "텔레그램 경유 완료"
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
    "telegram" -> "텔레그램 가족 연결"
    "unconfigured", "" -> "가족 연결 전"
    "offline" -> "연결을 기다리는 중"
    else -> transport
}
