package kr.family.homeway

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.family.homeway.data.AppRepository
import kr.family.homeway.data.AppDiagnostics
import kr.family.homeway.data.FamilyNotifications
import kr.family.homeway.data.DemoStore
import kr.family.homeway.data.FamilySnapshot
import kr.family.homeway.data.FamilyEvent
import kr.family.homeway.data.FamilyChatCursor
import kr.family.homeway.data.FamilyChatMemberDraft
import kr.family.homeway.data.ChatHistoryPaging
import kr.family.homeway.data.MovementHistoryCursor
import kr.family.homeway.data.MovementHistoryDates
import kr.family.homeway.tracking.CurrentLocationProvider
import kr.family.homeway.tracking.TrackingService
import kr.family.homeway.ui.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class HomewayViewModel internal constructor(app: Application, private val repo: AppRepository) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AppRepository(app))
    private val demo = DemoStore(app)
    private val mutableState = MutableStateFlow(UiState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var awaitedLocationId: String? = null
    private var historyJob: Job? = null
    private var historyCursor: MovementHistoryCursor? = null
    private var historyDaySelected = false
    private var historyGeneration = 0
    private var historyContext: List<String>? = null
    private var privateChatJob: Job? = null
    private var privateChatCursor: MovementHistoryCursor? = null
    private var privateChatGeneration = 0
    private var privateChatContext: List<String>? = null
    private var privateChatEvents: List<FamilyEvent> = emptyList()
    private var privateChatRefreshPending = false
    private var roomHistoryJob: Job? = null
    private var roomHistoryCursor: FamilyChatCursor? = null
    private var roomHistoryGeneration = 0
    private var roomHistoryContext: String? = null
    private var roomHistoryRefreshPending = false
    init {
        val snapshot = if(repo.demoMode) demo.read() else repo.cached()
        render(snapshot)
        viewModelScope.launch {
            state.map { it.error }.distinctUntilChanged().collect {
                // Errors may include a family display name or an arbitrary exception payload.
                // Detailed, authored transport diagnostics are recorded at their safe call sites.
                if (it != null) AppDiagnostics.record(getApplication(), "ui.error", "화면 동작 오류가 발생했어요.")
            }
        }
        viewModelScope.launch {
            // The receive service commits locally before notifying. Rendering those rows must
            // never wait for its remaining ACKs, outgoing requests, or next long poll.
            repo.changes.collect { refreshCached() }
        }
        viewModelScope.launch {
            runCatching { repo.prepareCare() }.onFailure { showError(it.message ?: "가족 기록을 준비하지 못했어요.") }
            render(if (repo.demoMode) demo.read() else repo.cached())
        }
    }
    private fun render(snapshot: FamilySnapshot, error: String? = null, forcePrivateChat: Boolean = false, forceRoomChat: Boolean = false) {
        val activeRoom = repo.room
        val careEnabled = repo.careEnabled
        val selectedChild = repo.selectedCareChildId
        val care = if (careEnabled) repo.careSnapshot(selectedChild) else null
        val visible = if (careEnabled) care ?: FamilySnapshot.parse(kr.family.homeway.data.TelegramLedger.emptyState()) else snapshot
        val privateContext = listOf(repo.demoMode.toString(), repo.paired.toString(), repo.botUsername, repo.peerBotUsername)
        if (privateChatContext != privateContext) {
            resetPrivateChatHistory()
            privateChatContext = privateContext
        }
        if (roomHistoryContext != activeRoom?.id) {
            resetRoomHistory()
            roomHistoryContext = activeRoom?.id
        }
        mutableState.update { old -> old.copy(
            role=repo.effectiveRole, configured=repo.configured, demoMode=repo.demoMode,
            needsOnboarding=!repo.configured && !repo.demoMode,
            botUsername=repo.botUsername, peerBotUsername=repo.peerBotUsername,
            events=if (repo.demoMode) visible.events else chatChronology(visible.events.filter { it.kind != "chat" } + privateChatEvents),
            stickerBalance=visible.stickerBalance, redemptions=visible.redemptions,
            paired=repo.paired || repo.demoMode, room=activeRoom, selfBotId=repo.selfBotId,
            rewards=visible.rewards,
            careEnabled=careEnabled, privateRole=repo.role, careChildren=repo.careChildren, selectedChildBotId=selectedChild,
            careReady=care != null, carePending=repo.carePending(selectedChild), careStatus=repo.careStatus(selectedChild),
            sharingEnabled=if(repo.isChild && !repo.demoMode) repo.sharingEnabled else visible.sharingEnabled,
            trackingStatus=if(repo.demoMode) "체험 기록 · 실제 위치를 수집하지 않아요" else repo.trackingStatus,
            transport=snapshot.transport, error=error ?: repo.connectionError,
            messageNotificationSoundEnabled=FamilyNotifications.soundEnabled(getApplication())
        ) }
        if (!repo.demoMode && repo.paired) refreshPrivateChatHistory(force = forcePrivateChat)
        if (!repo.demoMode && activeRoom != null) refreshRoomHistory(force = forceRoomChat)
        val context = locationContext()
        if (historyContext != context) {
            resetHistorySelection()
            historyContext = context
        }
        if (repo.effectiveRole == "guardian") refreshHistory(visible)
        else mutableState.update { it.copy(locationHistory = emptyList(), historyDays = emptyList(), historyHasMore = false, historyLoading = false) }
        awaitedLocationId?.let { id ->
            val event = snapshot.events.firstOrNull { it.id == id }
            if(event?.delivery=="relayed") {
                awaitedLocationId=null
                notice("보호자 휴대폰이 위치를 받았어요. 읽음 여부는 확인되지 않아요.")
            } else if(event?.delivery=="failed") {
                awaitedLocationId=null
                showError("위치를 전달하지 못했어요. 연결 설정을 확인한 뒤 다시 보내 주세요.")
            }
        }
    }
    fun configure(role: String, botToken: String, peerBotUsername: String) = perform {
        TrackingService.stopAndAwait(getApplication())
        val snapshot = repo.configure(role,botToken,peerBotUsername)
        resetChatHistory()
        resetHistorySelection()
        render(snapshot)
        notice(if (role == "child") "텔레그램 봇을 연결했어요. 상대 휴대폰도 연결해 주세요. 자동 위치 공유는 대화에 '설정'을 보내 따로 켤 수 있어요."
            else "텔레그램 봇을 연결했어요. 자녀 휴대폰도 연결해 주세요. 자동 위치 공유는 자녀가 따로 켤 수 있어요.")
    }
    fun startDemo(role: String) = perform {
        refreshJob?.cancel()
        TrackingService.stopAndAwait(getApplication())
        repo.startDemo(role); demo.reset(); resetHistorySelection(); resetChatHistory(); render(demo.read()); notice("체험 모드예요. 실제 위치 수집과 메시지 전송은 하지 않아요.")
    }
    fun switchDemoRole(role:String) {
        if (!repo.demoMode) return
        perform { repo.startDemo(role); render(demo.read()) }
    }
    fun refreshCached() {
        render(if (repo.demoMode) demo.read() else repo.cached())
    }
    fun refresh() = refreshNetwork(scheduled = false)
    fun refreshScheduled() = refreshNetwork(scheduled = true)
    private fun refreshNetwork(scheduled: Boolean) {
        refreshCached()
        if(refreshJob?.isActive==true) return
        if(repo.demoMode) return
        if(!repo.configured) return
        refreshJob=viewModelScope.launch {
            try { render(if (scheduled) repo.refreshScheduled() else repo.refresh()) }
            catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(_: Exception) { render(repo.cached(), repo.connectionError ?: "연결이 원활하지 않아요. 마지막 받은 기록을 표시하고 있어요.") }
        }
    }
    private fun locationContext() = listOf(repo.effectiveRole, repo.demoMode.toString(), ZoneId.systemDefault().id,
        if (repo.careEnabled) repo.room?.id.orEmpty() else "", repo.selectedCareChildId?.toString().orEmpty())
    fun selectCareChild(childId: Long) {
        if (!repo.careEnabled || repo.careRole != "guardian" || childId == repo.selectedCareChildId) return
        repo.selectCareChild(childId)
        resetHistorySelection()
        render(repo.cached())
    }
    fun selectHistoryDay(day: String) {
        if (repo.effectiveRole != "guardian" || runCatching { LocalDate.parse(day) }.isFailure) return
        historyJob?.cancel()
        historyGeneration++
        historyCursor = null
        historyDaySelected = true
        mutableState.update { it.copy(historyDay = day, locationHistory = emptyList(), historyHasMore = false) }
        refreshHistory(if (repo.demoMode) demo.read() else null, force = true)
    }
    fun loadMoreHistory() {
        if (repo.effectiveRole != "guardian" || historyJob?.isActive == true || historyCursor == null) return
        refreshHistory(if (repo.demoMode) demo.read() else null, append = true)
    }
    private fun resetHistorySelection() {
        historyJob?.cancel()
        historyGeneration++
        historyCursor = null
        historyDaySelected = false
        mutableState.update { it.copy(locationHistory = emptyList(), historyDays = emptyList(),
            historyDay = LocalDate.now().toString(), historyHasMore = false, historyLoading = false) }
    }
    private fun refreshHistory(snapshot: FamilySnapshot? = null, append: Boolean = false, force: Boolean = false) {
        if (historyJob?.isActive == true && !force) return
        val generation = ++historyGeneration
        val isDemo = repo.demoMode
        val context = locationContext()
        val careRoomId = repo.room?.id.takeIf { repo.careEnabled }
        val childId = repo.selectedCareChildId
        val requested = mutableState.value.historyDay.takeIf { historyDaySelected }
        val cursor = if (append) historyCursor else null
        mutableState.update { it.copy(historyLoading = true) }
        historyJob = viewModelScope.launch {
            try {
                val page = if (isDemo) withContext(Dispatchers.Default) {
                    MovementHistoryDates.demoPage((snapshot ?: demo.read()).events, requested, cursor)
                } else repo.movementHistory(requested, cursor, careRoomId, childId)
                if (generation != historyGeneration || context != locationContext()) return@launch
                val oldHistory = mutableState.value
                val (preserve, events) = withContext(Dispatchers.Default) {
                    val old = oldHistory
                    val existingIds = old.locationHistory.map { it.id }.toHashSet()
                    // Retain already expanded rows on routine refreshes when the pages overlap.
                    // A large newly received batch without overlap starts a fresh contiguous page.
                    val preserve = old.historyDay == page.day && (append || page.events.any { it.id in existingIds })
                    val events = if (preserve) (page.events + old.locationHistory).distinctBy { it.id }
                        .map { (MovementHistoryDates.epoch(it) ?: Long.MIN_VALUE) to it }
                        .sortedWith(compareByDescending<Pair<Long, kr.family.homeway.data.FamilyEvent>> { it.first }.thenByDescending { it.second.id })
                        .map { it.second }
                        else page.events
                    preserve to events
                }
                if (generation != historyGeneration || context != locationContext()) return@launch
                historyDaySelected = historyDaySelected || page.days.isNotEmpty()
                if (append || !preserve) historyCursor = page.next
                mutableState.update { old ->
                    old.copy(locationHistory = events, historyDays = page.days, historyDay = page.day,
                        historyHasMore = historyCursor != null, historyLoading = false)
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) {
                if (generation == historyGeneration) mutableState.update { it.copy(historyLoading = false,
                    error = "이동 기록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.") }
            }
        }
    }
    fun sendChat(text:String) {
        if (!requirePaired()) return
        if(text.isBlank()) return
        if(text.length>1500) { showError("메시지는 1,500자 이내로 보내 주세요."); return }
        send("chat",JSONObject().put("text",text.trim()))
    }
    fun sendRoomChat(text: String) {
        if (text.isBlank()) return
        if (text.length > 1500) { showError("메시지는 1,500자 이내로 보내 주세요."); return }
        perform { repo.sendRoomChat(text.trim()); render(repo.cached(), forceRoomChat = true); refreshScheduled() }
    }
    fun createFamilyRoom(token: String, title: String, selfName: String, relationship: String, members: List<FamilyChatMemberDraft>) = perform {
        repo.createFamilyRoom(token, title, selfName, relationship, members)
        repo.prepareCare()
        if (!repo.canShareLocation && repo.sharingEnabled) TrackingService.stopAndAwait(getApplication())
        render(repo.cached())
        refresh()
    }
    fun joinFamilyRoom(token: String, code: String) = perform {
        repo.joinFamilyRoom(token, code)
        repo.prepareCare()
        if (!repo.canShareLocation && repo.sharingEnabled) TrackingService.stopAndAwait(getApplication())
        render(repo.cached())
        refresh()
    }
    fun leaveFamilyRoom() = perform {
        if (repo.sharingEnabled && (!repo.paired || repo.role != "child")) {
            TrackingService.stopAndAwait(getApplication())
            repo.sharingEnabled = false
        }
        repo.leaveFamilyRoom()
        render(repo.cached())
    }

    fun loadMorePrivateChatHistory() {
        if (!repo.paired || repo.demoMode || privateChatJob?.isActive == true || privateChatCursor == null) return
        refreshPrivateChatHistory(append = true)
    }
    fun loadMoreRoomHistory() {
        if (repo.room == null || repo.demoMode || roomHistoryJob?.isActive == true || roomHistoryCursor == null) return
        refreshRoomHistory(append = true)
    }
    private fun resetPrivateChatHistory() {
        privateChatJob?.cancel(); privateChatGeneration++; privateChatCursor = null; privateChatEvents = emptyList()
        privateChatRefreshPending = false
        mutableState.update { it.copy(privateChatHasMore = false, privateChatLoading = false) }
    }
    private fun resetRoomHistory() {
        roomHistoryJob?.cancel(); roomHistoryGeneration++; roomHistoryCursor = null
        roomHistoryRefreshPending = false
        mutableState.update { it.copy(roomEvents = emptyList(), roomHasMore = false, roomLoading = false) }
    }
    private fun resetChatHistory() {
        resetPrivateChatHistory(); resetRoomHistory(); privateChatContext = null; roomHistoryContext = null
    }

    private fun refreshPrivateChatHistory(append: Boolean = false, force: Boolean = false) {
        if (privateChatJob?.isActive == true) {
            if (!force) {
                if (!append) privateChatRefreshPending = true
                return
            }
            privateChatJob?.cancel()
        }
        privateChatRefreshPending = false
        val generation = ++privateChatGeneration
        val context = privateChatContext
        val previous = privateChatEvents
        val oldestLoadedId = previous.firstOrNull()?.id
        val before = if (append) privateChatCursor else null
        mutableState.update { it.copy(privateChatLoading = true) }
        privateChatJob = viewModelScope.launch {
            try {
                var page = repo.privateChatHistory(before)
                val loaded = page.events.toMutableList()
                if (!append) {
                    // Show the latest page before rereading a user-expanded history for ACKs.
                    val immediate = withContext(Dispatchers.Default) { chatChronology(page.events + previous) }
                    if (generation != privateChatGeneration || context != privateChatContext || !repo.paired || repo.demoMode) return@launch
                    privateChatEvents = immediate
                    mutableState.update { it.copy(events = chatChronology(it.events.filter { event -> event.kind != "chat" } + immediate)) }
                }
                // Re-read the already expanded range so delayed ACKs also update old visible bubbles.
                // A fresh open reads just 200; only user-expanded history increases this range.
                while (!append && oldestLoadedId != null && loaded.none { it.id == oldestLoadedId } && page.nextCursor != null) {
                    page = repo.privateChatHistory(page.nextCursor)
                    loaded.addAll(page.events)
                }
                if (generation != privateChatGeneration || context != privateChatContext || !repo.paired || repo.demoMode) return@launch
                val window = withContext(Dispatchers.Default) { ChatHistoryPaging.merge(previous, loaded, append, page.nextCursor != null) }
                if (generation != privateChatGeneration || context != privateChatContext || !repo.paired || repo.demoMode) return@launch
                privateChatCursor = window.next?.let {
                    MovementHistoryCursor(Instant.parse(it.createdAt).toEpochMilli(), it.id)
                }
                privateChatEvents = window.events
                mutableState.update { old -> old.copy(events = chatChronology(old.events.filter { it.kind != "chat" } + privateChatEvents),
                    privateChatHasMore = privateChatCursor != null, privateChatLoading = false) }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) {
                if (generation == privateChatGeneration) mutableState.update { it.copy(privateChatLoading = false,
                    error = "이전 대화를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.") }
            } finally {
                if (generation == privateChatGeneration) {
                    privateChatJob = null
                    if (privateChatRefreshPending && repo.paired && !repo.demoMode) refreshPrivateChatHistory()
                }
            }
        }
    }

    private fun refreshRoomHistory(append: Boolean = false, force: Boolean = false) {
        if (roomHistoryJob?.isActive == true) {
            if (!force) {
                if (!append) roomHistoryRefreshPending = true
                return
            }
            roomHistoryJob?.cancel()
        }
        roomHistoryRefreshPending = false
        val active = repo.room ?: return
        val generation = ++roomHistoryGeneration
        val previous = mutableState.value.roomEvents
        val oldestLoadedId = previous.firstOrNull()?.id
        val before = if (append) roomHistoryCursor else null
        mutableState.update { it.copy(roomLoading = true) }
        roomHistoryJob = viewModelScope.launch {
            try {
                var page = repo.roomHistory(before)
                val loaded = page.messages.toMutableList()
                if (!append) {
                    val immediate = withContext(Dispatchers.Default) { chatChronology(page.messages.map { repo.roomEvent(it, active) } + previous) }
                    if (generation != roomHistoryGeneration || repo.room?.id != active.id || repo.demoMode) return@launch
                    mutableState.update { it.copy(roomEvents = immediate) }
                }
                while (!append && oldestLoadedId != null && loaded.none { it.id == oldestLoadedId } && page.next != null) {
                    page = repo.roomHistory(page.next)
                    loaded.addAll(page.messages)
                }
                if (generation != roomHistoryGeneration || repo.room?.id != active.id || repo.demoMode) return@launch
                val window = withContext(Dispatchers.Default) {
                    ChatHistoryPaging.merge(previous, loaded.map { repo.roomEvent(it, active) }, append, page.next != null)
                }
                if (generation != roomHistoryGeneration || repo.room?.id != active.id || repo.demoMode) return@launch
                roomHistoryCursor = window.next?.let { FamilyChatCursor(it.createdAt, it.id) }
                mutableState.update { it.copy(roomEvents = window.events,
                    roomHasMore = roomHistoryCursor != null, roomLoading = false) }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) {
                if (generation == roomHistoryGeneration) mutableState.update { it.copy(roomLoading = false,
                    error = "가족방 대화를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.") }
            } finally {
                if (generation == roomHistoryGeneration) {
                    roomHistoryJob = null
                    if (roomHistoryRefreshPending && repo.room?.id == active.id && !repo.demoMode) refreshRoomHistory()
                }
            }
        }
    }

    private fun chatChronology(events: List<FamilyEvent>): List<FamilyEvent> = ChatHistoryPaging.ordered(events)
    fun awardSticker(reason:String) {
        if (!requireRole("guardian")) return
        send("sticker_award",JSONObject().put("count",1).put("reason",reason.trim().take(200).ifBlank { "참 잘했어요" }))
    }
    fun saveReward(id:String?, name:String, cost:Int) = saveRewardInternal(id, name, cost, null)
    fun saveRewardVersioned(id:String?, name:String, cost:Int, version:Long) = saveRewardInternal(id, name, cost, version)
    private fun saveRewardInternal(id:String?, name:String, cost:Int, version:Long?) {
        if (!requireRole("guardian")) return
        val cleanedName = name.trim()
        if (cleanedName.isEmpty() || cleanedName.length > 60 || cost !in 1..999) {
            showError("약속 이름은 1~60자, 스티커는 1~999개로 입력해 주세요."); return
        }
        if (id != null && mutableState.value.rewards.none { it.id == id }) {
            showError("이 약속은 삭제되었어요. 새로고침 후 확인해 주세요."); return
        }
        send("reward_upsert",JSONObject().put("rewardId",id ?: UUID.randomUUID().toString()).put("name",cleanedName).put("cost",cost), version)
    }
    fun deleteReward(id:String) = deleteRewardInternal(id, null)
    fun deleteRewardVersioned(id:String, version:Long) = deleteRewardInternal(id, version)
    private fun deleteRewardInternal(id:String, version:Long?) {
        if (!requireRole("guardian")) return
        if (mutableState.value.rewards.none { it.id == id }) {
            showError("이 약속은 이미 삭제되었어요."); return
        }
        send("reward_delete",JSONObject().put("rewardId",id), version)
    }
    fun requestRedemption(rewardId:String) {
        if (!requireRole("child")) return
        val reward = mutableState.value.rewards.firstOrNull { it.id == rewardId }
        if (reward == null) { showError("약속이 변경되었어요. 다시 골라 주세요."); return }
        if (mutableState.value.stickerBalance < reward.cost) { showError("모은 스티커 안에서 골라 주세요."); return }
        send("sticker_redeem_request",JSONObject().put("rewardId",reward.id).put("reward",reward.name).put("cost",reward.cost))
    }
    fun approveRedemption(id:String,accepted:Boolean) {
        if (!requireRole("guardian")) return
        send("sticker_redeem_approve",JSONObject().put("requestId",id).put("accepted",accepted))
    }
    private fun requireRole(role:String): Boolean {
        if (!repo.careEnabled && !requirePaired()) return false
        if (repo.effectiveRole == role) return true
        showError(if (role == "guardian") "보호자만 바꿀 수 있어요." else "자녀 화면에서 사용할 수 있어요.")
        return false
    }
    private fun requirePaired(): Boolean {
        if (repo.demoMode || repo.paired) return true
        showError("1:1 가족 연결이 필요해요. 가족방에서는 대화와 자녀의 위치·칭찬판을 함께 사용할 수 있어요.")
        return false
    }
    private fun send(kind:String,payload:JSONObject, expectedVersion:Long? = null) {
        // Capture the displayed child and reward revision before any coroutine can switch screens.
        val roomId = repo.room?.id.takeIf { repo.careEnabled && kind != "chat" }
        val childId = repo.selectedCareChildId
        val rewardId = payload.optString("rewardId")
        val rewardVersion = if (kind in setOf("reward_upsert", "reward_delete", "sticker_redeem_request"))
            expectedVersion ?: mutableState.value.rewards.firstOrNull { it.id == rewardId }?.version ?: 0L else null
        if (roomId != null && (!mutableState.value.careReady || mutableState.value.carePending)) {
            showError("자녀 휴대폰의 최신 칭찬판과 처리 결과를 기다려 주세요."); return
        }
        perform {
        if(repo.demoMode) { render(demo.apply(kind,payload,repo.role)); return@perform }
        val id = UUID.randomUUID().toString()
        if (roomId != null && childId != null) {
            repo.sendCareAction(roomId, childId, kind, payload, id, rewardVersion)
            render(repo.cached())
            refreshScheduled()
            return@perform
        }
        val accepted = repo.sendEvent(kind,payload,id)
        val snapshot = repo.cached()
        render(snapshot, forcePrivateChat = kind == "chat")
        val sent = snapshot.events.firstOrNull { it.id == id }
        if (sent?.delivery == "failed") {
            showError(sent.deliveryError ?: "요청을 처리하지 못했어요. 최신 약속과 스티커 수를 확인한 뒤 다시 시도해 주세요.")
            return@perform
        }
        if(!accepted) notice("상대 기기의 수신을 기다리고 있어요. 두 휴대폰의 인터넷 연결과 메시지 수신 설정을 확인해 주세요.")
        refreshScheduled()
        }
    }
    fun shareCurrentLocation() = perform {
        if (!repo.careEnabled && !requirePaired()) return@perform
        check(repo.isChild) { "자녀 화면에서 위치를 공유할 수 있어요." }
        if(repo.demoMode) {
            render(demo.apply("location",JSONObject().put("latitude",37.5665).put("longitude",126.978)
                .put("accuracy",12).put("capturedAt",Instant.now().toString()).put("source","manual"),"child"))
            notice("체험 위치를 대화에 표시했어요. 실제로 전송하지 않았어요.")
            return@perform
        }
        notice("현재 위치를 확인하고 있어요…")
        val location = CurrentLocationProvider.capture(getApplication())
        val id = UUID.randomUUID().toString()
        awaitedLocationId=if (repo.careEnabled) null else id
        val accepted=repo.sendEvent("location",JSONObject().put("latitude",location.latitude).put("longitude",location.longitude)
            .put("accuracy",location.accuracy.toDouble()).put("capturedAt",Instant.ofEpochMilli(location.time).toString()).put("source","manual"),id)
        render(repo.cached())
        notice(if(accepted) "보호자 휴대폰이 위치를 받았어요. 읽음 여부는 확인되지 않아요." else "위치가 전송 대기 중이에요. 두 휴대폰의 인터넷 연결과 메시지 수신 설정을 확인해 주세요.")
        refreshScheduled()
    }
    fun setSharing(enabled:Boolean) {
        if (!requireRole("child")) return
        if(repo.demoMode) { render(demo.apply("sharing_status",JSONObject().put("enabled",enabled),"child")); notice("체험 설정만 바뀌었어요. 실제 위치는 수집하지 않아요."); return }
        if(!repo.configured || !repo.isChild) return
        try {
            if(enabled) {
                repo.sharingEnabled=true
                TrackingService.start(getApplication())
                notice("자동 공유를 켰어요. 이동 중 약 20초, 정지 중 약 5분마다 새 위치를 요청해요.")
            } else {
                TrackingService.stop(getApplication())
                repo.sharingEnabled=false
                notice("자동 위치 공유를 중지했어요.")
            }
            render(repo.cached())
        } catch(e:Exception) {
            repo.sharingEnabled=false
            showError(e.message ?: "자동 공유를 시작하지 못했어요. 위치와 알림 권한을 확인해 주세요.")
        }
    }
    fun resetConfiguration() {
        if(repo.sharingEnabled) { showError("자동 위치 공유를 먼저 끈 뒤 연결을 해제해 주세요."); return }
        refreshJob?.cancel(); awaitedLocationId=null
        perform {
            TrackingService.stopAndAwait(getApplication())
            repo.reset(); resetHistorySelection(); resetChatHistory(); mutableState.value=UiState()
        }
    }
    fun clearNotice() { mutableState.update { it.copy(notice=null,error=null) } }
    fun showError(message:String) { mutableState.update { it.copy(error=message,loading=false,notice=null) } }
    private fun notice(message:String) { AppDiagnostics.record(getApplication(), "ui.notice", message) }
    fun setMessageNotificationSoundEnabled(enabled: Boolean) {
        FamilyNotifications.setSoundEnabled(getApplication(), enabled)
        mutableState.update { it.copy(messageNotificationSoundEnabled = FamilyNotifications.soundEnabled(getApplication())) }
        AppDiagnostics.record(getApplication(), "notification.preference", if (enabled) "메시지 알림 소리 켜짐" else "메시지 알림 소리 꺼짐")
    }
    private fun perform(block:suspend () -> Unit) {
        if(mutableState.value.loading) return
        mutableState.update { it.copy(loading=true,error=null) }
        viewModelScope.launch {
            try { block() } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:Exception) { showError(e.message ?: "잠시 후 다시 시도해 주세요.") }
            finally { mutableState.update { it.copy(loading=false) } }
        }
    }
}
