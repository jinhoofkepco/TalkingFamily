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
class AppRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("homeway_settings", Context.MODE_PRIVATE)
    private val vault = TokenVault(app)
    private val store = LocalStore.get(app)
    val role: String get() = prefs.getString("role", "child")!!
    val botUsername: String get() = prefs.getString("botUsername", "")!!
    val peerBotUsername: String get() = prefs.getString("peerBotUsername", "")!!
    val connectionError: String? get() = prefs.getString("connectionError", null)
    val demoMode: Boolean get() = prefs.getBoolean("demoMode", false)
    val configured: Boolean get() = !demoMode && prefs.getString("transport", "") == "telegram_direct" &&
        prefs.getLong("peerBotId", 0) > 0 && vault.get().isNotBlank()
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
            val client = TelegramClient(token)
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
            check(configured) { "텔레그램 가족 연결을 먼저 설정해 주세요." }
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
        val client = TelegramClient(vault.get())
        val peerId = prefs.getLong("peerBotId", 0)
        val peerRole = if (isChild) "guardian" else "child"
        val coroutineContext = currentCoroutineContext()
        try {
            val exchanged = TelegramExchange(client, store, peerId, peerRole, lock = dataLock,
                checkActive = { coroutineContext.ensureActive() },
                onReceived = { FamilyNotifications.received(app, it) }).synchronize(timeout)
            if (exchanged) prefs.edit().remove("connectionError").apply()
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
    fun hasPending(): Boolean = synchronized(dataLock) { store.pending().isNotEmpty() || store.receipts().isNotEmpty() }
    private fun scheduleOutbox() {
        WorkManager.getInstance(app).enqueueUniqueWork("homeway_outbox", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OutboxWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    companion object {
        private val networkMutex = Mutex()
        private val dataLock = Any()
    }
}
