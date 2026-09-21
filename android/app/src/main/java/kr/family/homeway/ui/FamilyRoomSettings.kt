package kr.family.homeway.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.family.homeway.data.FamilyChatMemberDraft
import kr.family.homeway.data.FamilyChatRoom

private val relationships = listOf("mother" to "엄마", "father" to "아빠", "son" to "아들", "daughter" to "딸", "family" to "가족")

@Composable
internal fun FamilyRoomSettings(
    state: UiState,
    actions: UiActions,
    initialMode: String = "create",
    openReceiveSettings: (() -> Unit)? = null,
    openOverlaySettings: (() -> Unit)? = null,
) {
    var mode by rememberSaveable(initialMode) { mutableStateOf(initialMode) }
    var token by remember { mutableStateOf("") }
    var roomTitle by rememberSaveable { mutableStateOf("우리 가족") }
    val defaultRelationship = if (state.paired && state.role == "child") "son" else "father"
    var selfName by rememberSaveable { mutableStateOf(relationships.first { it.first == defaultRelationship }.second) }
    var selfRelationship by rememberSaveable { mutableStateOf(defaultRelationship) }
    val drafts = remember {
        mutableStateListOf<FamilyChatMemberDraft>().apply {
            relationships.filter { it.first != defaultRelationship && it.first != "family" }.forEach {
                add(FamilyChatMemberDraft("", it.second, it.first))
            }
        }
    }
    var joinCode by rememberSaveable { mutableStateOf("") }
    var acknowledged by rememberSaveable(mode, joinCode) { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var leaveDialog by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val canReuseToken = state.botUsername.isNotBlank() && !state.demoMode
    val tokenReady = canReuseToken || token.isNotBlank()
    val active = state.room

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(18.dp).testTag("family-room-settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("엄마·아빠·아들·딸, 한 방에서 함께 대화해요.", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("가족방에는 대화만 공유해요. 위치와 칭찬판은 기존 1:1 연결에서 이용해요.", fontSize = 13.sp, lineHeight = 20.sp)
        if (state.demoMode) {
            RoomCard {
                Text("가족방 연결은 체험을 마친 뒤 할 수 있어요.", fontWeight = FontWeight.Medium)
                Text("체험 중에는 실제 봇을 연결하거나 메시지를 보내지 않아요.", fontSize = 13.sp)
                Button(actions.resetConfiguration, Modifier.fillMaxWidth().testTag("room-exit-demo")) { Text("체험 마치고 가족 연결하기") }
            }
        } else if (active != null) {
            RoomCard {
                Text(active.title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("가족 ${active.members.size}명", fontSize = 13.sp)
                RoomRoster(active, state.selfBotId)
                Button(
                    {
                        clipboard.setText(AnnotatedString(active.toCode()))
                        copied = true
                    }, Modifier.fillMaxWidth().testTag("room-copy-code"),
                ) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (copied) "가족방 코드를 복사했어요" else "가족방 코드 복사")
                }
                Text("다른 가족은 자신의 봇 토큰과 이 코드로 참여해요. 코드는 가족 명단을 담고 있으며 봇 토큰은 포함하지 않아요.", fontSize = 12.sp, lineHeight = 19.sp)
                Text("명단에 등록한 봇만 참여할 수 있어요. 구성원을 바꾸려면 새 가족방을 만들고 새 코드를 함께 사용해 주세요.", fontSize = 12.sp, lineHeight = 19.sp)
            }
            if (openReceiveSettings != null || openOverlaySettings != null) RoomCard {
                Text("계속 연결해 두기", fontWeight = FontWeight.Bold)
                Text("각 가족 휴대폰에서 메시지 수신을 켜 두세요. 절전·강제 종료·통신 상태에 따라 수신이 늦어질 수 있어요.", fontSize = 13.sp, lineHeight = 20.sp)
                openReceiveSettings?.let { OutlinedButton(it, Modifier.fillMaxWidth().testTag("room-receive-settings")) { Text("메시지 수신 설정") } }
                openOverlaySettings?.let { OutlinedButton(it, Modifier.fillMaxWidth().testTag("room-overlay-settings")) { Text("별 아이콘 설정") } }
            }
            OutlinedButton({ leaveDialog = true }, Modifier.fillMaxWidth().testTag("room-leave"), enabled = !state.loading) { Text("이 휴대폰에서 가족방 나가기") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == "create", { mode = "create" }, { Text("새 가족방 만들기") }, Modifier.weight(1f).testTag("room-mode-create"))
                FilterChip(mode == "join", { mode = "join" }, { Text("코드로 참여") }, Modifier.weight(1f).testTag("room-mode-join"))
            }
            RoomCard {
                Text("휴대폰마다 텔레그램 봇 하나", fontWeight = FontWeight.Bold)
                Text("각자 BotFather에서 봇을 만들고 Bot-to-Bot Communication Mode를 켜 주세요. 같은 봇 토큰을 여러 휴대폰에서 함께 사용하면 수신이 엇갈릴 수 있어요.", fontSize = 13.sp, lineHeight = 20.sp)
                if (canReuseToken) Text("이 휴대폰에 연결된 @${state.botUsername.removePrefix("@")} 봇을 사용해요.", fontSize = 13.sp)
                OutlinedTextField(
                    token, { token = it }, Modifier.fillMaxWidth().testTag("room-token-input"),
                    label = { Text(if (canReuseToken) "이 휴대폰 봇 토큰 · 선택" else "이 휴대폰 봇 토큰") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text(if (canReuseToken) "비워 두면 기존 토큰을 사용해요. 연결을 복구하려면 같은 봇의 토큰을 입력하세요." else "다른 가족의 토큰은 필요하지 않아요.") },
                )
                Text("대화는 텔레그램 봇을 거쳐 각 가족 휴대폰에 저장돼요. 봇 대화는 종단간 암호화되지 않아요.", fontSize = 12.sp, lineHeight = 19.sp)
            }
            if (mode == "create") {
                RoomCard {
                    OutlinedTextField(roomTitle, { roomTitle = it }, Modifier.fillMaxWidth().testTag("room-title-input"), label = { Text("가족방 이름") }, singleLine = true, isError = roomTitle.length > 40)
                    Text("내 프로필", fontWeight = FontWeight.Bold)
                    OutlinedTextField(selfName, { selfName = it }, Modifier.fillMaxWidth().testTag("room-self-name"), label = { Text("방에서 부를 내 이름") }, singleLine = true, isError = selfName.length > 20)
                    RelationshipPicker(selfRelationship, { selfRelationship = it }, "room-self-relationship")
                }
                drafts.forEachIndexed { index, draft ->
                    RoomCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("가족 ${index + 1}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            if (drafts.size > 1) IconButton({ drafts.removeAt(index) }, Modifier.testTag("room-remove-member-$index"), enabled = !state.loading) {
                                Icon(Icons.Outlined.Close, "가족 ${index + 1} 삭제")
                            }
                        }
                        OutlinedTextField(draft.displayName, { drafts[index] = draft.copy(displayName = it) }, Modifier.fillMaxWidth().testTag("room-member-name-$index"), label = { Text("방에서 부를 이름") }, singleLine = true, isError = draft.displayName.length > 20)
                        RelationshipPicker(draft.relationship, { drafts[index] = draft.copy(relationship = it) }, "room-member-relationship-$index")
                        OutlinedTextField(draft.username, { drafts[index] = draft.copy(username = it) }, Modifier.fillMaxWidth().testTag("room-member-bot-$index"), label = { Text("이 가족의 봇 사용자명") }, placeholder = { Text("@family_member_bot") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    }
                }
                if (drafts.size < 7) OutlinedButton(
                    { drafts.add(FamilyChatMemberDraft("", "", "family")) }, Modifier.fillMaxWidth().testTag("room-add-member"), enabled = !state.loading,
                ) { Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("가족 추가 · 나 포함 ${drafts.size + 1}/8명") }
                RoomConsent(acknowledged, { acknowledged = it })
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Button(
                    { actions.createFamilyRoom(token.trim(), roomTitle.trim(), selfName.trim(), selfRelationship, drafts.toList()) },
                    Modifier.fillMaxWidth().height(50.dp).testTag("room-create"),
                    enabled = !state.loading && tokenReady && acknowledged && roomTitle.isNotBlank() && roomTitle.length <= 40 && selfName.isNotBlank() && selfName.length <= 20 && drafts.all { it.username.isNotBlank() && it.displayName.isNotBlank() && it.displayName.length <= 20 },
                ) { if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("가족방 만들고 코드 받기") }
            } else {
                OutlinedTextField(
                    joinCode, { joinCode = it.take(8192) }, Modifier.fillMaxWidth().testTag("room-code-input"),
                    label = { Text("가족방 코드") }, placeholder = { Text("가족이 보내 준 TFROOM1: 코드를 붙여 넣어요") }, minLines = 3, maxLines = 5,
                )
                val parsed = remember(joinCode) { runCatching { FamilyChatRoom.fromCode(joinCode) } }
                val preview = parsed.getOrNull()
                if (preview != null) RoomCard {
                    Text("참여할 가족방", fontSize = 12.sp)
                    Text(preview.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    RoomRoster(preview, state.selfBotId)
                    Text("이 명단에 등록된 내 봇으로 참여해요. 모르는 명단이면 가족에게 코드를 다시 확인해 주세요.", fontSize = 12.sp, lineHeight = 19.sp)
                } else if (joinCode.isNotBlank()) Text("가족방 코드를 확인해 주세요.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                RoomConsent(acknowledged, { acknowledged = it })
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Button(
                    { actions.joinFamilyRoom(token.trim(), joinCode.trim()) }, Modifier.fillMaxWidth().height(50.dp).testTag("room-join"),
                    enabled = !state.loading && tokenReady && acknowledged && preview != null,
                ) { if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("명단 확인하고 가족방 참여") }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (leaveDialog) AlertDialog(
        onDismissRequest = { leaveDialog = false }, modifier = Modifier.testTag("room-leave-dialog"),
        title = { Text("이 휴대폰에서 가족방을 나갈까요?") },
        text = { Text("새 가족방 메시지의 송수신을 멈춥니다. 기존 1:1 연결과 이 휴대폰의 대화 기록은 유지돼요. 다른 가족은 계속 대화할 수 있어요.") },
        confirmButton = { TextButton({ leaveDialog = false; actions.leaveFamilyRoom() }, Modifier.testTag("room-leave-confirm")) { Text("가족방 나가기") } },
        dismissButton = { TextButton({ leaveDialog = false }) { Text("계속 참여") } },
    )
}

@Composable
private fun RoomRoster(room: FamilyChatRoom, selfId: Long) {
    room.members.forEach { member ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.PersonOutline, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(member.displayName + if (member.botId == selfId) " · 나" else "", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("${relationships.firstOrNull { it.first == member.relationship }?.second ?: "가족"} · @${member.username.removePrefix("@")}", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun RelationshipPicker(value: String, choose: (String) -> Unit, tag: String) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().testTag(tag)) {
            Text("가족 관계: ${relationships.firstOrNull { it.first == value }?.second ?: "가족"}", Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            relationships.forEach { (code, label) -> DropdownMenuItem({ Text(label) }, { choose(code); expanded = false }) }
        }
    }
}

@Composable
private fun RoomConsent(checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, change, Modifier.testTag("room-consent"))
        Text("이 명단의 가족과 대화 내용을 공유하는 데 동의해요.", fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun RoomCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp), content = content)
    }
}
