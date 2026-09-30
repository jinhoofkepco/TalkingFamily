package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException

/** Exercises real file preparation, hydration and the exchange commit against a fake Telegram API. */
class TelegramDocumentExchangeTest {
    @Test fun `document downloads outside data lock then originals receipts and offset commit together`() {
        val h = Harness()
        val messages = h.messages()
        h.document(messages.map(FamilyChatProtocol::message))
        h.http.onFileRequest = {
            assertFalse(Thread.holdsLock(h.lock))
            assertEquals(0, h.store.transactionDepth)
            assertEquals(0L, h.store.meta("offset"))
            assertTrue(h.store.chatHistory(h.room.id).messages.isEmpty())
        }
        h.onCommit = {
            assertEquals(2L, h.store.meta("offset"))
            assertEquals(messages.map { it.id }.toSet(), h.store.chatHistory(h.room.id).messages.map { it.id }.toSet())
            assertEquals(messages.size, h.store.chatReceipts(h.room.id).size)
        }
        assertTrue(h.exchange().synchronize())
        assertEquals(1, h.http.fileInfoCalls)
        assertEquals(1, h.http.downloadCalls)
        assertEquals(messages.map { it.id }, h.notifications)
        assertEquals(1, h.progress.single().updateCount)
    }

    @Test fun `failed document commit rolls back messages receipts offset and file proof then replay notifies once`() {
        val h = Harness()
        val messages = h.messages()
        h.document(messages.map(FamilyChatProtocol::message))
        val before = h.store.base.saved()
        h.store.base.failCommit = true
        assertThrows(IOException::class.java) { h.exchange().synchronize() }
        assertEquals(before, h.store.base.saved())
        assertTrue(h.notifications.isEmpty())
        assertEquals(0L, h.store.meta(TelegramExchange.BLOCKED_DOCUMENT_FAILURES))
        assertTrue(h.progress.isEmpty())
        assertTrue(h.exchange().synchronize())
        assertEquals(2L, h.store.meta("offset"))
        assertEquals(messages.map { it.id }, h.notifications)
        assertEquals(2, h.http.downloadCalls)
    }

    @Test fun `temporary download failure retains cursor and every original ACK until retry succeeds`() {
        val h = Harness()
        val messages = h.messages()
        h.document(messages.map(FamilyChatProtocol::message))
        h.http.downloadFailure = TelegramException(503, operation = TelegramOperation.FILE_DOWNLOAD)
        assertThrows(TelegramException::class.java) { h.exchange().synchronize() }
        assertEquals(0L, h.store.meta("offset"))
        assertTrue(h.store.chatReceipts(h.room.id).isEmpty())
        assertTrue(h.store.chatHistory(h.room.id).messages.isEmpty())
        assertTrue(h.notifications.isEmpty())
        assertEquals(1L, h.store.meta(TelegramExchange.BLOCKED_DOCUMENT_FAILURES))
        val calls = h.http.fileInfoCalls
        assertFalse(h.exchange().synchronize())
        assertEquals(calls, h.http.fileInfoCalls)
        h.time += 15_001
        assertTrue(h.exchange().synchronize())
        assertEquals(messages.size, h.notifications.size)
        assertEquals(0L, h.store.meta(TelegramExchange.BLOCKED_DOCUMENT_FAILURES))
    }

    @Test fun `getFile retry after is global and does not acknowledge or skip a failed history document`() {
        val h = Harness()
        h.document(h.messages().map(FamilyChatProtocol::message))
        h.http.getFileFailureCode = 429
        val failure = assertThrows(TelegramException::class.java) { h.exchange().synchronize() }
        assertEquals(70, failure.retryAfterSeconds)
        assertEquals(h.time + 70_000, h.store.meta("retryAfter"))
        assertEquals(0L, h.store.meta("offset"))
        assertTrue(h.store.chatReceipts(h.room.id).isEmpty())
        assertEquals(0, h.http.downloadCalls)
        val polls = h.http.pollCalls
        h.time += 69_000
        assertFalse(h.exchange().synchronize())
        assertEquals(polls, h.http.pollCalls)
        h.time += 1_001
        assertTrue(h.exchange().synchronize())
    }

