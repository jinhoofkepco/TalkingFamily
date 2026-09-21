package kr.family.homeway

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.family.homeway.data.AppRepository
import kr.family.homeway.data.DemoStore
import kr.family.homeway.data.FamilySnapshot
import kr.family.homeway.data.MovementHistoryCursor
import kr.family.homeway.data.MovementHistoryDates
import kr.family.homeway.tracking.CurrentLocationProvider
import kr.family.homeway.tracking.TrackingService
import kr.family.homeway.ui.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class HomewayViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppRepository(app)
    private val demo = DemoStore(app)
    private val mutableState = MutableStateFlow(UiState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var awaitedLocationId: String? = null
    private var historyJob: Job? = null
    private var historyCursor: MovementHistoryCursor? = null
    private var historyDaySelected = false
    private var historyGeneration = 0
    private var historyContext: Triple<String, Boolean, String>? = null
    init {
        val snapshot = if(repo.demoMode) demo.read() else repo.cached()
        render(snapshot)
    }
    private fun render(snapshot: FamilySnapshot, error: String? = null) {
        mutableState.update { old -> old.copy(
            role=repo.role, configured=repo.configured, demoMode=repo.demoMode,
            needsOnboarding=!repo.configured && !repo.demoMode,
            botUsername=repo.botUsername, peerBotUsername=repo.peerBotUsername,
            events=snapshot.events, stickerBalance=snapshot.stickerBalance, redemptions=snapshot.redemptions,
            rewards=snapshot.rewards,
            sharingEnabled=if(repo.isChild && !repo.demoMode) repo.sharingEnabled else snapshot.sharingEnabled,
            trackingStatus=if(repo.demoMode) "체험 기록 · 실제 위치를 수집하지 않아요" else repo.trackingStatus,
            transport=snapshot.transport, error=error ?: repo.connectionError
        ) }
        val context = Triple(repo.role, repo.demoMode, ZoneId.systemDefault().id)
        if (historyContext != context) {
            resetHistorySelection()
            historyContext = context
        }
        if (repo.role == "guardian") refreshHistory(snapshot)
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
        resetHistorySelection()
        render(snapshot)
        notice(if (role == "child") "텔레그램 봇을 연결했어요. 상대 휴대폰도 연결해 주세요. 자동 위치 공유는 대화에 '설정'을 보내 따로 켤 수 있어요."
            else "텔레그램 봇을 연결했어요. 자녀 휴대폰도 연결해 주세요. 자동 위치 공유는 자녀가 따로 켤 수 있어요.")
    }
    fun startDemo(role: String) = perform {
        refreshJob?.cancel()
        TrackingService.stopAndAwait(getApplication())
        repo.startDemo(role); demo.reset(); resetHistorySelection(); render(demo.read()); notice("체험 모드예요. 실제 위치 수집과 메시지 전송은 하지 않아요.")
    }
    fun switchDemoRole(role:String) {
        if (!repo.demoMode) return
        perform { repo.startDemo(role); render(demo.read()) }
    }
    fun refresh() {
        if(refreshJob?.isActive==true) return
        if(repo.demoMode) { render(demo.read()); return }
        if(!repo.configured) return
        refreshJob=viewModelScope.launch {
            try { render(repo.refresh()) }
            catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(_: Exception) { render(repo.cached(), repo.connectionError ?: "연결이 원활하지 않아요. 마지막 받은 기록을 표시하고 있어요.") }
        }
    }
    fun selectHistoryDay(day: String) {
        if (repo.role != "guardian" || runCatching { LocalDate.parse(day) }.isFailure) return
        historyJob?.cancel()
        historyGeneration++
        historyCursor = null
        historyDaySelected = true
        mutableState.update { it.copy(historyDay = day, locationHistory = emptyList(), historyHasMore = false) }
        refreshHistory(if (repo.demoMode) demo.read() else null, force = true)
    }
    fun loadMoreHistory() {
        if (repo.role != "guardian" || historyJob?.isActive == true || historyCursor == null) return
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
        val requested = mutableState.value.historyDay.takeIf { historyDaySelected }
        val cursor = if (append) historyCursor else null
        mutableState.update { it.copy(historyLoading = true) }
        historyJob = viewModelScope.launch {
            try {
                val page = if (isDemo) withContext(Dispatchers.Default) {
                    MovementHistoryDates.demoPage((snapshot ?: demo.read()).events, requested, cursor)
                } else repo.movementHistory(requested, cursor)
                if (generation != historyGeneration || repo.role != "guardian" || repo.demoMode != isDemo) return@launch
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
                if (generation != historyGeneration || repo.role != "guardian" || repo.demoMode != isDemo) return@launch
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
        if(text.isBlank()) return
        if(text.length>1500) { showError("메시지는 1,500자 이내로 보내 주세요."); return }
        send("chat",JSONObject().put("text",text.trim()))
    }
    fun awardSticker(reason:String) {
        if (!requireRole("guardian")) return
        send("sticker_award",JSONObject().put("count",1).put("reason",reason.trim().take(200).ifBlank { "참 잘했어요" }))
    }
    fun saveReward(id:String?, name:String, cost:Int) {
        if (!requireRole("guardian")) return
        val cleanedName = name.trim()
        if (cleanedName.isEmpty() || cleanedName.length > 60 || cost !in 1..999) {
            showError("약속 이름은 1~60자, 스티커는 1~999개로 입력해 주세요."); return
        }
        if (id != null && mutableState.value.rewards.none { it.id == id }) {
            showError("이 약속은 삭제되었어요. 새로고침 후 확인해 주세요."); return
        }
        send("reward_upsert",JSONObject().put("rewardId",id ?: UUID.randomUUID().toString()).put("name",cleanedName).put("cost",cost))
    }
    fun deleteReward(id:String) {
        if (!requireRole("guardian")) return
        if (mutableState.value.rewards.none { it.id == id }) {
            showError("이 약속은 이미 삭제되었어요."); return
        }
        send("reward_delete",JSONObject().put("rewardId",id))
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
        if (repo.role == role) return true
        showError(if (role == "guardian") "보호자만 바꿀 수 있어요." else "자녀 화면에서 사용할 수 있어요.")
        return false
    }
    private fun send(kind:String,payload:JSONObject) = perform {
        if(repo.demoMode) { render(demo.apply(kind,payload,repo.role)); return@perform }
        val id = UUID.randomUUID().toString()
        val accepted = repo.sendEvent(kind,payload,id)
        val snapshot = repo.cached()
        render(snapshot)
        val sent = snapshot.events.firstOrNull { it.id == id }
        if (sent?.delivery == "failed") {
            showError(sent.deliveryError ?: "요청을 처리하지 못했어요. 최신 약속과 스티커 수를 확인한 뒤 다시 시도해 주세요.")
            return@perform
        }
        if(!accepted) notice("상대 기기의 수신을 기다리고 있어요. 두 휴대폰의 인터넷 연결과 메시지 수신 설정을 확인해 주세요.")
        refresh()
    }
    fun shareCurrentLocation() = perform {
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
        awaitedLocationId=id
        val accepted=repo.sendEvent("location",JSONObject().put("latitude",location.latitude).put("longitude",location.longitude)
            .put("accuracy",location.accuracy.toDouble()).put("capturedAt",Instant.ofEpochMilli(location.time).toString()).put("source","manual"),id)
        render(repo.cached())
        notice(if(accepted) "보호자 휴대폰이 위치를 받았어요. 읽음 여부는 확인되지 않아요." else "위치가 전송 대기 중이에요. 두 휴대폰의 인터넷 연결과 메시지 수신 설정을 확인해 주세요.")
        refresh()
    }
    fun setSharing(enabled:Boolean) {
        if (!requireRole("child")) return
        if(repo.demoMode) { render(demo.apply("sharing_status",JSONObject().put("enabled",enabled),"child")); notice("체험 설정만 바뀌었어요. 실제 위치는 수집하지 않아요."); return }
        if(!repo.configured || !repo.isChild) return
        try {
            if(enabled) {
                repo.sharingEnabled=true
                TrackingService.start(getApplication())
                notice("자동 공유를 켰어요. 움직임이 없어도 약 5분마다 새 위치를 요청해요.")
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
            repo.reset(); resetHistorySelection(); mutableState.value=UiState()
        }
    }
    fun clearNotice() { mutableState.update { it.copy(notice=null,error=null) } }
    fun showError(message:String) { mutableState.update { it.copy(error=message,loading=false,notice=null) } }
    private fun notice(message:String) { mutableState.update { it.copy(notice=message) } }
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
