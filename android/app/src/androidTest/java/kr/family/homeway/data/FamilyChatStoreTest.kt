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
import java.time.ZoneId
import java.util.UUID

/** Every case owns a random test database; never reads or changes the installed family's database. */
@RunWith(AndroidJUnit4::class)
class FamilyChatStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String
    private var opened: LocalStore? = null
    private val timestamp = "2026-09-24T03:00:00Z"
    @Before fun prepare() { databaseName = "family-chat-test-${UUID.randomUUID()}.db" }
    @After fun cleanUp() { opened?.close(); context.deleteDatabase(databaseName) }
    private fun store(): LocalStore = opened ?: LocalStore(context, databaseName).also { opened = it }
    private fun reopen(): LocalStore { opened?.close(); opened = null; return store() }
    private fun id() = UUID.randomUUID().toString()
    private fun room() = FamilyChatRoom(id(), "우리 가족", listOf(
        FamilyChatMember(101, "@test_mother_bot", "엄마", "mother"),
        FamilyChatMember(102, "@test_father_bot", "아빠", "father"),
        FamilyChatMember(103, "@test_son_bot", "아들", "son"),
        FamilyChatMember(104, "@test_daughter_bot", "딸", "daughter")))
    private fun message(roomId: String, sender: Long = 101, at: String = timestamp) =
        FamilyChatMessage(id(), roomId, sender, "가족 메시지", at)
    private fun privateChat(delivery: String = "relayed") = FamilyEvent(id(), "chat", JSONObject().put("text", "예전 대화"),
        "child", timestamp, delivery)
    private fun state(vararg events: FamilyEvent) = TelegramLedger.emptyState().put("events", JSONArray(events.map { it.json() }))

    @Test fun upgradeFromV3RetainsPairingLedgerMovementOutboxAndReceiveCursorAndBackfillsPrivateChat() {
        val retained = privateChat()
        val queued = privateChat("pending")
        val position = FamilyEvent(id(), "location", JSONObject().put("latitude", 37.0).put("longitude", 127.0)
            .put("accuracy", 15).put("source", "automatic").put("capturedAt", timestamp), "child", timestamp, "relayed")
        val cached = state(retained, position).put("stickerBalance", 14)
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
            db.execSQL("INSERT INTO cache(id,state) VALUES(1,?)", arrayOf(cached.toString()))
            db.execSQL("INSERT INTO outbox(id,event) VALUES(?,?)", arrayOf(queued.id, queued.json().toString()))
            db.execSQL("INSERT INTO telegram_meta(name,value) VALUES('offset',8492)")
            db.execSQL("INSERT INTO telegram_meta(name,value) VALUES('sentAt',129)")
            db.execSQL("INSERT INTO telegram_receipts(id) VALUES(?)", arrayOf(retained.id))
            db.execSQL("INSERT INTO movement_history(id,measured_at,local_day,event) VALUES(?,?,?,?)", arrayOf(position.id,
                java.time.Instant.parse(timestamp).toEpochMilli(), "2026-09-24", position.json().toString()))
            db.version = 3
        }
        val migrated = store()
        assertEquals(4, migrated.readableDatabase.version)
        assertEquals(cached.toString(), migrated.cached()!!.toString())
        assertEquals(8492L, migrated.meta("offset"))
        assertEquals(129L, migrated.meta("sentAt"))
        assertEquals(listOf(retained.id), migrated.receipts())
        assertEquals(listOf(queued.id), migrated.pending().map { it.id })
        assertEquals(listOf(position.id), migrated.movementHistory("2026-09-24", zone = ZoneId.of("UTC")).events.map { it.id })
        assertEquals(setOf(retained.id, queued.id), migrated.privateChatHistory().events.map { it.id }.toSet())
        assertEquals("queued", migrated.privateChatHistory().events.single { it.id == queued.id }.delivery)
        assertNull(migrated.familyChat.activeRoom())
        assertTrue(migrated.familyChat.chatHistory(room().id).messages.isEmpty())
    }

    @Test fun perRecipientProgressSurvivesRestartAndOfflinePeerNeverCompletesAnotherRecipient() {
        val group = room()
        val outgoing = message(group.id)
        val db = store()
        db.familyChat.setActiveRoom(group)
        db.familyChat.insertChatMessage(outgoing, listOf(102, 103, 104))
        db.familyChat.acknowledgeChat(group.id, outgoing.id, 102) // Unsent ACK is not accepted.
        assertEquals(0, db.familyChat.chatMessage(group.id, outgoing.id)!!.deliveredCount)
        listOf(102L, 103L, 104L).forEach { db.familyChat.markChatSent(group.id, outgoing.id, it, 1000) }
        db.familyChat.acknowledgeChat(group.id, outgoing.id, 102)
        db.familyChat.setChatPeerRetryAfter(group.id, 103, 50_000)
        val resumed = reopen().familyChat
        assertEquals(group, resumed.activeRoom())
        assertEquals(setOf(103L, 104L), resumed.pendingChatDeliveries(group.id).map { it.peerId }.toSet())
        assertTrue(resumed.pendingChatDeliveries(group.id).all { it.sentAt == 1000L })
        assertEquals(3, resumed.chatMessage(group.id, outgoing.id)!!.recipientCount)
        assertEquals(1, resumed.chatMessage(group.id, outgoing.id)!!.deliveredCount)
        assertEquals(50_000L, resumed.chatPeerRetryAfter(group.id, 103))
        assertEquals(0L, resumed.chatPeerRetryAfter(group.id, 104))
        resumed.insertChatMessage(outgoing, listOf(104, 102, 103)) // Retry cannot requeue the completed parent.
        assertEquals(setOf(103L, 104L), resumed.pendingChatDeliveries(group.id).map { it.peerId }.toSet())
    }

    @Test fun immutableMessageIdentityAndFixedRecipientsRejectConflictingRetry() {
        val group = room()
        val original = message(group.id)
        val chat = store().familyChat
        chat.insertChatMessage(original, listOf(102, 103, 104))
        assertThrows(IllegalArgumentException::class.java) { chat.insertChatMessage(original.copy(text = "바뀐 내용"), listOf(102, 103, 104)) }
        assertThrows(IllegalArgumentException::class.java) { chat.insertChatMessage(original, listOf(102, 103)) }
        assertEquals(original.text, chat.chatMessage(group.id, original.id)!!.text)
        assertEquals(3, chat.pendingChatDeliveries(group.id).size)
        assertEquals(1, chat.chatHistory(group.id).messages.size)
    }

    @Test fun deliveryUsesInsertionFifoEvenWhenDeviceClockMovesBackward() {
        val group = room()
        val first = message(group.id)
        val second = message(group.id, at = "2026-09-24T02:59:00Z")
        val chat = store().familyChat
        chat.insertChatMessage(first, listOf(102, 103, 104))
        chat.insertChatMessage(second, listOf(102, 103, 104))
        assertEquals(listOf(first.id, second.id), chat.pendingChatDeliveries(group.id).filter { it.peerId == 102L }.map { it.messageId })
        chat.markChatSent(group.id, first.id, 102, 1000)
        chat.acknowledgeChat(group.id, first.id, 102)
        assertEquals(listOf(second.id), chat.pendingChatDeliveries(group.id).filter { it.peerId == 102L }.map { it.messageId })
        assertEquals(listOf(first.id, second.id), chat.pendingChatDeliveries(group.id).filter { it.peerId == 103L }.map { it.messageId })
    }

    @Test fun receivedMessageReceiptAndTelegramCursorCommitOrRollbackTogether() {
        val first = room()
        val replacement = room()
        val db = store()
        db.familyChat.setActiveRoom(first)
        db.setMeta("offset", 71)
        val received = message(first.id, 102)
        val receipt = FamilyChatReceipt(first.id, received.id, 102, received.digest)
        assertThrows(IllegalStateException::class.java) {
            db.transaction {
                db.familyChat.setActiveRoom(replacement)
                db.familyChat.insertChatMessage(received, emptyList())
                db.familyChat.queueChatReceipt(receipt)
                db.setMeta("offset", 72)
                error("failure before atomic receive commit")
            }
        }
        assertEquals(first, db.familyChat.activeRoom())
        assertEquals(71L, db.meta("offset"))
        assertNull(db.familyChat.chatMessage(first.id, received.id))
        assertTrue(db.familyChat.chatReceipts(first.id).isEmpty())
        db.transaction {
            db.familyChat.insertChatMessage(received, emptyList())
            db.familyChat.queueChatReceipt(receipt)
            db.setMeta("offset", 72)
        }
        val resumed = reopen()
        assertEquals(received, resumed.familyChat.chatMessage(first.id, received.id))
        assertEquals(listOf(receipt), resumed.familyChat.chatReceipts(first.id))
        assertEquals(72L, resumed.meta("offset"))
        resumed.familyChat.queueChatReceipt(receipt)
        assertEquals(1, resumed.familyChat.chatReceipts(first.id).size)
        resumed.familyChat.removeChatReceipt(receipt.copy(digest = "wrong"))
        assertEquals(1, resumed.familyChat.chatReceipts(first.id).size)
        resumed.familyChat.removeChatReceipt(receipt)
        assertTrue(resumed.familyChat.chatReceipts(first.id).isEmpty())
    }

    @Test fun stableRoomPagesRetainFullArchiveAndPrecisionWithoutOtherRoomLeakage() {
        val group = room()
        val other = room()
        val chat = store().familyChat
        val sameTime = List(203) { message(group.id) }
        val precise = message(group.id, at = "2026-09-24T03:00:00.000000001Z")
        chat.transaction {
            sameTime.forEach { chat.insertChatMessage(it, emptyList()) }
            chat.insertChatMessage(precise, emptyList())
            chat.insertChatMessage(message(other.id, at = "2026-09-25T03:00:00Z"), emptyList())
        }
        val first = chat.chatHistory(group.id)
        val second = chat.chatHistory(group.id, first.next)
        assertEquals(200, first.messages.size)
        assertEquals(4, second.messages.size)
        assertEquals(precise.id, first.messages.first().id)
        assertEquals(listOf(precise.id) + sameTime.map { it.id }.sortedDescending(), (first.messages + second.messages).map { it.id })
        assertNotNull(first.next)
        assertNull(second.next)
        assertTrue((first.messages + second.messages).all { it.roomId == group.id })
    }

    @Test fun activeRoomSwitchKeepsOlderArchiveAndQueuesAndRejectsIdentityReuseWithChangedMembers() {
        val oldRoom = room()
        val newRoom = room()
        val oldMessage = message(oldRoom.id)
        val newMessage = message(newRoom.id)
        val chat = store().familyChat
        chat.setActiveRoom(oldRoom)
        chat.insertChatMessage(oldMessage, listOf(102, 103, 104))
        chat.setActiveRoom(newRoom)
        chat.insertChatMessage(newMessage, listOf(102, 103, 104))
        assertEquals(newRoom, chat.activeRoom())
        assertEquals(listOf(newMessage.id), chat.chatHistory(newRoom.id).messages.map { it.id })
        assertEquals(listOf(oldMessage.id), chat.chatHistory(oldRoom.id).messages.map { it.id })
        assertTrue(chat.pendingChatDeliveries(newRoom.id).all { it.messageId == newMessage.id })
        assertThrows(IllegalArgumentException::class.java) {
            chat.setActiveRoom(oldRoom.copy(members = oldRoom.members.dropLast(1)))
        }
        assertEquals(newRoom, chat.activeRoom())
        chat.setActiveRoom(null)
        assertNull(chat.activeRoom())
        assertEquals(3, chat.pendingChatDeliveries(oldRoom.id).size)
        chat.setActiveRoom(oldRoom)
        assertEquals(oldRoom, chat.activeRoom())
    }

    @Test fun privateChatArchiveSurvivesLedgerTrimmingAndUsesStableAscendingPagesAndCurrentOutboxStatus() {
        val entries = List(205) { privateChat() }
        val pending = privateChat("pending")
        val db = store()
        db.cache(state(*entries.toTypedArray()))
        db.enqueue(pending)
        db.fail(pending.id, "연결을 확인해 주세요.")
        db.cache(state())
        val first = db.privateChatHistory()
        val second = db.privateChatHistory(first.nextCursor)
        assertEquals(200, first.events.size)
        assertEquals(6, second.events.size)
        val all = second.events + first.events
        assertEquals((entries + pending).map { it.id }.sorted(), all.map { it.id })
        assertEquals("failed", all.single { it.id == pending.id }.delivery)
        assertEquals("연결을 확인해 주세요.", all.single { it.id == pending.id }.deliveryError)
        assertNull(second.nextCursor)
        assertTrue(FamilySnapshot.parse(db.cached()!!).events.isEmpty())
        db.clear()
        assertTrue(db.privateChatHistory().events.isEmpty())
    }

    @Test fun explicitResetClearsRoomConfigurationArchiveReceiptsAndPerPeerState() {
        val group = room()
        val outgoing = message(group.id)
        val incoming = message(group.id, 102)
        val db = store()
        db.familyChat.setActiveRoom(group)
        db.familyChat.insertChatMessage(outgoing, listOf(102, 103, 104))
        db.familyChat.insertChatMessage(incoming, emptyList())
        db.familyChat.queueChatReceipt(FamilyChatReceipt(group.id, incoming.id, 102, incoming.digest))
        db.familyChat.setChatPeerRetryAfter(group.id, 104, 6000)
        db.transaction { db.clear() }
        assertNull(db.familyChat.activeRoom())
        assertTrue(db.familyChat.chatHistory(group.id).messages.isEmpty())
        assertTrue(db.familyChat.pendingChatDeliveries(group.id).isEmpty())
        assertTrue(db.familyChat.chatReceipts(group.id).isEmpty())
        assertEquals(0L, db.familyChat.chatPeerRetryAfter(group.id, 104))
    }
}