    @Test fun `permanent missing file advances without ACK so later normal messages remain receivable`() {
        val h = Harness()
        val failed = h.messages()
        h.document(failed.map(FamilyChatProtocol::message))
        val later = h.messages(1).single()
        h.http.updates += update(2, 101, FamilyChatProtocol.message(later))
        h.http.getFileFailureCode = 404
        assertTrue(h.exchange().synchronize())
        assertEquals(3L, h.store.meta("offset"))
        assertEquals(listOf(later.id), h.notifications)
        assertEquals(setOf(later.id), h.store.chatHistory(h.room.id).messages.map { it.id }.toSet())
        assertEquals(1, h.invalidDocuments)
        assertTrue(h.store.chatReceipts(h.room.id).none { receipt -> failed.any { it.id == receipt.messageId } })
    }

    @Test fun `third transient file failure survives reopen then skips without ACK and plaintext originals deliver once`() {
        val h = Harness()
        val messages = h.messages()
        h.document(messages.map(FamilyChatProtocol::message))
        h.http.getFileFailureCode = 503
        assertThrows(TelegramException::class.java) { h.exchange().synchronize() }
        assertEquals(1L, h.store.meta(TelegramExchange.BLOCKED_DOCUMENT_FAILURES))
        h.time += 15_001
        h.http.downloadFailure = TelegramException(0, operation = TelegramOperation.FILE_DOWNLOAD)
        assertThrows(TelegramException::class.java) { h.exchange().synchronize() }
        assertEquals(2L, h.store.meta(TelegramExchange.BLOCKED_DOCUMENT_FAILURES))
        assertEquals(0L, h.store.meta("offset"))
        messages.forEachIndexed { index, message -> h.http.updates += update(index + 2L, 101, FamilyChatProtocol.message(message)) }
        h.http.updates += update(messages.size + 2L, 101, FamilyChatProtocol.message(messages.first()))
        h.onCommit = {
            if (h.store.meta("offset") == 2L) {
                assertTrue(h.store.chatReceipts(h.room.id).isEmpty())
                assertTrue(h.store.chatHistory(h.room.id).messages.isEmpty())
            }
        }
        h.time += 15_001
        h.http.downloadFailure = TelegramException(503, operation = TelegramOperation.FILE_DOWNLOAD)
        assertTrue(h.exchange().synchronize())
        assertEquals(messages.size + 3L, h.store.meta("offset"))
        assertEquals(messages.map { it.id }, h.notifications)
        assertEquals(messages.size, h.store.chatHistory(h.room.id).messages.size)
        assertEquals(1, h.invalidDocuments)
        assertEquals(0L, h.store.meta(TelegramExchange.BLOCKED_DOCUMENT_UPDATE))
        assertEquals(0L, h.store.meta(TelegramExchange.BLOCKED_DOCUMENT_FAILURES))
    }

    @Test fun `repeated rate limits never spend a prior transient file retry or skip its cursor`() {
        val h = Harness()
        h.document(h.messages().map(FamilyChatProtocol::message))
        h.http.getFileFailureCode = 503
        assertThrows(TelegramException::class.java) { h.exchange().synchronize() }
        h.time += 15_001
        repeat(4) {
            h.http.getFileFailureCode = 429
            val error = assertThrows(TelegramException::class.java) { h.exchange().synchronize() }
            assertEquals(70, error.retryAfterSeconds)
            assertEquals(1L, h.store.meta(TelegramExchange.BLOCKED_DOCUMENT_FAILURES))
            assertEquals(0L, h.store.meta("offset"))
            assertTrue(h.store.chatReceipts(h.room.id).isEmpty())
            h.time += 70_001
        }
        assertEquals(0, h.invalidDocuments)
        assertEquals(0, h.http.downloadCalls)
    }

    @Test fun `moving past a permanently missing file resets the single slot before another blocked document`() {
        val h = Harness()
        h.document(h.messages().map(FamilyChatProtocol::message))
        h.document(h.messages().map(FamilyChatProtocol::message))
        h.http.getFileFailureCode = 503
        assertThrows(TelegramException::class.java) { h.exchange().synchronize() }
        h.time += 15_001
        h.http.getFileFailureCode = 404
        h.http.downloadFailure = TelegramException(0, operation = TelegramOperation.FILE_DOWNLOAD)
        assertThrows(TelegramException::class.java) { h.exchange().synchronize() }
        assertEquals(2L, h.store.meta("offset"))
        assertEquals(2L, h.store.meta(TelegramExchange.BLOCKED_DOCUMENT_UPDATE))
        assertEquals(1L, h.store.meta(TelegramExchange.BLOCKED_DOCUMENT_FAILURES))
        assertEquals(1, h.invalidDocuments)
    }

