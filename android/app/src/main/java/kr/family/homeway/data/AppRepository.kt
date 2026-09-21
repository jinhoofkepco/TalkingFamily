package kr.family.homeway.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kr.family.homeway.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.UUID

class ApiException(val status: Int, message: String) : Exception(message)

class AppRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("homeway_settings", Context.MODE_PRIVATE)
    private val vault = TokenVault(app)
    private val store = LocalStore.get(app)
    val role: String get() = prefs.getString("role", "child")!!
    val serverUrl: String get() = prefs.getString("serverUrl", "")!!
    val demoMode: Boolean get() = prefs.getBoolean("demoMode", false)
    val configured: Boolean get() = !demoMode && serverUrl.isNotBlank() && vault.get().isNotBlank()
    val isChild: Boolean get() = role == "child"
    var sharingEnabled: Boolean
        get() = prefs.getBoolean("sharingEnabled", false)
        set(value) { prefs.edit().putBoolean("sharingEnabled", value).commit() }
    val trackingStatus: String get() = prefs.getString("trackingStatus", "자동 위치 공유 꺼짐")!!
    fun noteTrackingStatus(message: String) { prefs.edit().putString("trackingStatus", message).apply() }

    suspend fun configure(role: String, rawUrl: String, token: String): FamilySnapshot = withContext(Dispatchers.IO) {
        require(role in listOf("child", "guardian")) { "사용자를 선택해 주세요." }
        val url = validateServerUrl(rawUrl)
        require(token.trim().length >= 24) { "서버에서 발급한 기기 연결 키를 입력해 주세요. (24자 이상)" }
        val state = request(url, token.trim(), "GET", "/v1/state")
        require(state.optString("role") == role) { "선택한 사용자와 연결 키의 역할이 다릅니다." }
        if (state.optString("transport") != "telegram") throw IllegalStateException("서버에 텔레그램 봇을 먼저 설정해 주세요.")
        mutex.withLock {
            store.clear()
            vault.put(token.trim())
            prefs.edit().putString("role", role).putString("serverUrl", url).putBoolean("demoMode", false)
                .putBoolean("sharingEnabled", false).putString("trackingStatus", "자동 위치 공유 꺼짐").commit()
            store.cache(state)
        }
        FamilySnapshot.parse(state)
    }

    suspend fun startDemo(role: String) = withContext(Dispatchers.IO) { mutex.withLock {
        require(role in listOf("child", "guardian"))
        vault.clear(); store.clear()
        prefs.edit().clear().putString("role", role).putBoolean("demoMode", true).commit()
    } }
    suspend fun reset() = withContext(Dispatchers.IO) { mutex.withLock {
        vault.clear(); store.clear(); prefs.edit().clear().commit()
        WorkManager.getInstance(app).cancelUniqueWork("homeway_outbox")
    } }

    suspend fun sendEvent(kind: String, payload: JSONObject, id: String = UUID.randomUUID().toString()): Boolean = withContext(Dispatchers.IO) {
        enqueueEventOnly(kind, payload, id)
        flush()
        store.localEvents().none { it.id == id }
    }

    /** Sensor events must reach SQLite immediately instead of waiting behind network retries. */
    suspend fun enqueueEventOnly(kind: String, payload: JSONObject, id: String = UUID.randomUUID().toString()) = withContext(Dispatchers.IO) {
        check(configured) { "가족 연결을 먼저 설정해 주세요." }
        store.enqueue(FamilyEvent(id, kind, JSONObject(payload.toString()), role, Instant.now().toString(), "queued"))
        scheduleOutbox()
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        if (!configured) return@withContext
        mutex.withLock {
            for (event in store.pending()) {
                try {
                    val body = JSONObject().put("id", event.id).put("kind", event.kind).put("payload", event.payload)
                    val response = request(serverUrl, vault.get(), "POST", "/v1/events", body)
                    // Cache accepted events until the next state refresh so an acknowledged post cannot disappear.
                    val cached = store.cached() ?: JSONObject()
                    val array = cached.optJSONArray("events") ?: org.json.JSONArray()
                    response.optJSONObject("event")?.let { accepted ->
                        if ((0 until array.length()).none { array.getJSONObject(it).optString("id") == event.id }) array.put(accepted)
                    }
                    cached.put("events", array); store.cache(cached)
                    store.remove(event.id)
                } catch (e: ApiException) {
                    if (e.status in listOf(400, 403, 409, 422)) store.fail(event.id, e.message ?: "요청을 처리하지 못했습니다.")
                    else break
                } catch (_: Exception) { break }
            }
        }
    }

    suspend fun refresh(): FamilySnapshot = withContext(Dispatchers.IO) {
        check(configured) { "가족 연결을 먼저 설정해 주세요." }
        flush()
        mutex.withLock {
            check(configured) { "가족 연결을 먼저 설정해 주세요." }
            val state = request(serverUrl, vault.get(), "GET", "/v1/state")
            store.cache(state)
            merge(FamilySnapshot.parse(state))
        }
    }
    fun cached(): FamilySnapshot = merge(store.cached()?.let(FamilySnapshot::parse) ?: FamilySnapshot())
    private fun merge(snapshot: FamilySnapshot): FamilySnapshot {
        val byId = snapshot.events.associateBy { it.id }.toMutableMap()
        store.localEvents().forEach { if (it.id !in byId) byId[it.id] = it }
        return snapshot.copy(events = byId.values.sortedBy { it.measuredAt })
    }
    fun hasPending(): Boolean = store.pending().isNotEmpty()
    suspend fun registerPush(token: String) = withContext(Dispatchers.IO) {
        if(configured) request(serverUrl, vault.get(), "POST", "/v1/push-token", JSONObject().put("token", token))
        Unit
    }
    private fun scheduleOutbox() {
        WorkManager.getInstance(app).enqueueUniqueWork("homeway_outbox", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<OutboxWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    companion object {
        private val mutex = Mutex()
        fun validateServerUrl(raw: String): String {
            val url = raw.trim().trimEnd('/')
            val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("올바른 서버 주소를 입력해 주세요.") }
            require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "서버 주소는 https://서버주소 형태로 입력해 주세요." }
            val localDebug = BuildConfig.DEBUG && uri.scheme == "http" && uri.host in listOf("10.0.2.2", "localhost", "127.0.0.1")
            require(uri.scheme == "https" || localDebug) { "위치와 대화 보호를 위해 HTTPS 서버 주소가 필요합니다." }
            return url
        }
        private fun request(base: String, token: String, method: String, path: String, body: JSONObject? = null): JSONObject {
            val connection = URL(base + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 12000; connection.readTimeout = 15000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if(status in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val result = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                if(status !in 200..299) {
                    val message = result.optJSONObject("error")?.optString("message")
                        ?: result.optString("message", "서버 연결을 확인해 주세요. ($status)")
                    throw ApiException(status, message.ifBlank { "요청을 처리하지 못했습니다. ($status)" })
                }
                return result
            } finally { connection.disconnect() }
        }
    }
}
