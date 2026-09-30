package kr.family.homeway.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Each phone owns one bot. No family server or shared bot token. */
class AppRepository internal constructor(context: Context, private val clientFactory: (String) -> TelegramClient = ::TelegramClient) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("homeway_settings", Context.MODE_PRIVATE)
    private val vault = TokenVault(app)
    private val store = LocalStore.get(app)
    val changes = changeRevision.asStateFlow()
    private fun publishChanges() { changeRevision.update { it + 1 } }
    val role: String get() = prefs.getString("role", "child")!!
    val botUsername: String get() = prefs.getString("botUsername", "")!!
    val peerBotUsername: String get() = prefs.getString("peerBotUsername", "")!!
    val connectionError: String? get() = prefs.getString("connectionError", null)
    val demoMode: Boolean get() = prefs.getBoolean("demoMode", false)
    private val credentialsReady: Boolean get() = !demoMode && prefs.getString("transport", "") == "telegram_direct" && vault.get().isNotBlank()
    val paired: Boolean get() = credentialsReady && prefs.getLong("peerBotId", 0) > 0
    val selfBotId: Long get() = prefs.getLong("ownBotId", 0)
    val room: FamilyChatRoom? get() = synchronized(dataLock) { store.familyChat.activeRoom() }
    val configured: Boolean get() = credentialsReady && (prefs.getLong("peerBotId", 0) > 0 || room?.members?.any { it.botId == selfBotId } == true)
    val careRole: String? get() = careRoleOf(room, selfBotId)
    private fun careRoleOf(activeRoom: FamilyChatRoom?, ownId: Long): String? = activeRoom?.let { active ->
        if (active.members.none { it.relationship in FamilyCareValidation.parentRelationships } ||
            active.members.none { it.relationship in FamilyCareValidation.childRelationships }) return@let null
        when (active.members.firstOrNull { it.botId == ownId }?.relationship) {
            "mother", "father" -> "guardian"
            "son", "daughter" -> "child"
            else -> null
        }
    }
    val careEnabled: Boolean get() = credentialsReady && careRole != null
    val effectiveRole: String get() = if (careEnabled) careRole!! else role
    val isChild: Boolean get() = effectiveRole == "child"
    val canShareLocation: Boolean get() = isChild && (careEnabled || paired)
    val careChildren: List<FamilyChatMember> get() = if (careEnabled) room!!.members.filter {
        it.relationship in FamilyCareValidation.childRelationships
    } else emptyList()
    val selectedCareChildId: Long? get() {
        if (!careEnabled) return null
        if (careRole == "child") return selfBotId
        val saved = prefs.getLong("careChild_${room!!.id}", 0)
        return careChildren.firstOrNull { it.botId == saved }?.botId ?: careChildren.firstOrNull()?.botId
    }
    fun selectCareChild(id: Long) = synchronized(dataLock) {
        require(careEnabled && careRole == "guardian" && careChildren.any { it.botId == id }) { "자녀를 확인해 주세요." }
        prefs.edit().putLong("careChild_${room!!.id}", id).commit()
    }
    private fun careEngine(active: FamilyChatRoom = checkNotNull(room), client: TelegramClient = clientFactory(vault.get()),
        checkActive: () -> Unit = {}, transport: FamilyTransport? = null) =
        FamilyCareEngine(client, store.familyCare, active, selfBotId, lock = dataLock, checkActive = checkActive,
            transport = transport, canSyncDeltas = { peer ->
                FamilyTransport.supportsCareDeltas(store, active.id, peer, System.currentTimeMillis())
            }, canSendLatestLocation = { peer ->
                FamilyTransport.supportsLatestLocation(store, active.id, peer, System.currentTimeMillis())
            })
    fun careSnapshot(childId: Long? = selectedCareChildId): FamilySnapshot? = synchronized(dataLock) {
        val active = room ?: return@synchronized null
        if (!careEnabled || childId == null) return@synchronized null
        careSnapshotForRoom(active, childId)
    }
    private fun careSnapshotForRoom(active: FamilyChatRoom, childId: Long): FamilySnapshot? {
        val state = store.familyCare.state(active.id, childId) ?: return null
        val member = active.members.firstOrNull { it.botId == childId }
        val snapshot = state.snapshot
        return snapshot.copy(events = snapshot.events.map {
            it.copy(roomId = active.id, senderId = if (it.sender == "child") childId else null,
                senderName = if (it.sender == "child") member?.displayName else "부모")
        }, rewards = snapshot.rewards.map { it.copy(version = state.rewardVersion(it.id)) })
    }
    /** Call from an IO dispatcher. All related screen fields come from one consistent local read. */
    fun readUiSnapshot(): RepositoryUiSnapshot = synchronized(dataLock) {
        // Do not fan out through public getters here: each credentialsReady read decrypts
        // Android Keystore data. One projection needs one credential read and one room read.
        val demo = demoMode
        val token = if (!demo && prefs.getString("transport", "") == "telegram_direct") vault.get() else ""
        val ready = token.isNotBlank()
        val active = store.familyChat.activeRoom()
        val ownId = selfBotId
        val privateRole = role
        val peerId = prefs.getLong("peerBotId", 0)
        val activeRole = careRoleOf(active, ownId)
        val useCare = ready && activeRole != null
        val children = if (useCare) active!!.members.filter {
            it.relationship in FamilyCareValidation.childRelationships
        } else emptyList()
        val selected = if (!useCare) null else if (activeRole == "child") ownId else {
            val saved = prefs.getLong("careChild_${active!!.id}", 0)
            children.firstOrNull { it.botId == saved }?.botId ?: children.firstOrNull()?.botId
        }
        val care = selected?.let { careSnapshotForRoom(active!!, it) }
        val engine = if (useCare) FamilyCareEngine(clientFactory(token), store.familyCare, active!!, ownId, lock = dataLock) else null
        RepositoryUiSnapshot(
            snapshot = if (useCare) care ?: FamilySnapshot.parse(TelegramLedger.emptyState()) else cached(),
            careSnapshot = care,
            role = if (useCare) activeRole!! else privateRole, privateRole = privateRole,
            configured = ready && (peerId > 0 || active?.members?.any { it.botId == ownId } == true),
            demoMode = demo, paired = ready && peerId > 0, room = active, selfBotId = ownId,
            botUsername = botUsername, peerBotUsername = peerBotUsername,
            careEnabled = useCare, careChildren = children, selectedChildBotId = selected,
            carePending = selected?.let { engine!!.hasPending(it) } ?: false,
            careStatus = selected?.let { engine!!.status(it) },
            sharingEnabled = sharingEnabled, trackingStatus = trackingStatus, connectionError = connectionError,
            latestLocation = if (useCare && selected != null) store.familyCare.latestLocationHead(active!!.id, selected)?.event?.copy(
                roomId = active.id, senderId = selected,
                senderName = active.members.firstOrNull { it.botId == selected }?.displayName) else null,
        )
    }
    fun carePending(childId: Long? = selectedCareChildId): Boolean = synchronized(dataLock) {
        careEnabled && childId != null && careEngine().hasPending(childId)
    }
    fun careStatus(childId: Long? = selectedCareChildId): String? = synchronized(dataLock) {
        if (!careEnabled || childId == null) return@synchronized null
        careEngine().status(childId)
    }
    suspend fun prepareCare() = withContext(Dispatchers.IO) { synchronized(dataLock) { prepareCareLocked() } }
    private fun prepareCareLocked() {
        if (demoMode || prefs.getString("transport", "") != "telegram_direct") return
        val token = vault.get().takeIf { it.isNotBlank() } ?: return
        val active = store.familyChat.activeRoom() ?: return
        val activeRole = careRoleOf(active, selfBotId) ?: return
        val children = active.members.filter { it.relationship in FamilyCareValidation.childRelationships }
        val engine = careEngine(active, clientFactory(token))
        store.transaction {
            if (activeRole == "child") {
                val existing = store.familyCare.stateMetadata(active.id, selfBotId)
                if (existing == null) {
                    val previousRoom = prefs.getString("lastCareRoom_$selfBotId", null)
                    val previous = previousRoom?.let { store.familyCare.state(it, selfBotId) }?.takeIf { it.authoritative }
                    if (previous != null) store.familyCare.copyEventDigests(previous.roomId, active.id, selfBotId)
                    engine.ensureAuthority(previous?.state ?: if (role == "child") store.cached() else null)
                } else check(existing.authoritative) { "자녀의 칭찬판 정보를 확인해 주세요." }
                if (prefs.getString("lastCareRoom_$selfBotId", null) != active.id)
                    prefs.edit().putString("lastCareRoom_$selfBotId", active.id).commit()
            }
            else {
                val oldChild = prefs.getLong("peerBotId", 0)
                if (role == "guardian" && children.any { it.botId == oldChild }) store.familyCare.importLegacyMovement(active.id, oldChild)
                val now = System.currentTimeMillis()
                children.forEach { child ->
                    val key = "careSync_${active.id}_${child.botId}"
                    val previous = prefs.getLong(key, 0)
                    val interval = if (store.familyCare.stateMetadata(active.id, child.botId) == null) 60_000 else 300_000
                    if (now < previous || now - previous >= interval) {
                        engine.requestSync(child.botId)
                        prefs.edit().putLong(key, now).commit()
                    }
                }
            }
        }
    }
    suspend fun sendCareAction(roomId: String, childId: Long, kind: String, payload: JSONObject,
        id: String = UUID.randomUUID().toString(), expectedRewardVersion: Long? = null) = withContext(Dispatchers.IO) {
        synchronized(dataLock) {
            check(careEnabled && room?.id == roomId) { "가족방이 변경되었어요. 다시 시도해 주세요." }
            prepareCareLocked()
            careEngine().enqueueCommand(childId, kind, payload, id, expectedRewardVersion)
        }
        scheduleOutbox()
    }
    var sharingEnabled: Boolean
        get() = prefs.getBoolean("sharingEnabled", false)
        set(value) { prefs.edit().putBoolean("sharingEnabled", value).commit() }
    val trackingStatus: String get() = prefs.getString("trackingStatus", "자동 위치 공유 꺼짐")!!
    fun noteTrackingStatus(message: String) {
        prefs.edit().putString("trackingStatus", message).apply()
        // Per-fix timestamps already belong to movement history, not diagnostics.
        if (!message.startsWith("자동 공유 중 · 마지막 위치 저장")) AppDiagnostics.record(app, "tracking.status", message)
    }

    suspend fun configure(role: String, botToken: String, peerUsername: String): FamilySnapshot = withContext(Dispatchers.IO) {
        require(role in listOf("child", "guardian")) { "사용자를 선택해 주세요." }
        val token = TelegramClient.normalizeToken(botToken)
        val peer = TelegramClient.normalizePeerUsername(peerUsername)
        networkMutex.withLock {
            val client = clientFactory(token)
            val me = client.getMe()
            require(me.optBoolean("is_bot") && me.optLong("id") > 0) { "이 휴대폰의 봇 토큰을 확인해 주세요." }
            val ownName = "@${me.getString("username")}"
            require(!ownName.equals(peer, true)) { "서로 다른 봇 두 개가 필요해요. 상대 휴대폰의 봇 사용자명을 넣어 주세요." }
            require(client.getWebhookInfo().optString("url").isBlank()) {
                "이 봇이 다른 서비스에 연결되어 있어요. BotFather에서 새 가족용 봇을 만들어 주세요."
            }
            val hello = TelegramProtocol.envelope("hello").put("role", role).toString()
            val chat = client.send(peer, hello).getJSONObject("chat")
            require(chat.optString("type") == "private" && chat.optLong("id") > 0 && chat.optLong("id") != me.getLong("id")) {
                "상대방 봇과 개인 대화를 열지 못했어요. 두 봇의 Bot-to-Bot 설정을 확인해 주세요."
            }
            require(chat.optString("username").isBlank() || "@${chat.optString("username")}".equals(peer, true)) {
                "상대방 봇 사용자명을 확인해 주세요."
            }
            synchronized(dataLock) {
                store.transaction { store.clear(); store.cache(TelegramLedger.emptyState()) }
                vault.put(token)
                prefs.edit().clear().putString("role", role).putString("botUsername", ownName)
                    .putLong("ownBotId", me.getLong("id"))
                    .putString("peerBotUsername", peer).putLong("peerBotId", chat.getLong("id"))
                    .putString("transport", "telegram_direct").putBoolean("demoMode", false)
                    .putBoolean("sharingEnabled", false).commit()
            }
            publishChanges()
            cached()
        }
    }
    suspend fun startDemo(role: String) = withContext(Dispatchers.IO) { networkMutex.withLock {
        require(role in listOf("child", "guardian"))
        synchronized(dataLock) {
            vault.clear(); store.transaction { store.clear() }
            prefs.edit().clear().putString("role", role).putBoolean("demoMode", true).commit()
        }
        WorkManager.getInstance(app).cancelUniqueWork("homeway_outbox")
        publishChanges()
    } }
    suspend fun reset() = withContext(Dispatchers.IO) { networkMutex.withLock {
        synchronized(dataLock) { vault.clear(); store.transaction { store.clear() }; prefs.edit().clear().commit() }
        WorkManager.getInstance(app).cancelUniqueWork("homeway_outbox")
        publishChanges()
    } }
    suspend fun sendEvent(kind: String, payload: JSONObject, id: String = UUID.randomUUID().toString()): Boolean {
        enqueueEventOnly(kind, payload, id)
        return false // Immediate durable pending event; worker/receiver sends asynchronously.
    }
    suspend fun enqueueEventOnly(kind: String, payload: JSONObject, id: String = UUID.randomUUID().toString()) = withContext(Dispatchers.IO) {
        synchronized(dataLock) {
            if (careEnabled && kind in FamilyCareValidation.telemetryKinds) {
                check(careRole == "child") { "자녀 휴대폰에서 위치를 공유할 수 있어요." }
                prepareCareLocked()
                careEngine().emitChildEvent(FamilyEvent(id, kind, JSONObject(payload.toString()), "child", Instant.now().toString(), "relayed"))
                return@synchronized
            }
            check(!careEnabled || kind == "chat") { "가족 칭찬판에서 자녀를 선택한 뒤 사용해 주세요." }
            check(paired) { "1:1 가족 연결이 필요해요." }
            val event = TelegramLedger.validate(FamilyEvent(id, kind, JSONObject(payload.toString()), role, Instant.now().toString(), "pending"))
            require(TelegramProtocol.event(event).length <= 4096) { "메시지가 너무 길어요. 내용을 나누어 보내 주세요." }
            store.transaction {
                val state = store.cached() ?: TelegramLedger.emptyState()
                if (!TelegramLedger.contains(state, id)) {
                    store.cache(TelegramLedger.apply(state, event)); store.enqueue(event)
                }
            }
        }
        scheduleOutbox(immediateChat = kind == "chat")
    }
    suspend fun flush() { synchronize(0) }
    suspend fun refresh(): FamilySnapshot { synchronize(0); return cached() }
    suspend fun refreshScheduled(): FamilySnapshot { synchronizeScheduled(0); return cached() }
    suspend fun flushOutgoing() { exchange(outgoingOnly = true) }
    suspend fun synchronizeScheduled(timeout: Int = 10) { exchange(timeout, scheduled = true) }
    fun receiveDelayMillis(): Long = TelegramChatReceiveCadence.delayMillis()

    /** Explicit sync; automatic callers share the scheduled receive deadline above. */
    suspend fun synchronize(timeout: Int = 0) { exchange(timeout) }
    private suspend fun exchange(timeout: Int = 0, scheduled: Boolean = false, outgoingOnly: Boolean = false) =
        withContext(Dispatchers.IO) { networkMutex.withLock {
        if (!configured) return@withLock
        // Decide after acquiring the sole network lock: waiting automatic callers must recheck
        // the shared deadline rather than each opening another poll when the lock becomes free.
        val poll = if (!outgoingOnly && (!scheduled || receiveDelayMillis() == 0L))
            TelegramChatReceiveCadence.beginPoll() else null
        synchronized(dataLock) { prepareCareLocked() }
        val client = clientFactory(vault.get())
        val peerId = prefs.getLong("peerBotId", 0)
        val peerRole = if (role == "child") "guardian" else "child"
        val coroutineContext = currentCoroutineContext()
        val activeRoom = room
        val transport = activeRoom?.let { FamilyTransport(client, store, it, selfBotId,
            lock = dataLock, checkActive = { coroutineContext.ensureActive() }) }
        val familyChat = activeRoom?.let { active -> FamilyChatExchange(client, store.familyChat, active, selfBotId,
            lock = dataLock, checkActive = { coroutineContext.ensureActive() },
            transport = transport,
            onReceived = { FamilyNotifications.received(app, roomEvent(it, active)) },
            onPeerFailure = { id, failure ->
                prefs.edit().putString("roomDeliveryError_${active.id}_$id", failure.message).commit()
                AppDiagnostics.record(app, "telegram.room_delivery", failure.message)
            }) }
        try {
            val exchange = TelegramExchange(client, store, peerId, peerRole, lock = dataLock,
                checkActive = { coroutineContext.ensureActive() },
                onReceived = { FamilyNotifications.received(app, it) }, familyChat = familyChat,
                onCommitted = ::publishChanges,
                onNewChatCommitted = {
                    TelegramChatReceiveCadence.onNewChatCommitted()
                    TelegramPollWakeup.signal()
                },
                onPollCompleted = { poll?.let(TelegramChatReceiveCadence::onPollCompleted) },
                onPollProgress = TelegramChatReceiveCadence::onPollProgress,
                onInvalidDocument = {
                    AppDiagnostics.record(app, "telegram.document_invalid", "압축 기록 수신을 완료하지 못해 원본 재전송을 기다립니다.")
                },
                pendingOutgoingWork = { synchronized(dataLock) {
                    store.hasQueuedLegacyEvents() || activeRoom?.let { active ->
                        store.familyChat.hasPendingDeliveries(active.id) || store.familyCare.hasPendingPackets(active.id)
                    } == true
                } },
                pollAllowed = { !scheduled || (poll != null && TelegramChatReceiveCadence.isCurrent(poll)) },
                familyCare = if (careEnabled) careEngine(client = client, checkActive = { coroutineContext.ensureActive() },
                    transport = transport) else null,
                transport = transport,
                onLegacyFailure = {
                    prefs.edit().putString("legacyDeliveryError", it.message).commit()
                    AppDiagnostics.record(app, "telegram.private_delivery", it.message)
                })
            val exchanged = if (poll == null) exchange.flushOutgoing() else exchange.synchronize(
                if (!scheduled) timeout else if (poll.catchingUp) 0 else if (poll.visible)
                    timeout.coerceIn(0, TelegramChatReceiveSchedule.FOREGROUND_POLL_SECONDS) else 0)
            if (exchanged) prefs.edit().apply {
                val failure = pendingDeliveryError()
                if (failure == null) remove("connectionError") else putString("connectionError", failure)
            }.apply()
        } catch (e: TelegramException) {
            prefs.edit().putString("connectionError", e.message).apply()
            AppDiagnostics.record(app, "telegram.connection", e.message)
            AppDiagnostics.record(app, "telegram.request", "operation=${e.operation.name} code=${e.errorCode} retryAfterSeconds=${e.retryAfterSeconds ?: 0}")
            throw e
        } catch (e: TelegramSyncException) {
            prefs.edit().putString("connectionError", e.message).apply()
            AppDiagnostics.record(app, "telegram.sync", e.message)
            throw e
        } finally {
            pacedOutboxDeadline = transport?.deferredUntilMillis ?: 0L
            publishChanges()
        }
    } }
    fun cached(): FamilySnapshot = synchronized(dataLock) {
        val snapshot = FamilySnapshot.parse(store.cached() ?: TelegramLedger.emptyState())
        val events = snapshot.events.associateBy { it.id }.toMutableMap()
        store.localEvents().forEach { events[it.id] = it }
        snapshot.copy(events = events.values.sortedBy { it.measuredAt })
    }
    suspend fun movementHistory(day: String? = null, before: MovementHistoryCursor? = null,
        careRoomId: String? = if (careEnabled) room?.id else null,
        childId: Long? = selectedCareChildId): MovementHistoryPage = withContext(Dispatchers.IO) {
        synchronized(dataLock) {
            if (careRoomId != null && childId != null) store.familyCare.movementHistory(careRoomId, childId, day, before)
            else store.movementHistory(day, before)
        }
    }
    suspend fun privateChatHistory(before: MovementHistoryCursor? = null): PrivateChatHistoryPage = withContext(Dispatchers.IO) {
        synchronized(dataLock) { store.privateChatHistory(before) }
    }
    suspend fun roomHistory(before: FamilyChatCursor? = null): FamilyChatPage = withContext(Dispatchers.IO) {
        synchronized(dataLock) { room?.let { store.familyChat.chatHistory(it.id, before) } ?: FamilyChatPage(emptyList(), null) }
    }
    fun roomEvent(message: FamilyChatMessage, active: FamilyChatRoom): FamilyEvent {
        val member = active.members.first { it.botId == message.senderId }
        return FamilyEvent(message.id, "chat", JSONObject().put("text", message.text),
            if (member.relationship in setOf("mother", "father")) "guardian" else "child", message.createdAt,
            if (message.senderId != selfBotId || message.deliveredCount == message.recipientCount) "relayed" else "pending",
            senderId = message.senderId, senderName = member.displayName, roomId = active.id,
            deliveredTo = message.deliveredCount, recipientCount = message.recipientCount)
    }
    suspend fun sendRoomChat(text: String) = withContext(Dispatchers.IO) {
        synchronized(dataLock) {
            check(configured) { "가족방에 먼저 참여해 주세요." }
            val active = checkNotNull(room) { "가족방에 먼저 참여해 주세요." }
            FamilyChatExchange(clientFactory(vault.get()), store.familyChat, active, selfBotId, lock = dataLock)
                .enqueue(FamilyChatMessage(UUID.randomUUID().toString(), active.id, selfBotId, text, Instant.now().toString()))
        }
        scheduleOutbox(immediateChat = true)
    }
    private fun roomClient(rawToken: String): Pair<TelegramClient, String> {
        check(!demoMode) { "체험을 끝내고 가족방을 연결해 주세요." }
        val token = TelegramClient.normalizeToken(rawToken.trim().ifBlank { vault.get() })
        return clientFactory(token) to token
    }
    private fun verifyRoomIdentity(client: TelegramClient): JSONObject {
        val me = client.getMe()
        require(me.optBoolean("is_bot") && me.optLong("id") > 0) { "이 휴대폰 봇 토큰을 확인해 주세요." }
        // Leaving a room does not discard this bot's offset or queued messages. Only an explicit
        // account reset can replace the pinned identity; older installs recover it from their token.
        val pinnedId = selfBotId.takeIf { it > 0 } ?: vault.get().substringBefore(':').toLongOrNull()
        require(pinnedId == null || pinnedId == me.getLong("id")) { "기존 연결과 같은 봇 토큰을 사용해 주세요. 다른 봇은 기존 연결을 초기화한 뒤 사용할 수 있어요." }
        require(prefs.getLong("peerBotId", 0) == 0L || pinnedId != null) { "기존 봇 정보를 읽지 못했어요. 잠시 후 다시 시도해 주세요." }
        require(client.getWebhookInfo().optString("url").isBlank()) { "다른 서비스에서 사용하지 않는 가족용 봇이 필요해요." }
        return me
    }
    suspend fun createFamilyRoom(rawToken: String, title: String, selfName: String, relationship: String,
        drafts: List<FamilyChatMemberDraft>) = withContext(Dispatchers.IO) { networkMutex.withLock {
        check(room == null) { "현재 가족방에서 나간 뒤 새 가족방을 만들어 주세요." }
        // Validate every field before opening conversations with the explicitly entered family bots.
        FamilyChatRoom.create(title, listOf(FamilyChatMember(1, "@self_family_bot", selfName, relationship)) +
            drafts.mapIndexed { index, draft -> FamilyChatMember(index + 2L, draft.username, draft.displayName, draft.relationship) })
        val (client, token) = roomClient(rawToken)
        val me = verifyRoomIdentity(client)
        val own = FamilyChatMember(me.getLong("id"), "@${me.getString("username")}", selfName, relationship)
        require(drafts.none { TelegramClient.normalizePeerUsername(it.username).equals(own.username, true) }) { "다른 가족의 봇 사용자명을 입력해 주세요." }
        val members = drafts.map { draft ->
            val username = TelegramClient.normalizePeerUsername(draft.username)
            val chat = client.send(username, FamilyChatProtocol.envelope("hello").toString()).getJSONObject("chat")
            require(chat.optString("type") == "private" && chat.optLong("id") > 0 && chat.optLong("id") != own.botId &&
                (chat.optString("username").isBlank() || "@${chat.optString("username")}".equals(username, true))) {
                "가족 봇을 연결하지 못했어요. 각 봇의 Bot-to-Bot 설정을 켜 주세요."
            }
            FamilyChatMember(chat.getLong("id"), username, draft.displayName, draft.relationship)
        }
        saveRoom(FamilyChatRoom.create(title, listOf(own) + members), own, token)
    } }
    suspend fun joinFamilyRoom(rawToken: String, code: String) = withContext(Dispatchers.IO) { networkMutex.withLock {
        val active = FamilyChatRoom.fromCode(code)
        check(room == null || room?.id == active.id) { "현재 가족방에서 나간 뒤 다른 방에 참여해 주세요." }
        synchronized(dataLock) { store.familyChat.validateRoom(active) }
        val (client, token) = roomClient(rawToken)
        val me = verifyRoomIdentity(client)
        val own = active.members.firstOrNull { it.botId == me.getLong("id") }
        require(own != null && own.username.equals("@${me.getString("username")}", true)) { "이 휴대폰 봇이 가족 명단에 없어요. 가족방을 만든 사람에게 확인해 주세요." }
        // Open each private bot conversation by username, including sibling-to-sibling peers.
        // Only a content-free hello may precede verification of the roster's pinned bot ID.
        val hello = FamilyChatProtocol.envelope("hello").toString()
        for (member in active.members.filter { it.botId != own.botId }) {
            currentCoroutineContext().ensureActive()
            val chat = client.send(member.username, hello).optJSONObject("chat")
            val id = chat?.opt("id")
            if (chat == null || chat.optString("type") != "private" || id !is Number || id.toString() != member.botId.toString() ||
                (chat.optString("username").isNotBlank() && !"@${chat.optString("username")}".equals(member.username, true))) {
                throw TelegramException(400, reason = TelegramFailureReason.PEER_IDENTITY_MISMATCH)
            }
        }
        // A failed or cancelled handshake must leave the saved connection and room untouched.
        currentCoroutineContext().ensureActive()
        saveRoom(active, own, token)
    } }
    private fun saveRoom(active: FamilyChatRoom, own: FamilyChatMember, token: String) = synchronized(dataLock) {
        val existingPair = prefs.getLong("peerBotId", 0) > 0
        vault.put(token)
        // Make the identity durable before exposing its room to the receiver after a process restart.
        prefs.edit().putString("transport", "telegram_direct").putBoolean("demoMode", false)
            .putString("botUsername", own.username).putLong("ownBotId", own.botId)
            .commit()
        store.transaction { store.familyChat.setActiveRoom(active) }
        prefs.edit().apply { if (!existingPair) putString("role", if (own.relationship in setOf("mother", "father")) "guardian" else "child") }
            .remove("connectionError").commit()
        publishChanges()
    }
    suspend fun leaveFamilyRoom() = withContext(Dispatchers.IO) { networkMutex.withLock {
        synchronized(dataLock) {
            store.transaction { store.familyChat.setActiveRoom(null) }
            prefs.edit().remove("connectionError").commit()
            publishChanges()
        }
    } }
    fun hasPending(): Boolean = synchronized(dataLock) {
        store.hasPendingLegacy() || room?.let {
            store.familyChat.hasPending(it.id) ||
                store.familyCare.hasPendingPackets(it.id) || store.familyCare.pendingPeers(it.id).isNotEmpty()
        } == true || (careEnabled && careChildren.any { careEngine().hasPending(it.botId) })
    }
    fun synchronizationRetryDelayMillis(): Long = synchronized(dataLock) {
        (store.meta("retryAfter") - System.currentTimeMillis()).coerceAtLeast(0)
    }
    /** Only an actual rate-deferred send supplies this deadline; idle peers cannot create a busy loop. */
    fun outgoingRecheckDelayMillis(): Long? = pacedOutboxDeadline.takeIf { it > 0 }?.let {
        (it - System.currentTimeMillis()).coerceIn(1, FamilyTransport.PEER_INTERVAL_MILLIS)
    }
    private fun pendingDeliveryError(): String? = synchronized(dataLock) {
        val failures = mutableListOf<String>()
        if (store.hasPendingLegacy()) {
            prefs.getString("legacyDeliveryError", null)?.let { failures.add("기존 1:1 전달 대기: $it") }
        } else prefs.edit().remove("legacyDeliveryError").apply()
        room?.let { active ->
            val pendingPeers = store.familyChat.pendingPeers(active.id)
            active.members.forEach { member ->
                val key = "roomDeliveryError_${active.id}_${member.botId}"
                if (member.botId in pendingPeers) prefs.getString(key, null)?.let { failures.add("${member.displayName}에게 전달 대기: $it") }
                else prefs.edit().remove(key).apply()
            }
        }
        failures.firstOrNull()
    }
    private fun scheduleOutbox(immediateChat: Boolean = false) {
        publishChanges()
        TelegramPollWakeup.signal()
        // One process-wide conflated consumer makes a user send immediate without collecting
        // one network-mutex waiter for every enqueue. WorkManager retains restart durability.
        if (immediateChat) immediateOutbox.trySend(this)
        // A successor is needed even if the running worker just observed an empty outbox.
        // KEEP could discard this enqueue before that worker reports success and strands it.
        WorkManager.getInstance(app).enqueueUniqueWork("homeway_outbox", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<OutboxWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    companion object {
        private val networkMutex = Mutex()
        private val dataLock = Any()
        private val changeRevision = MutableStateFlow(0L)
        @Volatile private var pacedOutboxDeadline = 0L
        private val immediateOutbox = Channel<AppRepository>(Channel.CONFLATED)
        private val immediateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
            scope.launch {
                for (repository in immediateOutbox) {
                    try { repository.flushOutgoing() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Persisted backoff and the durable worker own retries. */ }
                }
            }
        }
    }
}