    @Test fun `auth conflict cancellation and storage errors never consume a file retry slot`() {
        for (code in listOf(401, 409)) {
            val h = Harness()
            h.document(h.messages().map(FamilyChatProtocol::message))
            repeat(4) {
                h.http.getFileFailureCode = code
                assertThrows(TelegramException::class.java) { h.exchange().synchronize() }
                assertEquals(0L, h.store.meta(TelegramExchange.BLOCKED_DOCUMENT_FAILURES))
                assertEquals(0L, h.store.meta("offset"))
                h.time += 15_001
            }
        }
        val cancelled = Harness()
        cancelled.document(cancelled.messages().map(FamilyChatProtocol::message))
        cancelled.http.onFileRequest = { throw CancellationException() }
        assertThrows(CancellationException::class.java) { cancelled.exchange().synchronize() }
        assertEquals(0L, cancelled.store.meta(TelegramExchange.BLOCKED_DOCUMENT_FAILURES))
        assertEquals(0L, cancelled.store.meta("retryAfter"))
        val failedCommit = Harness()
        failedCommit.document(failedCommit.messages().map(FamilyChatProtocol::message))
        failedCommit.http.downloadFailure = TelegramException(503, operation = TelegramOperation.FILE_DOWNLOAD)
        failedCommit.store.base.failCommit = true
        assertThrows(IOException::class.java) { failedCommit.exchange().synchronize() }
        assertEquals(0L, failedCommit.store.meta(TelegramExchange.BLOCKED_DOCUMENT_FAILURES))
        assertEquals(0L, failedCommit.store.meta("offset"))
        assertTrue(failedCommit.store.chatReceipts(failedCommit.room.id).isEmpty())
    }

    @Test fun `one invalid original rejects the complete document before any message or receipt is stored`() {
        val h = Harness()
        val messages = h.messages()
        val texts = messages.map(FamilyChatProtocol::message).toMutableList()
        texts[texts.lastIndex] = FamilyChatProtocol.message(messages.last().copy(senderId = 202))
        h.document(texts)
        assertTrue(h.exchange().synchronize())
        assertEquals(2L, h.store.meta("offset"))
        assertEquals(1, h.invalidDocuments)
        assertTrue(h.store.chatHistory(h.room.id).messages.isEmpty())
        assertTrue(h.store.chatReceipts(h.room.id).isEmpty())
        assertTrue(h.notifications.isEmpty())
    }

