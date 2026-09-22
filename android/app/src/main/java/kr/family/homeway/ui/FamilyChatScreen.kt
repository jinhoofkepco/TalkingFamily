package kr.family.homeway.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.family.homeway.data.FamilyEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ChatBackground = Color(0xFFEAF0EC)
private val ChatInk = Color(0xFF24362D)
private val ChatMuted = Color(0xFF64736A)
private val ChatYellow = Color(0xFFFFE394)
private val ChatGreen = Color(0xFF245B46)

/** Chat stays in the room; child location and praise use the authenticated parent/child care lane. */
@Composable
internal fun FamilyChatScreen(
    state: UiState,
    actions: UiActions,
    openSettings: () -> Unit,
    openStickers: () -> Unit,
    openFamilyRoom: () -> Unit,
) {
    val room = state.room
    var privateConversation by rememberSaveable(room?.id) { mutableStateOf(false) }
    val inRoom = room != null && !privateConversation
    val conversationKey = if (inRoom) room!!.id else "private-${state.role}"
    val privateDraft = rememberSaveable(state.role) { mutableStateOf("") }
    val roomDraft = rememberSaveable(state.role, room?.id) { mutableStateOf("") }
    var message by if (inRoom) roomDraft else privateDraft
    var toolsExpanded by rememberSaveable(conversationKey) { mutableStateOf(false) }
    var roomMenu by remember { mutableStateOf(false) }
    val parent = state.role == "guardian" || state.role == "parent"
    val canUsePrivateConnection = state.paired || state.demoMode
    val canUseCare = canUsePrivateConnection || state.careEnabled
    val keyboard = LocalSoftwareKeyboardController.current
    val events = remember(state.events, state.roomEvents, inRoom) {
        if (inRoom) state.roomEvents.filter { it.kind == "chat" }
        else state.events.filter { it.roomId == null && (it.kind == "chat" || (it.kind == "location" && it.payload.optString("source") == "manual")) }
    }
    val newestFirst = remember(events) { events.asReversed() }
    val memberNames = remember(room) { room?.members?.associate { it.botId to it.displayName }.orEmpty() }
    val title = if (inRoom) room!!.title else if (room != null) "기존 1:1 대화" else "우리 가족 대화"
    val participantCount = if (inRoom) room!!.members.size else if (state.configured || state.demoMode) 2 else 0

    fun isMine(event: FamilyEvent) = if (inRoom) event.senderId != null && event.senderId == state.selfBotId else event.sender == (state.privateRole ?: state.role)
    fun author(event: FamilyEvent): String = if (inRoom) {
        event.senderId?.let(memberNames::get) ?: event.senderName?.takeIf(String::isNotBlank) ?: "가족"
    } else if (event.sender == "child") "자녀" else "보호자"

    // Each conversation retains its own scroll/draft state while popups are open. Reversed items
    // keep an older message anchored by id when a new message is inserted at the bottom.
    key(conversationKey) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var initialLayout by remember { mutableStateOf(true) }
        var unseenMessages by remember { mutableIntStateOf(0) }
        var lastEventId by remember { mutableStateOf(events.lastOrNull()?.id) }
        var forceLatestAfterSend by remember { mutableStateOf(false) }
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    if (index == 0 && offset == 0) unseenMessages = 0
                }
        }
        LaunchedEffect(events.lastOrNull()?.id) {
            val latestId = events.lastOrNull()?.id
            if (latestId != null && (initialLayout || latestId != lastEventId)) {
                val previousLatestVisible = listState.layoutInfo.visibleItemsInfo.any { it.key == lastEventId }
                if (initialLayout || previousLatestVisible || forceLatestAfterSend) {
                    listState.scrollToItem(0)
                    unseenMessages = 0
                } else {
                    val previous = events.indexOfFirst { it.id == lastEventId }
                    unseenMessages += if (previous >= 0) events.lastIndex - previous else 1
                }
            }
            initialLayout = false
            forceLatestAfterSend = false
            lastEventId = latestId
        }
        val send: () -> Unit = {
            if (message.isNotBlank() && message.length <= 1500 && !state.loading) {
                // This exact raw command stays local, including when a child is in the family room.
                if (!parent && message == "설정") openSettings()
                else {
                    forceLatestAfterSend = true
                    if (inRoom) actions.sendRoomChat(message) else actions.sendChat(message)
                }
                message = ""
                toolsExpanded = false
            }
        }

        Column(Modifier.fillMaxSize().background(ChatBackground).imePadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Color(0xFFF8FAF8)).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).background(Color(0xFFDDEAE0), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                    Icon(if (inRoom) Icons.Outlined.Groups else Icons.Outlined.ChatBubbleOutline, null, Modifier.size(21.dp), tint = ChatGreen)
                }
                Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ChatInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (participantCount > 0) "${participantCount}명 · ${if (inRoom) "가족 단체방" else "개인 대화"}" else "가족을 연결해 주세요", fontSize = 10.sp, color = ChatMuted)
                }
                if (!parent && canUseCare) TextButton(
                    openStickers, Modifier.testTag("child-sticker-button"), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = Color(0xFFB67918))
                    Spacer(Modifier.width(3.dp))
                    Text(if (state.careEnabled && !state.careReady) "칭찬판" else "칭찬 ${state.stickerBalance}", fontSize = 12.sp)
                }
                Box {
                    IconButton({ roomMenu = true }, Modifier.testTag("chat-room-menu")) {
                        Icon(Icons.Outlined.MoreVert, "대화방 메뉴", Modifier.size(22.dp), tint = ChatGreen)
                    }
                    DropdownMenu(roomMenu, { roomMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (room == null) "가족 단체방 만들기 · 참여" else "가족방 관리") },
                            onClick = { roomMenu = false; keyboard?.hide(); openFamilyRoom() },
                            leadingIcon = { Icon(Icons.Outlined.Groups, null) },
                        )
                        if (room != null && canUsePrivateConnection) DropdownMenuItem(
                            text = { Text(if (inRoom) "기존 1:1 대화" else room.title) },
                            onClick = { roomMenu = false; privateConversation = inRoom },
                            modifier = Modifier.testTag("chat-switch-conversation"),
                            leadingIcon = { Icon(Icons.Outlined.SwapHoriz, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("앱 설정") },
                            onClick = { roomMenu = false; keyboard?.hide(); openSettings() },
                            modifier = Modifier.testTag("chat-open-settings"),
                            leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                        )
                    }
                }
                if (state.overlayEnabled && state.overlayPermissionGranted) IconButton(
                    { keyboard?.hide(); actions.returnToStar() }, Modifier.testTag("return-to-star"),
                ) { Icon(Icons.Outlined.Close, "별 아이콘으로 돌아가기", Modifier.size(21.dp), tint = ChatGreen) }
            }
            HorizontalDivider(color = Color(0xFFD8E3DB))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("chat-list"), state = listState, reverseLayout = true,
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                ) {
                    if (events.isEmpty()) item {
                        Text("첫 인사를 건네 보세요", Modifier.fillMaxWidth().padding(24.dp), color = ChatMuted, fontSize = 13.sp)
                    }
                    itemsIndexed(newestFirst, key = { _, event -> event.id }) { reverseIndex, event ->
                        val chronologicalIndex = events.lastIndex - reverseIndex
                        val previous = events.getOrNull(chronologicalIndex - 1)
                        val next = events.getOrNull(chronologicalIndex + 1)
                        val firstFromAuthor = !sameChatGroup(previous, event, inRoom)
                        val lastFromAuthor = !sameChatGroup(event, next, inRoom)
                        val showDate = previous == null || chatDay(previous.createdAt) != chatDay(event.createdAt)
                        Column {
                            if (showDate) ChatDateSeparator(event.createdAt)
                            CompactChatBubble(
                                event = event, mine = isMine(event), author = author(event),
                                showAuthor = firstFromAuthor, showTime = lastFromAuthor || isMine(event),
                                demo = state.demoMode, group = inRoom,
                            )
                        }
                    }
                    val hasMore = if (inRoom) state.roomHasMore else state.privateChatHasMore
                    val historyLoading = if (inRoom) state.roomLoading else state.privateChatLoading
                    if (hasMore || historyLoading) item(key = "previous-chat-history") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                            TextButton(
                                onClick = if (inRoom) actions.loadMoreRoomHistory else actions.loadMorePrivateChatHistory,
                                enabled = !historyLoading, modifier = Modifier.testTag("chat-load-more"),
                            ) {
                                if (historyLoading) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                                else Icon(Icons.Outlined.ExpandLess, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(if (historyLoading) "이전 메시지 불러오는 중" else "이전 메시지 더 보기", fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (unseenMessages > 0) FilledTonalButton(
                    onClick = {
                        unseenMessages = 0
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).testTag("chat-new-messages"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("새 메시지 ${unseenMessages}개", fontSize = 12.sp)
                }
            }
            if (toolsExpanded) {
                Row(
                    Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val replies = if (parent) listOf("잘했어!", "사랑해 ♥", "조심히 와") else listOf("지금 출발했어!", "도착했어!", "전화해 줘")
                    replies.forEach { text ->
                        SuggestionChip(
                            onClick = {
                                forceLatestAfterSend = true
                                if (inRoom) actions.sendRoomChat(text) else actions.sendChat(text)
                                toolsExpanded = false
                            }, label = { Text(text, fontSize = 12.sp) }, enabled = !state.loading,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton({ toolsExpanded = !toolsExpanded }, Modifier.testTag("chat-tools-toggle")) {
                    Icon(if (toolsExpanded) Icons.Outlined.Close else Icons.Outlined.Add, if (toolsExpanded) "빠른 답장 닫기" else "빠른 답장", Modifier.size(23.dp), tint = ChatMuted)
                }
                if (!parent && (state.careEnabled || (!inRoom && canUsePrivateConnection))) IconButton(
                    actions.shareCurrentLocation, Modifier.testTag("share-current-location"), enabled = !state.loading,
                ) { Icon(Icons.Outlined.MyLocation, if (state.careEnabled) "현재 위치 공유 · 부모님에게만 한 번 보내요" else "현재 위치 공유 · 한 번만 보내요", Modifier.size(20.dp), tint = ChatGreen) }
                BasicTextField(
                    message, { message = it },
                    Modifier.weight(1f).heightIn(min = 44.dp).padding(vertical = 3.dp)
                        .border(1.dp, if (message.length > 1500) MaterialTheme.colorScheme.error else Color(0xFFE0E5E0), RoundedCornerShape(20.dp))
                        .background(Color(0xFFF5F7F5), RoundedCornerShape(20.dp)).testTag("chat-input"),
                    textStyle = TextStyle(color = ChatInk, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(ChatGreen), maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { input ->
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            if (message.isEmpty()) Text("메시지 보내기", color = ChatMuted, fontSize = 15.sp)
                            input()
                        }
                    },
                )
                FilledIconButton(
                    send, Modifier.padding(start = 3.dp).size(48.dp).testTag("chat-send"),
                    enabled = message.isNotBlank() && message.length <= 1500 && !state.loading,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = ChatYellow, contentColor = ChatInk),
                ) { Icon(Icons.AutoMirrored.Outlined.Send, "메시지 보내기", Modifier.size(21.dp)) }
            }
            if (message.length > 1500) Text(
                "1,500자 이내로 적어 주세요.", Modifier.fillMaxWidth().background(Color.White).padding(start = 16.dp, bottom = 5.dp),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CompactChatBubble(
    event: FamilyEvent, mine: Boolean, author: String, showAuthor: Boolean, showTime: Boolean, demo: Boolean, group: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = if (showAuthor) 8.dp else 3.dp).testTag("chat-message-${event.id}"),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!mine) {
            if (showAuthor) Box(
                Modifier.size(30.dp).background(avatarColor(author), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center,
            ) { Text(author.take(1), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ChatGreen) }
            else Spacer(Modifier.width(30.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (!mine && showAuthor) Text(author, Modifier.padding(start = 1.dp, bottom = 3.dp), fontSize = 11.sp, lineHeight = 13.sp, color = ChatMuted)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (mine && showTime) ChatMessageMeta(event, demo, group, mine = true)
                Surface(
                    modifier = Modifier.widthIn(max = 280.dp).weight(1f, fill = false),
                    color = if (mine) ChatYellow else Color.White,
                    shape = RoundedCornerShape(if (mine || !showAuthor) 13.dp else 3.dp, if (!mine || !showAuthor) 13.dp else 3.dp, 13.dp, 13.dp),
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (event.kind == "location") {
                            Text(if (demo) "📍 체험 위치" else "📍 현재 위치", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ChatInk)
                            val latitude = event.payload.optDouble("latitude", Double.NaN)
                            val longitude = event.payload.optDouble("longitude", Double.NaN)
                            if (latitude.isFinite() && longitude.isFinite()) Text(String.format(Locale.KOREA, "%.6f, %.6f", latitude, longitude), fontSize = 11.sp, color = ChatMuted)
                            Text("측정 ${chatTime(event.measuredAt)}", fontSize = 10.sp, color = ChatMuted)
                        } else Text(event.payload.optString("text"), fontSize = 15.sp, lineHeight = 21.sp, color = ChatInk)
                    }
                }
                if (!mine && showTime) ChatMessageMeta(event, demo, group, mine = false)
            }
        }
        if (mine) Spacer(Modifier.width(1.dp))
    }
}

@Composable
private fun ChatMessageMeta(event: FamilyEvent, demo: Boolean, group: Boolean, mine: Boolean) {
    Column(Modifier.padding(bottom = 1.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (mine) Text(chatDeliveryText(event, demo, group), fontSize = 9.sp, lineHeight = 11.sp, color = ChatMuted)
        Text(chatTime(event.createdAt), fontSize = 9.sp, lineHeight = 11.sp, color = ChatMuted)
    }
}

@Composable
private fun ChatDateSeparator(value: String) {
    Box(Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 5.dp), contentAlignment = Alignment.Center) {
        Text(
            runCatching { DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREA).withZone(ZoneId.systemDefault()).format(chatInstant(value)) }.getOrDefault("날짜 확인 중"),
            Modifier.background(Color(0xFFDCE6DF), CircleShape).padding(horizontal = 11.dp, vertical = 4.dp),
            fontSize = 10.sp, color = ChatMuted,
        )
    }
}

internal fun sameChatGroup(first: FamilyEvent?, second: FamilyEvent?, room: Boolean): Boolean {
    if (first == null || second == null) return false
    if (room && first.roomId != second.roomId) return false
    val sameAuthor = if (room) first.senderId != null && first.senderId == second.senderId else first.sender == second.sender
    return sameAuthor && runCatching {
        chatInstant(first.createdAt).epochSecond / 60 == chatInstant(second.createdAt).epochSecond / 60
    }.getOrDefault(false)
}

internal fun chatDeliveryText(event: FamilyEvent, demo: Boolean, group: Boolean): String {
    if (demo) return "체험"
    if (group && event.recipientCount != null && event.recipientCount > 0 && event.deliveredTo != null) {
        return "${event.deliveredTo.coerceIn(0, event.recipientCount)}/${event.recipientCount} 전달"
    }
    return when (event.delivery.lowercase()) {
        "pending", "queued" -> "전송 대기"
        "sending" -> "전송 중"
        "relayed" -> "기기 수신"
        "telegram_sent", "delivered" -> "텔레그램 전달"
        "failed", "error" -> "전송 실패"
        else -> "상태 확인 중"
    }
}

private fun chatInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse { OffsetDateTime.parse(value).toInstant() }
private fun chatDay(value: String): String = runCatching { chatInstant(value).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.getOrDefault(value)
private fun chatTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA).withZone(ZoneId.systemDefault()).format(chatInstant(value))
}.getOrDefault("--:--")
private fun avatarColor(name: String): Color {
    val colors = listOf(Color(0xFFD6E9DE), Color(0xFFE8E0F4), Color(0xFFFFE4CE), Color(0xFFD9E8F5))
    return colors[(name.hashCode() and Int.MAX_VALUE) % colors.size]
}
