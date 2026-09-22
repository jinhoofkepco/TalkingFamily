package kr.family.homeway.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Random isolated databases only: these cases never open installed family data or contact Telegram. */
@RunWith(AndroidJUnit4::class)
class FamilyCareStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String
    private var opened: LocalStore? = null
    private val utc = ZoneId.of("UTC")
    private val timestamp = "2026-09-24T03:00:00Z"
    @Before fun prepare() { databaseName = "family-care-test-${UUID.randomUUID()}.db" }
    @After fun cleanUp() { opened?.close(); context.deleteDatabase(databaseName) }
    private fun store(): LocalStore = opened ?: LocalStore(context, databaseName).also { opened = it }
    private fun reopen(): LocalStore { opened?.close(); opened = null; return store() }
    private fun id() = UUID.randomUUID().toString()
    private fun room() = FamilyChatRoom(id(), "우리 가족", listOf(
        FamilyChatMember(101, "@test_mother_bot", "엄마", "mother"),
        FamilyChatMember(102, "@test_father_bot", "아빠", "father"),
        FamilyChatMember(103, "@test_son_bot", "아들", "son"),
        FamilyChatMember(104, "@test_daughter_bot", "딸", "daughter")))
    private fun state(roomId: String, childId: Long = 103, revision: Long = 1, authority: Boolean = true) =
        FamilyCareState(roomId, childId, id(), revision, authority, TelegramLedger.emptyState().put("careRewardVersions", JSONObject()))
    private fun location(at: String = timestamp, eventId: String = id(), latitude: Double = 37.0, delivery: String = "relayed") =
        FamilyEvent(eventId, "location", JSONObject().put("latitude", latitude).put("longitude", 127.0)
            .put("accuracy", 15).put("source", "automatic").put("capturedAt", at), "child", at, delivery)
    private fun chat() = FamilyEvent(id(), "chat", JSONObject().put("text", "기존 대화"), "child", timestamp, "relayed")
    private fun digest(text: String) = FamilyChatValidation.digest(text)
    private fun packet(roomId: String, childId: Long = 103, peerId: Long = 101, packetId: String = id(), text: String = "care payload") =
        FamilyCareOutgoing(roomId, packetId, childId, peerId, text, digest(text))
    private fun outcome(state: FamilyCareState) = FamilyCareOutcome(state.roomId, state.childId, id(), 101,
        digest("command"), state.epoch, state.revision, true)

    @Test fun upgradeFromV4AddsCareWithoutChangingRoomMessagesPairLedgerHistoryOrTelegramCursor() {
        val group = room()
        val position = location()
        val conversation = chat()
        val oldState = TelegramLedger.emptyState().put("stickerBalance", 15)
            .put("events", JSONArray(listOf(position.json(), conversation.json())))
        val roomMessage = FamilyChatMessage(id(), group.id, 103, "기존 가족 대화", timestamp)
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE outbox (id TEXT PRIMARY KEY,event TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending',error TEXT)")
            db.execSQL("CREATE TABLE cache (id INTEGER PRIMARY KEY CHECK(id=1),state TEXT NOT NULL)")
            db.execSQL("CREATE TABLE telegram_receipts (id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE telegram_meta (name TEXT PRIMARY KEY,value INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE movement_history (id TEXT PRIMARY KEY,measured_at INTEGER NOT NULL,local_day TEXT NOT NULL,event TEXT NOT NULL)")
            db.execSQL("CREATE INDEX movement_history_day_time ON movement_history(local_day DESC,measured_at DESC,id DESC)")
            db.execSQL("CREATE INDEX movement_history_time ON movement_history(measured_at DESC)")
            db.execSQL("CREATE TABLE movement_settings (name TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("INSERT INTO movement_settings(name,value) VALUES('zone','UTC')")
            db.execSQL("CREATE TABLE private_chat_history (id TEXT PRIMARY KEY,created_at INTEGER NOT NULL,event TEXT NOT NULL)")
            db.execSQL("CREATE INDEX private_chat_history_time ON private_chat_history(created_at DESC,id DESC)")
            SqliteFamilyChatStore.createTables(db)
            db.execSQL("INSERT INTO cache(id,state) VALUES(1,?)", arrayOf(oldState.toString()))
            db.execSQL("INSERT INTO outbox(id,event) VALUES(?,?)", arrayOf(conversation.id, conversation.json().toString()))
            db.execSQL("INSERT INTO telegram_meta(name,value) VALUES('offset',9812)")
            db.execSQL("INSERT INTO telegram_meta(name,value) VALUES('sentAt',119)")
            db.execSQL("INSERT INTO telegram_receipts(id) VALUES(?)", arrayOf(conversation.id))
            db.execSQL("INSERT INTO movement_history(id,measured_at,local_day,event) VALUES(?,?,?,?)", arrayOf(position.id,
                Instant.parse(timestamp).toEpochMilli(), "2026-09-24", position.json().toString()))
            db.execSQL("INSERT INTO private_chat_history(id,created_at,event) VALUES(?,?,?)", arrayOf(conversation.id,
                Instant.parse(timestamp).toEpochMilli(), conversation.json().toString()))
            db.execSQL("INSERT INTO family_chat_rooms(id,configuration) VALUES(?,?)", arrayOf(group.id, group.json().toString()))
            db.execSQL("INSERT INTO family_chat_active(id,room_id) VALUES(1,?)", arrayOf(group.id))
            db.execSQL("INSERT INTO family_chat_messages(room_id,id,created_at,message,digest) VALUES(?,?,?,?,?)", arrayOf(group.id,
                roomMessage.id, Instant.parse(timestamp).epochSecond * 1_000_000_000L, roomMessage.json().toString(), roomMessage.digest))
            db.execSQL("INSERT INTO family_chat_deliveries(room_id,message_id,peer_id,sent_at,completed) VALUES(?,?,101,100,0)", arrayOf(group.id, roomMessage.id))
            db.execSQL("INSERT INTO family_chat_peer_state(room_id,peer_id,retry_after) VALUES(?,101,10000)", arrayOf(group.id))
            db.version = 4
        }
        val db = store()
        assertEquals(5, db.readableDatabase.version)
        assertEquals(oldState.toString(), db.cached()!!.toString())
        assertEquals(9812L, db.meta("offset"))
        assertEquals(119L, db.meta("sentAt"))
        assertEquals(listOf(conversation.id), db.receipts())
        assertEquals(listOf(conversation.id), db.pending().map { it.id })
        assertEquals(listOf(conversation.id), db.privateChatHistory().events.map { it.id })
        assertEquals(listOf(position.id), db.movementHistory("2026-09-24", zone = utc).events.map { it.id })
        assertEquals(group, db.familyChat.activeRoom())
        assertEquals(roomMessage.id, db.familyChat.chatHistory(group.id).messages.single().id)
        assertEquals(100L, db.familyChat.pendingChatDeliveries(group.id).single().sentAt)
        assertEquals(10000L, db.familyChat.chatPeerRetryAfter(group.id, 101))
        assertNull(db.familyCare.state(group.id, 103))
        assertTrue(db.familyCare.pendingPackets(group.id).isEmpty())
    }

    @Test fun authoritativeStatesAndParentSnapshotsAreSeparateAndRejectRollbackEpochOrRoleReplacement() {
        val roomId = id()
        val son = state(roomId)
        val daughter = state(roomId, 104, authority = false).copy(state = TelegramLedger.emptyState().put("stickerBalance", 8))
        val care = store().familyCare
        care.saveState(son)
        care.saveState(daughter)
        val updated = son.copy(revision = 2, state = JSONObject(son.state.toString()).put("stickerBalance", 12))
        care.saveState(updated)
        care.saveState(updated.copy(state = JSONObject(updated.state.toString())))
        assertThrows(IllegalArgumentException::class.java) { care.saveState(son) }
        assertThrows(IllegalArgumentException::class.java) { care.saveState(updated.copy(epoch = id(), revision = 3)) }
        assertThrows(IllegalArgumentException::class.java) { care.saveState(updated.copy(authoritative = false, revision = 3)) }
        assertThrows(IllegalArgumentException::class.java) { care.saveState(updated.copy(state = JSONObject(updated.state.toString()).put("stickerBalance", 99))) }
        val resumed = reopen().familyCare
        assertEquals(2L, resumed.state(roomId, 103)!!.revision)
        assertEquals(12, resumed.state(roomId, 103)!!.snapshot.stickerBalance)
        assertEquals(8, resumed.state(roomId, 104)!!.snapshot.stickerBalance)
        assertTrue(resumed.state(roomId, 103)!!.authoritative)
        assertFalse(resumed.state(roomId, 104)!!.authoritative)
        assertNull(resumed.state(id(), 103))
    }

    @Test fun commandsOutcomesAndReceiveDigestsRemainImmutableAcrossRestartAndTransportAck() {
        val state = state(id())
        val result = outcome(state)
        val packetId = id()
        val care = store().familyCare
        val command = FamilyCareCommand(result.commandId, state.roomId, state.childId, 101, state.epoch,
            "sticker_award", JSONObject().put("count", 1).put("reason", "정리 잘했어요"), timestamp)
        val outgoing = packet(state.roomId, packetId = command.id, peerId = state.childId)
        care.saveCommand(command)
        care.saveCommand(command)
        care.queuePacket(outgoing)
        care.markSent(state.roomId, outgoing.packetId, outgoing.peerId, 1000)
        care.acknowledge(state.roomId, outgoing.packetId, outgoing.peerId, outgoing.digest)
        care.saveOutcome(result)
        care.saveOutcome(result)
        care.recordReceived(state.roomId, packetId, digest("body"))
        care.recordReceived(state.roomId, packetId, digest("body"))
        assertThrows(IllegalArgumentException::class.java) { care.saveOutcome(result.copy(accepted = false)) }
        assertThrows(IllegalArgumentException::class.java) { care.saveOutcome(result.copy(childId = 104)) }
        assertThrows(IllegalArgumentException::class.java) { care.saveCommand(command.copy(childId = 104)) }
        assertThrows(IllegalArgumentException::class.java) { care.recordReceived(state.roomId, packetId, digest("changed")) }
        val resumed = reopen().familyCare
        assertTrue(resumed.pendingPackets(state.roomId).isEmpty())
        assertEquals(listOf(command.digest), resumed.commands(state.roomId, state.childId).map { it.digest })
        assertTrue(resumed.commands(state.roomId, 104).isEmpty())
        assertEquals(result, resumed.outcome(state.roomId, result.commandId))
        assertEquals(listOf(result), resumed.outcomes(state.roomId, 103))
        assertTrue(resumed.outcomes(state.roomId, 104).isEmpty())
        assertEquals(digest("body"), resumed.receivedDigest(state.roomId, packetId))
    }

    @Test fun eachParentDeliveryHasDurableFifoRetryAndAcknowledgementWithoutCompletingOfflineParent() {
        val roomId = id()
        val first = packet(roomId)
        val second = packet(roomId, text = "second")
        val care = store().familyCare
        listOf(first, second).forEach { care.queuePacket(it); care.queuePacket(it.copy(peerId = 102)) }
        care.acknowledge(roomId, first.packetId, 101, first.digest) // Unsent ACK cannot complete.
        assertEquals(4, care.pendingPackets(roomId).size)
        care.markSent(roomId, first.packetId, 101, 1000)
        care.acknowledge(roomId, first.packetId, 101, digest("wrong"))
        care.acknowledge(roomId, first.packetId, 104, first.digest)
        assertEquals(4, care.pendingPackets(roomId).size)
        care.acknowledge(roomId, first.packetId, 101, first.digest)
        care.markSent(roomId, first.packetId, 102, 1001)
        care.setRetryAfter(roomId, 102, 9000)
        care.queuePacket(first) // Retry cannot requeue an acknowledged parent.
        assertThrows(IllegalArgumentException::class.java) { care.queuePacket(first.copy(text = "changed", digest = digest("changed"))) }
        val resumed = reopen().familyCare
        assertEquals(listOf(second.packetId), resumed.pendingPackets(roomId).filter { it.peerId == 101L }.map { it.packetId })
        assertEquals(listOf(first.packetId, second.packetId), resumed.pendingPackets(roomId).filter { it.peerId == 102L }.map { it.packetId })
        assertEquals(1001L, resumed.pendingPackets(roomId).first { it.peerId == 102L }.sentAt)
        assertEquals(9000L, resumed.retryAfter(roomId, 102))
        assertEquals(0L, resumed.retryAfter(roomId, 101))
        assertTrue(resumed.pendingPackets(id()).isEmpty())
    }

    @Test fun careStateOutcomeMovementReceiptRoomChatAndReceiveCursorCommitOrRollbackTogether() {
        val group = room()
        val source = state(group.id)
        val result = outcome(source)
        val packetId = id()
        val receipt = FamilyCareReceipt(group.id, packetId, 103, 101, digest("received"))
        val event = location()
        val chat = FamilyChatMessage(id(), group.id, 102, "함께 받음", timestamp)
        val db = store()
        db.setMeta("offset", 400)
        fun receive() {
            db.familyCare.saveState(source)
            db.familyCare.saveOutcome(result)
            db.familyCare.recordReceived(group.id, packetId, receipt.digest)
            db.familyCare.queueReceipt(receipt)
            db.familyCare.archiveEvent(group.id, 103, event)
            db.familyChat.insertChatMessage(chat, emptyList())
            db.setMeta("offset", 401)
        }
        assertThrows(IllegalStateException::class.java) { db.transaction { receive(); error("failure before cursor commit") } }
        assertEquals(400L, db.meta("offset"))
        assertNull(db.familyCare.state(group.id, 103))
        assertNull(db.familyCare.outcome(group.id, result.commandId))
        assertNull(db.familyCare.receivedDigest(group.id, packetId))
        assertTrue(db.familyCare.receipts(group.id).isEmpty())
        assertTrue(db.familyCare.movementHistory(group.id, 103, zone = utc).days.isEmpty())
        assertNull(db.familyChat.chatMessage(group.id, chat.id))
        db.transaction { receive() }
        val resumed = reopen()
        assertEquals(401L, resumed.meta("offset"))
        assertEquals(source.epoch, resumed.familyCare.state(group.id, 103)!!.epoch)
        assertEquals(result, resumed.familyCare.outcome(group.id, result.commandId))
        assertEquals(listOf(receipt), resumed.familyCare.receipts(group.id))
        resumed.familyCare.removeReceipt(receipt.copy(digest = digest("wrong")))
        assertEquals(1, resumed.familyCare.receipts(group.id).size)
        resumed.familyCare.removeReceipt(receipt)
        assertTrue(resumed.familyCare.receipts(group.id).isEmpty())
        assertEquals(listOf(event.id), resumed.familyCare.movementHistory(group.id, 103, zone = utc).events.map { it.id })
        assertEquals(chat.id, resumed.familyChat.chatMessage(group.id, chat.id)!!.id)
    }

    @Test fun snapshotAssemblySurvivesRestartAndCannotMixChildrenEpochsRevisionsOrChangedParts() {
        val roomId = id()
        val source = state(roomId, authority = false).let { it.copy(state = it.state.put("events", JSONArray(List(100) { location().json() }))) }
        val chunks = FamilyCareSnapshots.chunks(source)
        assertTrue(chunks.size > 1)
        val first = chunks.first()
        val care = store().familyCare
        care.putSnapshotChunk(first)
        care.putSnapshotChunk(first)
        listOf(first.copy(epoch = id()), first.copy(childId = 104), first.copy(revision = first.revision + 1),
            first.copy(count = first.count + 1), first.copy(digest = digest("changed")), first.copy(encoded = "YWJj")).forEach {
            assertThrows(IllegalArgumentException::class.java) { care.putSnapshotChunk(it) }
        }
        val resumed = reopen().familyCare
        assertEquals(listOf(first), resumed.snapshotChunks(roomId, first.transferId))
        chunks.drop(1).reversed().forEach { resumed.putSnapshotChunk(it) }
        val rebuilt = FamilyCareSnapshots.assemble(resumed.snapshotChunks(roomId, first.transferId))
        assertEquals(source.childId, rebuilt.childId)
        assertEquals(source.epoch, rebuilt.epoch)
        assertEquals(100, rebuilt.snapshot.events.size)
        resumed.removeSnapshotChunks(roomId, first.transferId)
        assertTrue(resumed.snapshotChunks(roomId, first.transferId).isEmpty())
    }

    @Test fun movementPagesSeparateSiblingsAndRoomsEvenWhenIdsAndTimestampsMatch() {
        val roomId = id()
        val otherRoom = id()
        val son = List(305) { location() }
        val daughter = son.first().copy(payload = JSONObject(son.first().payload.toString()).put("latitude", 36.0))
        val previousDay = location("2026-09-23T23:59:59Z")
        val care = store().familyCare
        care.archiveMovement(roomId, 103, son + previousDay)
        care.archiveEvent(roomId, 104, daughter)
        care.archiveEvent(otherRoom, 103, location(eventId = daughter.id, latitude = 35.0))
        val first = care.movementHistory(roomId, 103, "2026-09-24", zone = utc)
        val second = care.movementHistory(roomId, 103, first.day, first.next, zone = utc)
        assertEquals(300, first.events.size)
        assertEquals(5, second.events.size)
        assertNotNull(first.next)
        assertNull(second.next)
        assertEquals(son.map { it.id }.sortedDescending(), (first.events + second.events).map { it.id })
        assertEquals(listOf("2026-09-24", "2026-09-23"), first.days)
        assertEquals(36.0, care.movementHistory(roomId, 104, zone = utc).events.single().payload.getDouble("latitude"), 0.0)
        assertEquals(35.0, care.movementHistory(otherRoom, 103, zone = utc).events.single().payload.getDouble("latitude"), 0.0)
        assertEquals(37.0, first.events.first().payload.getDouble("latitude"), 0.0)
    }

    @Test fun legacyImportIsOncePerChildKeepsDeliveryAndTimezoneChangesRebucketEveryChild() {
        val roomId = id()
        val retained = location("2026-09-24T15:01:00Z")
        val later = location("2026-09-25T15:01:00Z")
        val db = store()
        db.cache(TelegramLedger.emptyState().put("events", JSONArray(listOf(retained.json()))))
        db.familyCare.importLegacyMovement(roomId, 103)
        db.cache(TelegramLedger.emptyState().put("events", JSONArray(listOf(later.json()))))
        db.familyCare.importLegacyMovement(roomId, 103)
        db.familyCare.archiveEvent(roomId, 103, retained.copy(delivery = "queued"))
        db.familyCare.archiveEvent(roomId, 104, location(retained.createdAt))
        assertEquals(listOf("2026-09-24"), db.familyCare.movementHistory(roomId, 103, zone = utc).days)
        assertEquals("relayed", db.familyCare.movementHistory(roomId, 103, zone = utc).events.single().delivery)
        assertEquals(listOf("2026-09-25"), db.familyCare.movementHistory(roomId, 103, zone = ZoneId.of("Asia/Seoul")).days)
        assertEquals(listOf("2026-09-25"), db.familyCare.movementHistory(roomId, 104, zone = ZoneId.of("Asia/Seoul")).days)
        assertEquals(listOf("2026-09-24"), db.familyCare.movementHistory(roomId, 104, zone = utc).days)
        val resumed = reopen()
        resumed.familyCare.importLegacyMovement(roomId, 103)
        assertEquals(1, resumed.familyCare.movementHistory(roomId, 103, zone = utc).events.size)
        assertEquals(2, resumed.movementHistory("2026-09-25", zone = utc).days.size)
    }

    @Test fun leavingRoomRetainsCareWhileExplicitResetClearsAllCareTablesAndImportMarkers() {
        val group = room()
        val source = state(group.id)
        val outgoing = packet(group.id)
        val db = store()
        db.familyChat.setActiveRoom(group)
        db.familyCare.saveState(source)
        db.familyCare.saveOutcome(outcome(source))
        db.familyCare.saveCommand(FamilyCareCommand(id(), group.id, 103, 101, source.epoch,
            "sticker_award", JSONObject().put("count", 1).put("reason", "정리 잘했어요"), timestamp))
        db.familyCare.queuePacket(outgoing)
        db.familyCare.recordReceived(group.id, outgoing.packetId, outgoing.digest)
        db.familyCare.queueReceipt(FamilyCareReceipt(group.id, outgoing.packetId, 103, 101, outgoing.digest))
        db.familyCare.setRetryAfter(group.id, 101, 9000)
        db.familyCare.putSnapshotChunk(FamilyCareSnapshots.chunks(source).first())
        db.familyCare.setError(group.id, 103, "최신 칭찬판을 기다리는 중이에요.")
        db.familyCare.archiveEvent(group.id, 103, location())
        db.familyCare.importLegacyMovement(group.id, 103)
        db.familyChat.setActiveRoom(null)
        assertNotNull(db.familyCare.state(group.id, 103))
        assertEquals(1, db.familyCare.pendingPackets(group.id).size)
        assertEquals(1, db.familyCare.movementHistory(group.id, 103, zone = utc).events.size)
        db.transaction { db.clear() }
        val tables = db.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'family_care_%'", null).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
        assertTrue(tables.isNotEmpty())
        tables.forEach { table -> db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { rows ->
            rows.moveToFirst(); assertEquals(table, 0, rows.getInt(0))
        } }
        assertNull(db.familyCare.state(group.id, 103))
        assertNull(db.familyCare.error(group.id, 103))
        assertEquals(0L, db.familyCare.retryAfter(group.id, 101))
    }
}