    @Test fun `forged or forwarded document metadata never triggers getFile or download`() {
        for (alter in listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("message").getJSONObject("from").put("id", 999) },
            { it.getJSONObject("message").put("forward_origin", JSONObject()) },
            { it.getJSONObject("message").getJSONObject("chat").put("type", "group") },
            { val message = it.getJSONObject("message"); message.put("caption",
                JSONObject(message.getString("caption")).put("receiverId", 999).toString()) },
        )) {
            val h = Harness()
            val document = h.document(h.messages().map(FamilyChatProtocol::message))
            alter(document)
            assertTrue(h.exchange().synchronize())
            assertEquals(2L, h.store.meta("offset"))
            assertEquals(0, h.http.fileInfoCalls)
            assertEquals(0, h.http.downloadCalls)
            assertTrue(h.notifications.isEmpty())
        }
    }

    @Test fun `two file budget preserves the next fetched update and reports a real receive backlog`() {
        val h = Harness()
        val all = mutableListOf<FamilyChatMessage>()
        repeat(3) { all += h.messages().also { messages -> h.document(messages.map(FamilyChatProtocol::message)) } }
        assertTrue(h.exchange().synchronize())
        assertEquals(TelegramExchange.MAX_DOCUMENTS_PER_EXCHANGE, h.http.downloadCalls)
        assertEquals(3L, h.store.meta("offset"))
        assertEquals(34, h.notifications.size)
        assertTrue(h.progress.single().hasMoreUpdates)
        h.time += 1_200
        assertTrue(h.exchange().synchronize())
        assertEquals(3, h.http.downloadCalls)
        assertEquals(4L, h.store.meta("offset"))
        assertEquals(all.map { it.id }, h.notifications)
        assertFalse(h.progress.last().hasMoreUpdates)
    }

    @Test fun `stale document update cannot consume a file budget or download private contents again`() {
        val h = Harness()
        h.document(h.messages().map(FamilyChatProtocol::message))
        h.store.setMeta("offset", 100)
        h.http.replayStaleUpdates = true
        assertTrue(h.exchange().synchronize())
        assertEquals(100L, h.store.meta("offset"))
        assertEquals(0, h.http.fileInfoCalls)
        assertEquals(0, h.http.downloadCalls)
        assertEquals(TelegramReceiveProgress(0), h.progress.single())
    }

    @Test fun `later live location commits and publishes before a temporary earlier file failure without retiring history`() {
        val h = CareHarness()
        val head = h.head()
        h.http.updates += update(2, 101, head.text)
        h.http.downloadFailure = TelegramException(503, operation = TelegramOperation.FILE_DOWNLOAD)
        assertThrows(TelegramException::class.java) { h.exchange().synchronize() }
        assertEquals(0L, h.store.meta("offset"))
        assertEquals(1, h.commits)
        assertEquals(h.event.id, h.engine().latestLocation(101)!!.id)
        assertEquals(listOf(h.event.id), h.store.archived.map { it.second.id })
        assertEquals(0L, h.store.state(h.room.id, 101)!!.revision)
        assertEquals(5, h.store.state(h.room.id, 101)!!.snapshot.stickerBalance)
        assertNull(h.store.receivedDigest(h.room.id, head.packetId))
        assertNull(h.store.eventDigest(h.room.id, 101, h.event.id))
        assertTrue(h.store.receipts(h.room.id).isEmpty())
        assertFalse(h.exchange().synchronize())
        assertEquals(1, h.http.downloadCalls)
        var headReceiptCommitted = false
        h.onCommit = {
            if (h.store.meta("offset") == 3L) {
                assertTrue(h.store.receipts(h.room.id).any { it.packetId == head.packetId && it.digest == head.digest })
                headReceiptCommitted = true
            }
        }
        h.time += 15_001
        assertTrue(h.exchange().synchronize())
        assertEquals(3L, h.store.meta("offset"))
        assertTrue(headReceiptCommitted)
        assertNull(h.store.receivedDigest(h.room.id, head.packetId))
        assertEquals(1, h.store.archived.size)
        assertEquals(0L, h.store.state(h.room.id, 101)!!.revision)
    }

    @Test fun `authenticated text batch previews only the live head while ordered historical originals wait`() {
        val h = CareHarness()
        val historical = h.event.copy(id = UUID.randomUUID().toString(),
            createdAt = "2026-09-30T11:00:00Z", payload = JSONObject(h.event.payload.toString()).put("capturedAt", "2026-09-30T11:00:00Z"))
        val original = FamilyCareProtocol.outgoing(h.room, 101, 101, 202, "child_event", JSONObject()
            .put("epoch", h.epoch).put("revision", 1).put("event", historical.json()))
        val head = h.head()
        h.http.updates += update(2, 101, FamilyTransportProtocol.batch(listOf(original.text, head.text)))
        h.http.downloadFailure = TelegramException(503, operation = TelegramOperation.FILE_DOWNLOAD)
        assertThrows(TelegramException::class.java) { h.exchange().synchronize() }
        assertEquals(h.event.id, h.engine().latestLocation(101)!!.id)
        assertEquals(listOf(h.event.id), h.store.archived.map { it.second.id })
        assertNull(h.store.receivedDigest(h.room.id, original.packetId))
        assertNull(h.store.receivedDigest(h.room.id, head.packetId))
        assertEquals(0L, h.store.meta("offset"))
        assertEquals(0L, h.store.state(h.room.id, 101)!!.revision)
        assertEquals(5, h.store.state(h.room.id, 101)!!.snapshot.stickerBalance)
        assertTrue(h.store.receipts(h.room.id).isEmpty())
    }

    @Test fun `forwarded wrong actor epoch or invalid batch original never precommits a location`() {
        for (alter in listOf<(CareHarness, JSONObject) -> Unit>(
            { _, value -> value.getJSONObject("message").put("forward_origin", JSONObject()) },
            { _, value -> value.getJSONObject("message").getJSONObject("from").put("id", 999) },
            { _, value -> val message = value.getJSONObject("message"); message.put("text", JSONObject(message.getString("text"))
                .put("body", JSONObject(message.getString("text")).getJSONObject("body").put("epoch", UUID.randomUUID().toString())).toString()) },
            { _, value -> val message = value.getJSONObject("message"); val invalid = JSONObject(message.getString("text")).put("actorId", 999).toString()
                message.put("text", FamilyTransportProtocol.batch(listOf(message.getString("text"), invalid))) },
        )) {
            val h = CareHarness()
            val value = update(2, 101, h.head().text)
            alter(h, value)
            h.http.updates += value
            h.http.downloadFailure = TelegramException(503, operation = TelegramOperation.FILE_DOWNLOAD)
            assertThrows(TelegramException::class.java) { h.exchange().synchronize() }
            assertEquals(0, h.commits)
            assertEquals(0L, h.store.meta("offset"))
            assertNull(h.engine().latestLocation(101))
            assertTrue(h.store.archived.isEmpty())
            assertTrue(h.store.receipts(h.room.id).isEmpty())
        }
    }

    @Test fun `failed latest preview commit rolls back location archive and keeps receive cursor without downloading`() {
        val h = CareHarness()
        h.http.updates += update(2, 101, h.head().text)
        h.store.failCommit = true
        assertThrows(IOException::class.java) { h.exchange().synchronize() }
        assertEquals(0, h.commits)
        assertEquals(0L, h.store.meta("offset"))
        assertEquals(0, h.http.fileInfoCalls)
        assertEquals(0, h.http.downloadCalls)
        assertNull(h.engine().latestLocation(101))
        assertTrue(h.store.archived.isEmpty())
        assertTrue(h.store.receipts(h.room.id).isEmpty())
    }

    private class CareHarness {
        private val base = Harness()
        val room = base.room
        val http = base.http
        val lock = Any()
        val store = FamilyCareEngineTest.MemoryStore()
        val epoch = UUID.randomUUID().toString()
        val event = FamilyEvent(UUID.randomUUID().toString(), "location", JSONObject().put("latitude", 37.0)
            .put("longitude", 127.0).put("accuracy", 12.0).put("capturedAt", "2026-09-30T12:00:00Z").put("source", "automatic"),
            "child", "2026-09-30T12:00:00Z", "relayed")
        var time = 1_000_000L
        var commits = 0
        var onCommit: () -> Unit = {}
        init {
            store.saveState(FamilyCareState(room.id, 101, epoch, 0, false, TelegramLedger.emptyState().put("stickerBalance", 5)))
            base.document(base.messages().map(FamilyChatProtocol::message))
        }
        fun head() = FamilyCareProtocol.outgoing(room, 101, 101, 202, "care_location_head",
            FamilyCareLocationHead(room.id, 101, epoch, 100, event).json())
        private fun client() = TelegramClient("202:${"a".repeat(32)}", http)
        private fun transport() = FamilyTransport(client(), store, room, 202, lock = lock, now = { time })
        fun engine() = FamilyCareEngine(client(), store, room, 202, lock = lock, now = { time }, transport = transport())
        fun exchange() = TelegramExchange(client(), store, 0, "child", lock = lock, now = { time },
            familyCare = engine(), transport = transport(), onCommitted = { commits++; onCommit() })
    }

    private class Harness {
        val room = FamilyChatRoom.create("가족", listOf(
            FamilyChatMember(101, "@member101_bot", "아들", "son"),
            FamilyChatMember(202, "@member202_bot", "아빠", "father")))
        val lock = Any()
        val store = ObservedStore()
        val http = FilesHttp()
        val notifications = mutableListOf<String>()
        val progress = mutableListOf<TelegramReceiveProgress>()
        var invalidDocuments = 0
        var time = 1_000_000L
        var onCommit: () -> Unit = {}
        fun messages(count: Int = 17) = List(count) { index ->
            FamilyChatMessage(UUID.randomUUID().toString(), room.id, 101, "과거 메시지 $index", "2026-09-30T12:00:00Z")
        }
        fun document(texts: List<String>): JSONObject {
            val encoded = requireNotNull(FamilyDocumentProtocol.encode(texts, room.id, 101, 202))
            val fileId = "document_${http.files.size + 1}"
            http.files[fileId] = encoded.bytes
            val document = JSONObject().put("update_id", http.updates.size + 1).put("message", JSONObject()
                .put("from", JSONObject().put("id", 101).put("is_bot", true))
                .put("chat", JSONObject().put("id", 101).put("type", "private"))
                .put("caption", encoded.caption)
                .put("document", JSONObject().put("file_id", fileId).put("file_size", encoded.bytes.size)))
            http.updates += document
            return document
        }
        fun exchange(): TelegramExchange {
            val client = TelegramClient("202:${"a".repeat(32)}", http)
            val transport = FamilyTransport(client, store, room, 202, lock = lock, now = { time })
            val chat = FamilyChatExchange(client, store, room, 202, lock = lock, now = { time },
                onReceived = { notifications += it.id }, transport = transport)
            return TelegramExchange(client, store, 0, "child", lock = lock, now = { time },
                familyChat = chat, transport = transport, onCommitted = { onCommit() },
                onPollProgress = { progress += it }, onInvalidDocument = { invalidDocuments++ })
        }
    }

    private class ObservedStore(val base: FamilyChatExchangeTest.MemoryStore = FamilyChatExchangeTest.MemoryStore()) :
        TelegramExchangeStore by base, FamilyChatStore by base {
        var transactionDepth = 0
            private set
        override fun <T> transaction(block: () -> T): T {
            transactionDepth++
            try { return base.transaction(block) } finally { transactionDepth-- }
        }
    }

    private class FilesHttp : TelegramHttpTransport {
        val updates = mutableListOf<JSONObject>()
        val files = linkedMapOf<String, ByteArray>()
        var pollCalls = 0
        var fileInfoCalls = 0
        var downloadCalls = 0
        var getFileFailureCode: Int? = null
        var downloadFailure: TelegramException? = null
        var replayStaleUpdates = false
        var onFileRequest: () -> Unit = {}
        override fun execute(token: String, method: String, json: String, timeoutSeconds: Int): TelegramHttpResponse {
            val body = JSONObject(json)
            return when (method) {
                "getUpdates" -> {
                    pollCalls++
                    ok(JSONArray(updates.filter { replayStaleUpdates || it.getLong("update_id") >= body.getLong("offset") }.take(100)))
                }
                "getFile" -> {
                    fileInfoCalls++
                    onFileRequest()
                    val failure = getFileFailureCode
                    getFileFailureCode = null
                    if (failure != null) TelegramHttpResponse(failure, JSONObject().put("ok", false)
                        .put("error_code", failure).put("parameters", JSONObject().put("retry_after", 70)).toString())
                    else {
                        val fileId = body.getString("file_id")
                        ok(JSONObject().put("file_id", fileId).put("file_size", files.getValue(fileId).size)
                            .put("file_path", "documents/$fileId.gz"))
                    }
                }
                "sendMessage" -> ok(JSONObject().put("chat", JSONObject().put("id", body.getLong("chat_id")).put("type", "private")))
                else -> error("Unexpected method $method")
            }
        }
        override fun downloadFile(token: String, filePath: String, maxBytes: Int, checkActive: () -> Unit): ByteArray {
            downloadCalls++
            onFileRequest()
            checkActive()
            val failure = downloadFailure
            downloadFailure = null
            if (failure != null) throw failure
            val fileId = filePath.substringAfterLast('/').removeSuffix(".gz")
            return files.getValue(fileId).copyOf().also { assertTrue(it.size <= maxBytes) }
        }
        override fun uploadDocument(token: String, fields: String, filename: String, bytes: ByteArray): TelegramHttpResponse {
            val target = JSONObject(fields).getLong("chat_id")
            return ok(JSONObject().put("chat", JSONObject().put("id", target).put("type", "private")))
        }
        private fun ok(result: Any) = TelegramHttpResponse(200, JSONObject().put("ok", true).put("result", result).toString())
    }

    companion object {
        private fun update(id: Long, sender: Long, text: String): JSONObject = JSONObject().put("update_id", id)
            .put("message", JSONObject().put("text", text).put("from", JSONObject().put("id", sender).put("is_bot", true))
                .put("chat", JSONObject().put("id", sender).put("type", "private")))
    }
}
