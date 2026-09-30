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
    private fun incoming(sender: Long, text: String) = JSONObject().put("update_id", 1).put("message", JSONObject()
        .put("from", JSONObject().put("id", sender).put("is_bot", true))
        .put("chat", JSONObject().put("id", sender).put("type", "private")).put("text", text))

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
        assertEquals(10, db.readableDatabase.version)
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

    @Test fun upgradeFromV5PreservesPendingAndCompletedCareWithUnconfirmedAttempts() {
        val roomId = id()
        val first = packet(roomId)
        val second = packet(roomId, text = "second")
        val source = state(roomId)
        val db = store()
        db.familyCare.saveState(source)
        listOf(first, first.copy(peerId = 102), second).forEach(db.familyCare::queuePacket)
        db.familyCare.markSent(roomId, first.packetId, 101, 111)
        db.familyCare.acknowledge(roomId, first.packetId, 101, first.digest)
        db.familyCare.markSent(roomId, first.packetId, 102, 222)
        db.familyCare.markSendConfirmed(roomId, first.packetId, 102)
        db.familyCare.setRetryAfter(roomId, 102, 999)
        db.setMeta("offset", 12345)
        // Recreate the exact v5 delivery schema; all other care tables are unchanged in v6.
        db.writableDatabase.apply {
            execSQL("ALTER TABLE family_care_deliveries RENAME TO care_deliveries_v6_fixture")
            execSQL("CREATE TABLE family_care_deliveries (room_id TEXT NOT NULL,packet_id TEXT NOT NULL,peer_id INTEGER NOT NULL,sent_at INTEGER NOT NULL DEFAULT 0," +
                "completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN (0,1)),PRIMARY KEY(room_id,packet_id,peer_id))")
            execSQL("INSERT INTO family_care_deliveries(room_id,packet_id,peer_id,sent_at,completed) " +
                "SELECT room_id,packet_id,peer_id,sent_at,completed FROM care_deliveries_v6_fixture")
            execSQL("DROP TABLE care_deliveries_v6_fixture")
            execSQL("CREATE INDEX family_care_delivery_pending ON family_care_deliveries(room_id,completed,packet_id,peer_id)")
            version = 5
        }
        val upgraded = reopen()
        assertEquals(10, upgraded.readableDatabase.version)
        assertEquals(12345L, upgraded.meta("offset"))
        assertEquals(source.epoch, upgraded.familyCare.state(roomId, 103)!!.epoch)
        assertEquals(999L, upgraded.familyCare.retryAfter(roomId, 102))
        val pending = upgraded.familyCare.pendingPackets(roomId)
        assertEquals(listOf(first.packetId, second.packetId), pending.map { it.packetId })
        assertEquals(listOf(102L, 101L), pending.map { it.peerId })
        assertEquals(listOf(222L, 0L), pending.map { it.sentAt })
        assertTrue(pending.none { it.sendConfirmed })
        // An ACK of a pre-upgrade attempt still completes that exact packet safely.
        upgraded.familyCare.acknowledge(roomId, first.packetId, 102, first.digest)
        assertEquals(listOf(second.packetId), upgraded.familyCare.pendingPackets(roomId).map { it.packetId })
    }

    @Test fun confirmedSendAndAmbiguousRetryRemainDistinctAcrossRestart() {
        val roomId = id()
        val first = packet(roomId)
        val second = packet(roomId, text = "second")
        val care = store().familyCare
        care.queuePacket(first)
        care.queuePacket(second)
        care.markSendConfirmed(roomId, first.packetId, 101) // No attempt cannot be confirmed.
        assertFalse(care.pendingPackets(roomId).first().sendConfirmed)
        care.markSent(roomId, first.packetId, 101, 1000)
        care.markSendConfirmed(roomId, first.packetId, 101)
        val resumed = reopen().familyCare
        assertTrue(resumed.pendingPackets(roomId).first().sendConfirmed)
        resumed.markSent(roomId, first.packetId, 101, 2000)
        val afterRetry = reopen().familyCare.pendingPackets(roomId).first()
        assertEquals(2000L, afterRetry.sentAt)
        assertFalse(afterRetry.sendConfirmed)
    }

    @Test fun populatedV6ReplayMigrationIsLazyAndRetainsFinancialStateHistoryQueuesAndCursor() {
        val db = store()
        val roomId = id()
        val oldAward = FamilyEvent(id(), "sticker_award", JSONObject().put("count", 1).put("reason", "이전 칭찬"), "guardian", timestamp, "relayed")
        val latest = location()
        val identities = JSONObject().put(oldAward.id, TelegramLedger.eventDigest(oldAward))
            .put(latest.id, TelegramLedger.eventDigest(latest))
        repeat(2000) { identities.put(id(), "a".repeat(64)) }
        val oldProjection = TelegramLedger.emptyState()
            .put("stickerBalance", 37).put("sharingEnabled", true).put("appliedEventIds", identities)
            .put("events", JSONArray(listOf(latest.json(), oldAward.json())))
            .put("latestLocation", latest.json())
            .put("rewards", JSONArray().put(JSONObject().put("id", id()).put("name", "가족 약속").put("cost", 3)))
        val oldState = state(roomId, revision = 2077).copy(state = oldProjection)
        val legacy = TelegramLedger.apply(TelegramLedger.emptyState(), oldAward)
        db.cache(legacy)
        db.setMeta("offset", 74321)
        db.familyCare.archiveEvent(roomId, 103, latest)
        val pending = packet(roomId)
        db.familyCare.queuePacket(pending)
        db.writableDatabase.apply {
            execSQL("DROP TABLE family_care_states")
            execSQL("CREATE TABLE family_care_states (room_id TEXT NOT NULL,child_id INTEGER NOT NULL,state TEXT NOT NULL,PRIMARY KEY(room_id,child_id))")
            execSQL("INSERT INTO family_care_states(room_id,child_id,state) VALUES(?,?,?)", arrayOf(roomId, 103, oldState.json().toString()))
            execSQL("DROP TABLE family_care_event_ids")
            execSQL("ALTER TABLE family_care_packets RENAME TO care_packets_v7_fixture")
            execSQL("CREATE TABLE family_care_packets (room_id TEXT NOT NULL,id TEXT NOT NULL,child_id INTEGER NOT NULL,text TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(room_id,id))")
            execSQL("INSERT INTO family_care_packets(room_id,id,child_id,text,digest) SELECT room_id,id,child_id,text,digest FROM care_packets_v7_fixture")
            execSQL("DROP TABLE care_packets_v7_fixture")
            version = 6
        }
        val upgraded = reopen()
        assertEquals(10, upgraded.readableDatabase.version)
        // Opening a database must not parse a large old care state on a service's main thread.
        upgraded.readableDatabase.rawQuery("SELECT state,epoch FROM family_care_states", null).use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(1)); assertEquals(oldState.json().toString(), it.getString(0))
        }
        val migrated = checkNotNull(upgraded.familyCare.state(roomId, 103))
        assertEquals(0, migrated.state.getJSONObject("appliedEventIds").length())
        assertEquals(2002, identities.length()) // Caller-owned state was not mutated.
        assertEquals(oldState.epoch, migrated.epoch)
        assertEquals(2077L, migrated.revision)
        assertEquals(37, migrated.snapshot.stickerBalance)
        assertTrue(migrated.snapshot.sharingEnabled)
        assertEquals("가족 약속", migrated.snapshot.rewards.single().name)
        assertEquals(setOf(latest.id, oldAward.id), migrated.snapshot.events.map { it.id }.toSet())
        assertEquals(TelegramLedger.eventDigest(oldAward), upgraded.familyCare.eventDigest(roomId, 103, oldAward.id))
        assertEquals(TelegramLedger.eventDigest(latest), upgraded.familyCare.eventDigest(roomId, 103, latest.id))
        assertNull(upgraded.familyCare.eventDigest(roomId, 104, oldAward.id))
        upgraded.readableDatabase.rawQuery("SELECT COUNT(*) FROM family_care_event_ids WHERE room_id=?", arrayOf(roomId)).use {
            it.moveToFirst(); assertEquals(2002, it.getInt(0))
        }
        assertEquals(74321L, upgraded.meta("offset"))
        assertEquals(legacy.toString(), upgraded.cached()!!.toString())
        assertEquals(listOf(pending.packetId), upgraded.familyCare.pendingPackets(roomId).map { it.packetId })
        assertEquals(pending.text, upgraded.familyCare.pendingPacket(roomId, pending.packetId, 101)?.text)
        assertEquals(listOf(latest.id), upgraded.familyCare.movementHistory(roomId, 103, zone = utc).events.map { it.id })
        val engine = FamilyCareEngine(TelegramClient("103:${"synthetic_credentials_".repeat(2)}"), upgraded.familyCare,
            room().copy(id = roomId), 103)
        assertTrue(upgraded.transaction { engine.processLegacy(oldAward, 101) })
        assertEquals("An archived financial identity must never award again", 37,
            upgraded.familyCare.state(roomId, 103)!!.snapshot.stickerBalance)
        assertEquals(2077L, upgraded.familyCare.stateMetadata(roomId, 103)!!.revision)
        val resumed = reopen().familyCare
        assertEquals(FamilyCareStateMetadata(oldState.epoch, 2077, true), resumed.stateMetadata(roomId, 103))
        assertEquals(TelegramLedger.eventDigest(oldAward), resumed.eventDigest(roomId, 103, oldAward.id))
        assertThrows(IllegalArgumentException::class.java) { resumed.recordEventDigest(roomId, 103, oldAward.id, "b".repeat(64)) }
        resumed.markSent(roomId, pending.packetId, 101, 100)
        resumed.acknowledge(roomId, pending.packetId, 101, pending.digest)
        resumed.queuePacket(pending)
        assertNull(resumed.pendingPacket(roomId, pending.packetId, 101))
        assertThrows(IllegalArgumentException::class.java) { resumed.queuePacket(pending.copy(text = "changed old payload")) }
    }

    @Test fun compactStateAndReplayIdentityCommitTogetherAndConflictingReplayCannotChangeBalance() {
        val db = store()
        val initial = state(id())
        val event = FamilyEvent(id(), "sticker_award", JSONObject().put("count", 1).put("reason", "정리"), "guardian", timestamp, "relayed")
        db.familyCare.saveState(initial)
        val next = initial.copy(revision = 2, state = TelegramLedger.apply(initial.state, event))
        assertThrows(IllegalStateException::class.java) { db.transaction {
            db.familyCare.saveState(next); db.setMeta("offset", 9); error("rollback")
        } }
        assertNull(db.familyCare.eventDigest(initial.roomId, 103, event.id))
        assertEquals(0, db.familyCare.state(initial.roomId, 103)!!.snapshot.stickerBalance)
        assertEquals(0L, db.meta("offset"))
        db.familyCare.saveState(next)
        val retained = reopen().familyCare
        assertEquals(1, retained.state(initial.roomId, 103)!!.snapshot.stickerBalance)
        assertEquals(0, retained.state(initial.roomId, 103)!!.state.getJSONObject("appliedEventIds").length())
        assertEquals(TelegramLedger.eventDigest(event), retained.eventDigest(initial.roomId, 103, event.id))
        val engine = FamilyCareEngine(TelegramClient("103:${"synthetic_credentials_".repeat(2)}"), retained,
            room().copy(id = initial.roomId), 103)
        assertTrue(store().transaction { engine.processLegacy(event, 101) })
        assertEquals(1, retained.state(initial.roomId, 103)!!.snapshot.stickerBalance)
        val conflicting = next.copy(revision = 3, state = JSONObject(next.state.toString()).put("stickerBalance", 99)
            .put("appliedEventIds", JSONObject().put(event.id, "f".repeat(64))))
        assertThrows(IllegalArgumentException::class.java) { retained.saveState(conflicting) }
        assertEquals(2L, retained.stateMetadata(initial.roomId, 103)!!.revision)
        assertEquals(1, retained.state(initial.roomId, 103)!!.snapshot.stickerBalance)
    }

    @Test fun movingAuthorityToAnotherRoomCopiesArchivedReplayIdsWithoutLosingEitherBoard() {
        val db = store()
        val care = db.familyCare
        val oldAward = FamilyEvent(id(), "sticker_award", JSONObject().put("count", 1).put("reason", "오래된 칭찬"),
            "guardian", timestamp, "relayed")
        val oldIds = JSONObject().put(oldAward.id, TelegramLedger.eventDigest(oldAward))
        repeat(1500) { oldIds.put(id(), "a".repeat(64)) }
        val source = state(id(), revision = 2000).copy(state = TelegramLedger.emptyState()
            .put("stickerBalance", 37).put("appliedEventIds", oldIds))
        // A source left untouched since v6 still has its replay map inside JSON.
        db.writableDatabase.execSQL("INSERT INTO family_care_states(room_id,child_id,state) VALUES(?,?,?)",
            arrayOf(source.roomId, source.childId, source.json().toString()))
        val targetRoom = room()
        db.transaction {
            care.copyEventDigests(source.roomId, targetRoom.id, 103)
            val migrated = checkNotNull(care.state(source.roomId, 103))
            care.saveState(migrated.copy(roomId = targetRoom.id, epoch = id(), revision = 0))
        }
        val engine = FamilyCareEngine(TelegramClient("103:${"synthetic_credentials_".repeat(2)}"), care, targetRoom, 103)
        assertTrue(db.transaction { engine.processLegacy(oldAward, 101) })
        assertEquals(37, care.state(targetRoom.id, 103)!!.snapshot.stickerBalance)
        assertEquals(0L, care.stateMetadata(targetRoom.id, 103)!!.revision)
        assertEquals(37, care.state(source.roomId, 103)!!.snapshot.stickerBalance)
        assertEquals(TelegramLedger.eventDigest(oldAward), care.eventDigest(targetRoom.id, 103, oldAward.id))
        assertNull(care.eventDigest(targetRoom.id, 104, oldAward.id))
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM family_care_event_ids WHERE room_id=? AND child_id=103",
            arrayOf(targetRoom.id)).use { it.moveToFirst(); assertEquals(1501, it.getInt(0)) }

        val conflictRoom = id()
        val ownId = id()
        care.recordEventDigest(conflictRoom, 103, ownId, "b".repeat(64))
        care.recordEventDigest(conflictRoom, 103, oldAward.id, "c".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { care.copyEventDigests(source.roomId, conflictRoom, 103) }
        assertEquals("b".repeat(64), care.eventDigest(conflictRoom, 103, ownId))
        assertEquals("c".repeat(64), care.eventDigest(conflictRoom, 103, oldAward.id))
        assertEquals(TelegramLedger.eventDigest(oldAward), care.eventDigest(source.roomId, 103, oldAward.id))
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM family_care_event_ids WHERE room_id=?", arrayOf(conflictRoom)).use {
            it.moveToFirst(); assertEquals("A conflict must copy no partial identity set", 2, it.getInt(0))
        }
        val rolledBackRoom = id()
        assertThrows(IllegalStateException::class.java) { db.transaction {
            care.copyEventDigests(source.roomId, rolledBackRoom, 103); error("authority save failed")
        } }
        assertNull(reopen().familyCare.eventDigest(rolledBackRoom, 103, oldAward.id))
    }

    @Test fun exactAndLimitedQueriesKeepPeerFifoAndCompletedPayloadsRetainImmutableTombstones() {
        val db = store()
        val roomId = id()
        val first = packet(roomId, text = "first large payload")
        val others = List(50) { packet(roomId, text = "queued $it") }
        val care = db.familyCare
        listOf(first, first.copy(peerId = 102)).forEach(care::queuePacket)
        others.forEach(care::queuePacket)
        assertEquals(listOf(first.packetId, others.first().packetId), care.pendingPackets(roomId, 101, 2).map { it.packetId })
        assertEquals(first.packetId, care.pendingPacket(roomId, first.packetId, 102)?.packetId)
        assertNull(care.pendingPacket(roomId, first.packetId, 104))
        assertEquals(setOf(101L, 102L), care.pendingPeers(roomId).toSet())
        assertTrue(care.hasPendingPackets(roomId))
        care.markSent(roomId, first.packetId, 101, 100)
        care.acknowledge(roomId, first.packetId, 101, first.digest)
        assertNull(care.pendingPacket(roomId, first.packetId, 101))
        assertEquals(first.text, care.pendingPacket(roomId, first.packetId, 102)?.text)
        care.markSent(roomId, first.packetId, 102, 101)
        care.acknowledge(roomId, first.packetId, 102, first.digest)
        db.readableDatabase.rawQuery("SELECT text,payload_hash,digest FROM family_care_packets WHERE room_id=? AND id=?",
            arrayOf(roomId, first.packetId)).use {
            assertTrue(it.moveToFirst()); assertEquals("", it.getString(0))
            assertEquals(FamilyChatValidation.digest(first.text), it.getString(1)); assertEquals(first.digest, it.getString(2))
        }
        care.queuePacket(first)
        assertNull(care.pendingPacket(roomId, first.packetId, 101))
        assertThrows(IllegalArgumentException::class.java) { care.queuePacket(first.copy(text = "changed but reused digest")) }
        // An explicitly added recipient restores only the original verified payload.
        care.queuePacket(first.copy(peerId = 104))
        assertEquals(first.text, care.pendingPacket(roomId, first.packetId, 104)?.text)
        val receipts = List(4) { FamilyCareReceipt(roomId, id(), 103, 101, "c".repeat(64)) }
        receipts.forEach(care::queueReceipt)
        care.queueReceipt(FamilyCareReceipt(roomId, id(), 103, 102, "d".repeat(64)))
        assertEquals(receipts.take(2), care.receipts(roomId, 101, 2))
        assertEquals(receipts.first(), care.firstReceipt(roomId, 101))
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

    @Test fun upgradeFromV7AddsDeltaHistoryWithoutChangingAuthorityFinancialReplayIdsOrCursor() {
        val source = state(id(), revision = 27).copy(state = TelegramLedger.emptyState().put("stickerBalance", 51))
        val financialId = id()
        store().familyCare.saveState(source)
        store().familyCare.recordEventDigest(source.roomId, 103, financialId, digest("already approved"))
        store().setMeta("offset", 91823)
        opened?.close(); opened = null
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("DROP TABLE family_care_replacements")
            db.execSQL("DROP TABLE family_care_deltas")
            db.version = 7
        }
        val upgraded = store()
        assertEquals(10, upgraded.readableDatabase.version)
        assertEquals(51, upgraded.familyCare.state(source.roomId, 103)!!.snapshot.stickerBalance)
        assertEquals(FamilyCareStateMetadata(source.epoch, 27, true), upgraded.familyCare.stateMetadata(source.roomId, 103))
        assertEquals(digest("already approved"), upgraded.familyCare.eventDigest(source.roomId, 103, financialId))
        assertEquals(91823L, upgraded.meta("offset"))
        assertTrue(upgraded.familyCare.deltas(source.roomId, 103, source.epoch, 0, 16).isEmpty())
    }

    @Test fun boundedDeltaHistorySurvivesRestartWhileEveryFinancialIdentityAndRollbackRemainIntact() {
        val source = state(id(), revision = 2000).copy(state = TelegramLedger.emptyState().put("stickerBalance", 77))
        val care = store().familyCare
        care.saveState(source)
        val financialIds = mutableListOf<String>()
        care.transaction {
            for (revision in 1L..(FamilyCareDelta.HISTORY_LIMIT + 2).toLong()) {
                val event = FamilyEvent(id(), "sticker_award", JSONObject().put("count", 1).put("reason", "약속"), "guardian", timestamp, "relayed")
                financialIds += event.id
                care.recordEventDigest(source.roomId, 103, event.id, TelegramLedger.eventDigest(event))
                care.saveDelta(FamilyCareDelta(source.roomId, 103, source.epoch, revision - 1, digest("base$revision"),
                    revision, digest("next$revision"), event))
            }
        }
        val retained = reopen().familyCare
        val history = retained.deltas(source.roomId, 103, source.epoch, 0, FamilyCareDelta.HISTORY_LIMIT)
        assertEquals(FamilyCareDelta.HISTORY_LIMIT, history.size)
        assertEquals(3L, history.first().revision)
        assertNotNull(retained.eventDigest(source.roomId, 103, financialIds.first()))
        assertNotNull(retained.eventDigest(source.roomId, 103, financialIds.last()))
        val last = history.last()
        assertThrows(IllegalArgumentException::class.java) { retained.saveDelta(last.copy(resultDigest = digest("changed"))) }
        val checkpoint = store().meta("offset")
        assertThrows(IllegalStateException::class.java) { retained.transaction {
            val event = FamilyEvent(id(), "sticker_award", JSONObject().put("count", 1).put("reason", "추가"), "guardian", timestamp, "relayed")
            retained.saveDelta(last.copy(baseRevision = last.revision, revision = last.revision + 1, event = event))
            retained.saveState(source.copy(revision = 2001, state = JSONObject(source.state.toString()).put("stickerBalance", 78)))
            store().setMeta("offset", 92834)
            error("transaction rollback")
        } }
        assertEquals(checkpoint, store().meta("offset"))
        assertEquals(77, retained.state(source.roomId, 103)!!.snapshot.stickerBalance)
        assertEquals(last.revision, retained.deltas(source.roomId, 103, source.epoch, 0, FamilyCareDelta.HISTORY_LIMIT).last().revision)
    }

    @Test fun financialDeltaDowngradeKeepsOriginalUntilEveryCheckpointPartHasAnAuthenticatedAck() {
        val group = room()
        val initial = state(group.id, revision = 0).apply {
            state.put("rewards", JSONArray((0 until 120).map { JSONObject().put("id", id()).put("name", id()).put("cost", 1) }))
        }
        val event = FamilyEvent(id(), "sticker_award", JSONObject().put("count", 1).put("reason", "약속"), "guardian", timestamp, "relayed")
        val committed = initial.copy(revision = 1, state = TelegramLedger.apply(initial.state, event))
        val delta = FamilyCareDelta.between(initial, committed, event)
        val original = FamilyCareProtocol.outgoing(group, 103, 103, 101, "care_delta", delta.json())
        val chunks = FamilyCareSnapshots.chunks(committed)
        assertTrue(chunks.size > 1)
        val replacements = chunks.map { FamilyCareProtocol.outgoing(group, 103, 103, 101, "snapshot_chunk", FamilyCareSnapshots.chunkJson(it)) }
        var care = store().familyCare
        care.transaction {
            care.saveState(committed); care.saveDelta(delta); care.queuePacket(original); care.markSent(group.id, original.packetId, 101, 1000)
            repeat(16) { care.queuePacket(packet(group.id)) } // The normal unacknowledged window must not strand the compatible checkpoint.
            replacements.forEach(care::queuePacket); care.replacePacket(original, replacements)
        }
        assertFalse(care.pendingPackets(group.id, 101, 16).any { it.packetId == original.packetId })
        assertEquals(replacements.first().packetId, care.pendingPackets(group.id, 101, 16).first().packetId)
        assertNotNull(care.pendingPacket(group.id, original.packetId, 101))
        // An ACK before a persisted send is not accepted and cannot release the original.
        care.acknowledge(group.id, replacements.first().packetId, 101, replacements.first().digest)
        assertNotNull(care.pendingPacket(group.id, original.packetId, 101))
        replacements.dropLast(1).forEach {
            care.markSent(group.id, it.packetId, 101, 2000); care.acknowledge(group.id, it.packetId, 101, it.digest)
        }
        care = reopen().familyCare
        assertTrue(care.hasReplacement(group.id, original.packetId, 101))
        assertNotNull(care.pendingPacket(group.id, original.packetId, 101))
        val final = replacements.last()
        care.markSent(group.id, final.packetId, 101, 3000)
        care.acknowledge(group.id, final.packetId, 101, digest("forged"))
        assertNotNull(care.pendingPacket(group.id, original.packetId, 101))
        care.acknowledge(group.id, final.packetId, 101, final.digest)
        assertNull(care.pendingPacket(group.id, original.packetId, 101))
        assertEquals(TelegramLedger.eventDigest(event), care.eventDigest(group.id, 103, event.id))
        assertEquals(1, care.state(group.id, 103)!!.snapshot.stickerBalance)
        assertEquals(listOf(1L), care.deltas(group.id, 103, committed.epoch, 0, 16).map { it.revision })
    }

    @Test fun telemetryDeltaCompatibilityReplacementPreservesTheExactHistoricLocationAndRevision() {
        val group = room()
        val initial = state(group.id, revision = 0)
        val event = location()
        val committed = initial.copy(revision = 1, state = TelegramLedger.apply(initial.state, event))
        val delta = FamilyCareDelta.between(initial, committed, event)
        val original = FamilyCareProtocol.outgoing(group, 103, 103, 101, "care_delta", delta.json())
        val replacement = FamilyCareProtocol.outgoing(group, 103, 103, 101, "child_event", JSONObject()
            .put("epoch", delta.epoch).put("revision", 1).put("event", event.json()))
        val care = store().familyCare
        care.transaction {
            care.saveState(committed); care.saveDelta(delta); care.archiveEvent(group.id, 103, event)
            care.queuePacket(original); care.queuePacket(replacement)
        }
        val changed = FamilyCareProtocol.outgoing(group, 103, 103, 101, "child_event", JSONObject()
            .put("epoch", delta.epoch).put("revision", 1).put("event", location(latitude = 38.0).json()))
        care.queuePacket(changed)
        assertThrows(IllegalArgumentException::class.java) { care.replacePacket(original, listOf(changed)) }
        assertFalse(care.hasReplacement(group.id, original.packetId, 101))
        care.replacePacket(original, listOf(replacement))
        care.markSent(group.id, replacement.packetId, 101, 2000)
        care.acknowledge(group.id, replacement.packetId, 101, replacement.digest)
        assertNull(care.pendingPacket(group.id, original.packetId, 101))
        val history = reopen().familyCare.movementHistory(group.id, 103, zone = utc)
        assertEquals(event.id, history.events.single().id)
        assertEquals(37.0, history.events.single().payload.getDouble("latitude"), 0.0)
    }

    @Test fun latestLocationCacheSurvivesRestartWithoutAdvancingBoardReplayIdsOrCursorAndRollsBackAtomically() {
        val source = state(id(), revision = 1, authority = false).copy(state = TelegramLedger.emptyState().put("stickerBalance", 77))
        val head = FamilyCareLocationHead(source.roomId, 103, source.epoch, 100, location())
        var care = store().familyCare
        care.saveState(source); store().setMeta("offset", 84921)
        assertTrue(care.saveLatestLocationHead(head)); care.archiveEvent(source.roomId, 103, head.event)
        care = reopen().familyCare
        assertEquals(head.event.id, care.latestLocationHead(source.roomId, 103)!!.event.id)
        assertEquals(1L, care.stateMetadata(source.roomId, 103)!!.revision)
        assertEquals(77, care.state(source.roomId, 103)!!.snapshot.stickerBalance)
        assertNull(care.eventDigest(source.roomId, 103, head.event.id))
        assertEquals(84921L, store().meta("offset"))
        assertFalse(care.saveLatestLocationHead(head.copy(revision = 99, event = location())))
        assertThrows(IllegalArgumentException::class.java) { care.saveLatestLocationHead(head.copy(event = location(latitude = 38.0))) }
        assertFalse(care.saveLatestLocationHead(head.copy(epoch = id(), revision = 101)))
        assertEquals(head.event.id, care.latestLocationHead(source.roomId, 103)!!.event.id)
        val next = head.copy(revision = 101, event = location("2026-09-24T03:01:00Z"))
        assertThrows(IllegalStateException::class.java) { care.transaction {
            care.saveLatestLocationHead(next); care.archiveEvent(source.roomId, 103, next.event)
            store().setMeta("offset", 84922); error("rollback preview")
        } }
        assertEquals(head.event.id, care.latestLocationHead(source.roomId, 103)!!.event.id)
        assertEquals(listOf(head.event.id), care.movementHistory(source.roomId, 103, zone = utc).events.map { it.id })
        assertEquals(84921L, store().meta("offset"))
    }

    @Test fun firstVerifiedBoardPinsProvisionalLocationEpochAndOldBufferedHeadsCannotResurrectIt() {
        val roomId = id(); var care = store().familyCare
        val provisional = FamilyCareLocationHead(roomId, 103, id(), 5, location())
        assertTrue(care.saveLatestLocationHead(provisional))
        assertNotNull(reopen().familyCare.latestLocationHead(roomId, 103))
        care = store().familyCare
        val verified = state(roomId, revision = 0, authority = false)
        care.saveState(verified)
        assertNull(care.latestLocationHead(roomId, 103))
        assertFalse(care.saveLatestLocationHead(provisional.copy(revision = 6)))
        val current = provisional.copy(epoch = verified.epoch, revision = 7, event = location("2026-09-24T03:01:00Z"))
        assertTrue(care.saveLatestLocationHead(current))
        assertEquals(current.event.id, reopen().familyCare.latestLocationHead(roomId, 103)!!.event.id)
        assertEquals(verified.epoch, store().familyCare.stateMetadata(roomId, 103)!!.epoch)
    }

    @Test fun currentLocationSlotsCoalesceAcrossRestartAndOnlyMatchingSentHeadAckClearsThem() {
        val group = room(); val epoch = id(); var care = store().familyCare
        val oldHead = FamilyCareLocationHead(group.id, 103, epoch, 1, location())
        val newHead = oldHead.copy(revision = 2, event = location("2026-09-24T03:01:00Z"))
        val oldPacket = FamilyCareProtocol.outgoing(group, 103, 103, 101, "care_location_head", oldHead.json())
        val currentPacket = FamilyCareProtocol.outgoing(group, 103, 103, 101, "care_location_head", newHead.json())
        val originalHistory = FamilyCareProtocol.outgoing(group, 103, 103, 101, "child_event", oldHead.json())
        care.queuePacket(originalHistory); care.queueLatestLocationHead(oldPacket)
        care.acknowledgeLatestLocationHead(group.id, oldPacket.packetId, 101, oldPacket.digest)
        assertNotNull(care.pendingLatestLocationHead(group.id, oldPacket.packetId, 101))
        care.markLatestLocationSent(group.id, oldPacket.packetId, 101, 1000)
        care.queueLatestLocationHead(currentPacket)
        care = reopen().familyCare
        assertEquals(listOf(currentPacket.packetId), care.pendingLatestLocationHeads(group.id).map { it.packetId })
        assertEquals(1000L, care.latestLocationSentAt(group.id, 101))
        care.acknowledgeLatestLocationHead(group.id, oldPacket.packetId, 101, oldPacket.digest)
        care.acknowledgeLatestLocationHead(group.id, currentPacket.packetId, 101, currentPacket.digest)
        assertNotNull(care.pendingLatestLocationHead(group.id, currentPacket.packetId, 101))
        care.markLatestLocationSent(group.id, currentPacket.packetId, 101, 2000)
        care.acknowledgeLatestLocationHead(group.id, currentPacket.packetId, 101, digest("forged"))
        assertNotNull(care.pendingLatestLocationHead(group.id, currentPacket.packetId, 101))
        care.acknowledgeLatestLocationHead(group.id, currentPacket.packetId, 101, currentPacket.digest)
        assertTrue(care.pendingLatestLocationHeads(group.id).isEmpty())
        assertEquals(listOf(originalHistory.packetId), care.pendingPackets(group.id).map { it.packetId })
        care = reopen().familyCare
        assertEquals(2000L, care.latestLocationSentAt(group.id, 101))
        assertEquals(newHead.eventDigest, care.acknowledgedLatestLocationHead(group.id, 103, 101)!!.eventDigest)
        // Reconstructing an engine or renegotiating capabilities must not resend this stationary point.
        care.queueLatestLocationHead(FamilyCareProtocol.outgoing(group, 103, 103, 101, "care_location_head", newHead.json()))
        care.queueLatestLocationHead(FamilyCareProtocol.outgoing(group, 103, 103, 101, "care_location_head", newHead.copy(revision = 3).json()))
        assertTrue(reopen().familyCare.pendingLatestLocationHeads(group.id).isEmpty())
        assertEquals(newHead.eventDigest, store().familyCare.acknowledgedLatestLocationHead(group.id, 103, 101)!!.eventDigest)
    }

    @Test fun upgradeFromV8KeepsDeltaHistoryOldOutgoingPacketsAndFinancialReplayIds() {
        val group = room(); val before = state(group.id, revision = 0)
        val event = location(); val after = before.copy(revision = 1, state = TelegramLedger.apply(before.state, event))
        val delta = FamilyCareDelta.between(before, after, event)
        val pending = FamilyCareProtocol.outgoing(group, 103, 103, 101, "child_event", JSONObject()
            .put("epoch", after.epoch).put("revision", 1).put("event", event.json()))
        store().familyCare.saveState(after); store().familyCare.saveDelta(delta); store().familyCare.queuePacket(pending)
        store().setMeta("offset", 45123)
        opened?.close(); opened = null
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            listOf("family_care_live", "family_care_live_outgoing", "family_care_live_peer").forEach { db.execSQL("DROP TABLE $it") }
            db.version = 8
        }
        val upgraded = store()
        assertEquals(10, upgraded.readableDatabase.version)
        assertEquals(after.epoch, upgraded.familyCare.stateMetadata(group.id, 103)!!.epoch)
        assertEquals(TelegramLedger.eventDigest(event), upgraded.familyCare.eventDigest(group.id, 103, event.id))
        assertEquals(listOf(1L), upgraded.familyCare.deltas(group.id, 103, after.epoch, 0, 16).map { it.revision })
        assertEquals(listOf(pending.packetId), upgraded.familyCare.pendingPackets(group.id).map { it.packetId })
        assertEquals(45123L, upgraded.meta("offset"))
        assertTrue(upgraded.familyCare.pendingLatestLocationHeads(group.id).isEmpty())
    }

    @Test fun upgradeFromV9IndexesEveryArchivedPointAndKeepsStableSequenceAcrossReplacementAndLateFix() {
        val group = room(); val before = state(group.id, revision = 81)
        val points = (0 until 35).map { location(eventId = id()) }
        store().familyCare.saveState(before)
        points.forEach {
            store().familyCare.archiveEvent(group.id, 103, it.copy(delivery = "queued"))
            store().familyCare.archiveEvent(group.id, 104, location())
        }
        store().setMeta("offset", 77881)
        opened?.close(); opened = null
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            listOf("family_care_history_streams", "family_care_history_log", "family_care_history_progress", "family_care_history_parts")
                .forEach { db.execSQL("DROP TABLE $it") }
            db.version = 9
        }
        var care = store().familyCare
        assertEquals(10, store().readableDatabase.version)
        val source = care.historySource(group.id, 103)
        assertEquals(35L, source.count)
        val first = care.historyEntries(group.id, 103, 0, source.anchor, 24)
        assertEquals(24, first.size)
        care.archiveEvent(group.id, 103, first.first().event.copy(delivery = "relayed"))
        care = reopen().familyCare
        assertEquals(source, care.historySource(group.id, 103))
        assertEquals(first.map { it.sequence }, care.historyEntries(group.id, 103, 0, source.anchor, 24).map { it.sequence })
        val late = location("2026-09-20T01:00:00Z")
        care.archiveEvent(group.id, 103, late)
        val incremental = care.historyEntries(group.id, 103, source.anchor, care.historySource(group.id, 103).anchor, 24)
        assertEquals(listOf(late.id), incremental.map { it.event.id })
        assertEquals(35L, care.historySource(group.id, 103, source.anchor).count)
        assertEquals(36L, care.historySource(group.id, 103).count)
        assertEquals(81L, care.stateMetadata(group.id, 103)!!.revision)
        assertEquals(77881L, store().meta("offset"))
    }

    @Test fun historyManifestCursorAndAcknowledgementAreAtomicAndDoNotTouchFinanceOrLiveHead() {
        val group = room(); val before = state(group.id, revision = 0, authority = false)
        var db = store(); db.familyCare.saveState(before)
        val transfer = id(); val stream = id(); val event = location()
        db.familyCare.saveHistoryProgress(FamilyCareHistoryProgress(group.id, 103, transfer, null, 0, 0, 0, false, 1000))
        fun engine() = FamilyCareEngine(TelegramClient("101:${"synthetic_credentials_".repeat(2)}"), store().familyCare, group, 101, canSyncHistory = { true })
        val entries = listOf(FamilyCareHistoryEntry(1, event))
        val manifest = JSONArray().put(JSONObject().put("sequence", 1).put("eventId", event.id))
        val marker = FamilyCareProtocol.envelope(group, 103, 103, "history_checkpoint", JSONObject()
            .put("transferId", transfer).put("streamId", stream).put("previous", 0).put("cursor", 1).put("anchor", 1)
            .put("count", 1).put("done", true).put("entries", manifest).put("digest", FamilyCareHistorySync.digest(entries)))
        db.transaction { engine().processUpdate(incoming(103, marker.toString())) }
        assertEquals(0L, db.familyCare.historyProgress(group.id, 103)!!.cursor)
        assertNull(db.familyCare.receivedDigest(group.id, marker.getString("id")))
        val record = FamilyCareProtocol.envelope(group, 103, 103, "history_event", JSONObject()
            .put("transferId", transfer).put("streamId", stream).put("sequence", 1).put("event", event.json()))
        db.transaction { engine().processUpdate(incoming(103, record.toString())) }
        try { db.transaction {
            engine().processUpdate(incoming(103, marker.toString())); db.setMeta("offset", 91); error("rollback")
        }; fail() } catch (_: IllegalStateException) { }
        db = reopen()
        assertEquals(0L, db.familyCare.historyProgress(group.id, 103)!!.cursor)
        assertEquals(0L, db.meta("offset"))
        assertNull(db.familyCare.receivedDigest(group.id, marker.getString("id")))
        db.transaction { engine().processUpdate(incoming(103, marker.toString())); db.setMeta("offset", 92) }
        db = reopen()
        assertEquals(1L, db.familyCare.historyProgress(group.id, 103)!!.cursor)
        assertTrue(db.familyCare.historyProgress(group.id, 103)!!.complete)
        assertTrue(db.familyCare.receipts(group.id).any { it.packetId == marker.getString("id") })
        assertEquals(before.epoch, db.familyCare.stateMetadata(group.id, 103)!!.epoch)
        assertEquals(0L, db.familyCare.stateMetadata(group.id, 103)!!.revision)
        assertNull(db.familyCare.eventDigest(group.id, 103, event.id))
        assertNull(db.familyCare.latestLocationHead(group.id, 103))
        assertEquals(92L, db.meta("offset"))
    }

    @Test fun equalSnapshotAndGappedTelemetryDeltaArchiveBeforeAcknowledgementWithoutAdvancingFinance() {
        val group = room(); val points = (0 until 12).map { location() }
        val replica = state(group.id, revision = 0, authority = false).copy(state = TelegramLedger.emptyState()
            .put("stickerBalance", 7).put("careRewardVersions", JSONObject()).put("events", JSONArray(points.map { it.json() })))
        val care = store().familyCare; care.saveState(replica)
        val engine = FamilyCareEngine(TelegramClient("101:${"synthetic_credentials_".repeat(2)}"), care, group, 101)
        FamilyCareSnapshots.chunks(replica.copy(authoritative = true)).forEach { chunk -> store().transaction {
            engine.processUpdate(incoming(103, FamilyCareProtocol.envelope(group, 103, 103, "snapshot_chunk",
                FamilyCareSnapshots.chunkJson(chunk)).toString()))
        } }
        assertEquals(12, care.movementHistory(group.id, 103, zone = utc).events.size)
        val old = replica.copy(authoritative = true, revision = 3)
        val missing = location("2026-09-24T03:02:00Z")
        val changed = old.copy(revision = 4, state = TelegramLedger.apply(old.state, missing))
        val delta = FamilyCareDelta.between(old, changed, missing)
        val envelope = FamilyCareProtocol.envelope(group, 103, 103, "care_delta", delta.json())
        store().transaction { engine.processUpdate(incoming(103, envelope.toString())) }
        assertEquals(13, care.movementHistory(group.id, 103, zone = utc).events.size)
        assertEquals(0L, care.stateMetadata(group.id, 103)!!.revision)
        assertEquals(7, care.state(group.id, 103)!!.snapshot.stickerBalance)
        assertNull(care.eventDigest(group.id, 103, missing.id))
        assertTrue(care.receipts(group.id).any { it.packetId == envelope.getString("id") })
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
