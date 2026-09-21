package kr.family.homeway.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val isChild: Boolean get() = role == "child"
    var sharingEnabled: Boolean
        get() = prefs.getBoolean("sharingEnabled", false)
        set(value) { prefs.edit().putBoolean("sharingEnabled", value).commit() }
    val trackingStatus: String get() = prefs.getString("trackingStatus", "자동 위치 공유 꺼짐")!!
    fun noteTrackingStatus(message: String) { prefs.edit().putString("trackingStatus", message).apply() }

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
    } }
    suspend fun reset() = withContext(Dispatchers.IO) { networkMutex.withLock {
        synchronized(dataLock) { vault.clear(); store.transaction { store.clear() }; prefs.edit().clear().commit() }
        WorkManager.getInstance(app).cancelUniqueWork("homeway_outbox")
    } }
    suspend fun sendEvent(kind: String, payload: JSONObject, id: String = UUID.randomUUID().toString()): Boolean {
        enqueueEventOnly(kind, payload, id)
        return false // Immediate durable pending event; worker/receiver sends asynchronously.
    }
    suspend fun enqueueEventOnly(kind: String, payload: JSONObject, id: String = UUID.randomUUID().toString()) = withContext(Dispatchers.IO) {
        synchronized(dataLock) {
            check(paired) { "위치와 칭찬판은 기존 1:1 가족 연결이 필요해요." }
            val event = TelegramLedger.validate(FamilyEvent(id, kind, JSONObject(payload.toString()), role, Instant.now().toString(), "pending"))
            require(TelegramProtocol.event(event).length <= 4096) { "메시지가 너무 길어요. 내용을 나누어 보내 주세요." }
            store.transaction {
                val state = store.cached() ?: TelegramLedger.emptyState()
                if (!TelegramLedger.contains(state, id)) {
                    store.cache(TelegramLedger.apply(state, event)); store.enqueue(event)
                }
            }
        }
        scheduleOutbox()
    }
    suspend fun flush() { synchronize(0) }
    suspend fun refresh(): FamilySnapshot { synchronize(0); return cached() }

    /** Durable FIFO: next event is sent only after the peer acknowledges the current one. */
    suspend fun synchronize(timeout: Int = 0) = withContext(Dispatchers.IO) { networkMutex.withLock {
        if (!configured) return@withLock
        val client = clientFactory(vault.get())
        val peerId = prefs.getLong("peerBotId", 0)
        val peerRole = if (isChild) "guardian" else "child"
        val coroutineContext = currentCoroutineContext()
        val familyChat = room?.let { active -> FamilyChatExchange(client, store.familyChat, active, selfBotId,
            lock = dataLock, checkActive = { coroutineContext.ensureActive() },
            onReceived = { FamilyNotifications.received(app, roomEvent(it, active)) },
            onPeerFailure = { id, failure -> prefs.edit().putString("roomDeliveryError_${active.id}_$id", failure.message).commit() }) }
        try {
            val exchanged = TelegramExchange(client, store, peerId, peerRole, lock = dataLock,
                checkActive = { coroutineContext.ensureActive() },
                onReceived = { FamilyNotifications.received(app, it) }, familyChat = familyChat,
                onLegacyFailure = { prefs.edit().putString("legacyDeliveryError", it.message).commit() }).synchronize(timeout)
            if (exchanged) prefs.edit().apply {
                val failure = pendingDeliveryError()
                if (failure == null) remove("connectionError") else putString("connectionError", failure)
            }.apply()
        } catch (e: TelegramException) {
            prefs.edit().putString("connectionError", e.message).apply()
            throw e
        } catch (e: TelegramSyncException) {
            prefs.edit().putString("connectionError", e.message).apply()
            throw e
        }
    } }
    fun cached(): FamilySnapshot = synchronized(dataLock) {
        val snapshot = FamilySnapshot.parse(store.cached() ?: TelegramLedger.emptyState())
        val events = snapshot.events.associateBy { it.id }.toMutableMap()
        store.localEvents().forEach { events[it.id] = it }
        snapshot.copy(events = events.values.sortedBy { it.measuredAt })
    }
    suspend fun movementHistory(day: String? = null, before: MovementHistoryCursor? = null): MovementHistoryPage = withContext(Dispatchers.IO) {
        synchronized(dataLock) { store.movementHistory(day, before) }
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
        scheduleOutbox()
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
        val (client, token) = roomClient(rawToken)
        val me = verifyRoomIdentity(client)
        val own = active.members.firstOrNull { it.botId == me.getLong("id") }
        require(own != null && own.username.equals("@${me.getString("username")}", true)) { "이 휴대폰 봇이 가족 명단에 없어요. 가족방을 만든 사람에게 확인해 주세요." }
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
    }
    suspend fun leaveFamilyRoom() = withContext(Dispatchers.IO) { networkMutex.withLock {
        synchronized(dataLock) {
            store.transaction { store.familyChat.setActiveRoom(null) }
            prefs.edit().remove("connectionError").commit()
        }
    } }
    fun hasPending(): Boolean = synchronized(dataLock) {
        store.pending().isNotEmpty() || store.receipts().isNotEmpty() || room?.let {
            store.familyChat.pendingChatDeliveries(it.id).isNotEmpty() || store.familyChat.chatReceipts(it.id).isNotEmpty()
        } == true
    }
    private fun pendingDeliveryError(): String? = synchronized(dataLock) {
        val failures = mutableListOf<String>()
        if (store.pending().isNotEmpty() || store.receipts().isNotEmpty()) {
            prefs.getString("legacyDeliveryError", null)?.let { failures.add("기존 1:1 전달 대기: $it") }
        } else prefs.edit().remove("legacyDeliveryError").apply()
        room?.let { active ->
            val pendingPeers = store.familyChat.pendingChatDeliveries(active.id).map { it.peerId }.toSet() +
                store.familyChat.chatReceipts(active.id).map { it.peerId }
            active.members.forEach { member ->
                val key = "roomDeliveryError_${active.id}_${member.botId}"
                if (member.botId in pendingPeers) prefs.getString(key, null)?.let { failures.add("${member.displayName}에게 전달 대기: $it") }
                else prefs.edit().remove(key).apply()
            }
        }
        failures.firstOrNull()
    }
    private fun scheduleOutbox() {
        WorkManager.getInstance(app).enqueueUniqueWork("homeway_outbox", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OutboxWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    companion object {
        private val networkMutex = Mutex()
        private val dataLock = Any()
    }
}
