package kr.family.homeway.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.time.Instant

/** Room traffic shares the private exchange's database transaction and Telegram receive cursor. */
class SqliteFamilyChatStore internal constructor(private val owner: LocalStore) : FamilyChatStore {
    override fun <T> transaction(block: () -> T): T = owner.transaction(block)

    fun activeRoom(): FamilyChatRoom? = owner.readableDatabase.rawQuery(
        "SELECT r.configuration FROM family_chat_rooms r JOIN family_chat_active a ON a.room_id=r.id WHERE a.id=1", null
    ).use { rows -> if (rows.moveToFirst()) FamilyChatRoom.parse(JSONObject(rows.getString(0))) else null }

    /** A room identity fixes its member list; a changed family list creates a new room identity. */
    fun setActiveRoom(room: FamilyChatRoom?) = transaction {
        val db = owner.writableDatabase
        if (room == null) {
            db.delete("family_chat_active", "id=1", null)
        } else {
            val validated = FamilyChatValidation.room(room).let { it.copy(members = it.members.sortedBy { member -> member.botId }) }
            val existing = db.rawQuery("SELECT configuration FROM family_chat_rooms WHERE id=?", arrayOf(validated.id)).use {
                if (it.moveToFirst()) FamilyChatRoom.parse(JSONObject(it.getString(0))) else null
            }
            require(existing == null || existing == validated) { "가족 명단이 달라요. 새 가족방 코드를 만들어 주세요." }
            db.insertWithOnConflict("family_chat_rooms", null, ContentValues().apply {
                put("id", validated.id); put("configuration", validated.json().toString())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.insertWithOnConflict("family_chat_active", null, ContentValues().apply {
                put("id", 1); put("room_id", validated.id)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        Unit
    }

    override fun chatMessage(roomId: String, messageId: String): FamilyChatMessage? = owner.readableDatabase.rawQuery(
        "$messageProjection WHERE m.room_id=? AND m.id=?", arrayOf(roomId, messageId)
    ).use { if (it.moveToFirst()) readMessage(it) else null }

    override fun insertChatMessage(message: FamilyChatMessage, recipients: List<Long>) = transaction {
        val validated = FamilyChatValidation.message(message)
        val peers = recipients.map { FamilyChatValidation.botId(it) }
        require(peers.size <= 7 && peers.distinct().size == peers.size && validated.senderId !in peers) {
            "메시지를 받을 가족을 확인해 주세요."
        }
        val db = owner.writableDatabase
        val existingDigest = db.rawQuery("SELECT digest FROM family_chat_messages WHERE room_id=? AND id=?",
            arrayOf(validated.roomId, validated.id)).use { if (it.moveToFirst()) it.getString(0) else null }
        if (existingDigest != null) {
            require(existingDigest == validated.digest) { "같은 가족 메시지의 내용이 달라졌어요." }
            val savedPeers = db.rawQuery("SELECT peer_id FROM family_chat_deliveries WHERE room_id=? AND message_id=?",
                arrayOf(validated.roomId, validated.id)).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getLong(0)) } }
            require(savedPeers == peers.toSet()) { "이미 보낸 메시지의 수신 가족은 바꿀 수 없어요." }
        } else {
            db.insertOrThrow("family_chat_messages", null, ContentValues().apply {
                put("room_id", validated.roomId); put("id", validated.id)
                put("created_at", sortTime(validated.createdAt))
                put("message", validated.json().toString()); put("digest", validated.digest)
            })
            peers.forEach { peerId ->
                db.insertOrThrow("family_chat_deliveries", null, ContentValues().apply {
                    put("room_id", validated.roomId); put("message_id", validated.id); put("peer_id", peerId)
                    put("sent_at", 0L); put("completed", 0)
                })
            }
        }
        Unit
    }

    override fun pendingChatDeliveries(roomId: String): List<FamilyChatDelivery> = owner.readableDatabase.rawQuery(
        "SELECT d.message_id,d.peer_id,d.sent_at FROM family_chat_deliveries d " +
            "JOIN family_chat_messages m ON m.room_id=d.room_id AND m.id=d.message_id " +
            "WHERE d.room_id=? AND d.completed=0 ORDER BY m.rowid,d.peer_id", arrayOf(roomId)
    ).use { rows -> buildList { while (rows.moveToNext()) add(FamilyChatDelivery(roomId, rows.getString(0), rows.getLong(1), rows.getLong(2))) } }

    override fun markChatSent(roomId: String, messageId: String, peerId: Long, sentAt: Long) {
        require(sentAt > 0)
        owner.writableDatabase.update("family_chat_deliveries", ContentValues().apply { put("sent_at", sentAt) },
            "room_id=? AND message_id=? AND peer_id=? AND completed=0", arrayOf(roomId, messageId, peerId.toString()))
    }

    override fun acknowledgeChat(roomId: String, messageId: String, peerId: Long) {
        owner.writableDatabase.update("family_chat_deliveries", ContentValues().apply { put("completed", 1) },
            "room_id=? AND message_id=? AND peer_id=? AND sent_at>0 AND completed=0", arrayOf(roomId, messageId, peerId.toString()))
    }

    override fun queueChatReceipt(receipt: FamilyChatReceipt) = transaction {
        val saved = chatMessage(receipt.roomId, receipt.messageId)
        require(saved != null && saved.digest == receipt.digest && saved.senderId == receipt.peerId) {
            "가족 메시지의 수신 확인 정보가 달라요."
        }
        owner.writableDatabase.insertWithOnConflict("family_chat_receipts", null, ContentValues().apply {
            put("room_id", receipt.roomId); put("message_id", receipt.messageId)
            put("peer_id", receipt.peerId); put("digest", receipt.digest)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        Unit
    }

    override fun chatReceipts(roomId: String): List<FamilyChatReceipt> = owner.readableDatabase.rawQuery(
        "SELECT message_id,peer_id,digest FROM family_chat_receipts WHERE room_id=? ORDER BY rowid", arrayOf(roomId)
    ).use { rows -> buildList { while (rows.moveToNext()) add(FamilyChatReceipt(roomId, rows.getString(0), rows.getLong(1), rows.getString(2))) } }

    override fun removeChatReceipt(receipt: FamilyChatReceipt) {
        owner.writableDatabase.delete("family_chat_receipts", "room_id=? AND message_id=? AND peer_id=? AND digest=?",
            arrayOf(receipt.roomId, receipt.messageId, receipt.peerId.toString(), receipt.digest))
    }

    override fun chatPeerRetryAfter(roomId: String, peerId: Long): Long = owner.readableDatabase.rawQuery(
        "SELECT retry_after FROM family_chat_peer_state WHERE room_id=? AND peer_id=?", arrayOf(roomId, peerId.toString())
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    override fun setChatPeerRetryAfter(roomId: String, peerId: Long, retryAfter: Long) {
        require(retryAfter >= 0)
        owner.writableDatabase.insertWithOnConflict("family_chat_peer_state", null, ContentValues().apply {
            put("room_id", roomId); put("peer_id", peerId); put("retry_after", retryAfter)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun chatHistory(roomId: String, before: FamilyChatCursor?, limit: Int): FamilyChatPage {
        require(limit in 1..1000)
        val args = mutableListOf(roomId)
        val condition = if (before == null) "" else {
            val beforeTime = sortTime(before.createdAt).toString()
            args.add(beforeTime); args.add(beforeTime); args.add(before.id)
            " AND m.created_at<=? AND (m.created_at<? OR m.id<?)"
        }
        val messages = owner.readableDatabase.rawQuery(
            "$messageProjection WHERE m.room_id=?$condition ORDER BY m.created_at DESC,m.id DESC LIMIT ${limit + 1}", args.toTypedArray()
        ).use { rows -> buildList { while (rows.moveToNext()) add(readMessage(rows)) } }
        val page = messages.take(limit)
        return FamilyChatPage(page, if (messages.size > limit) page.last().let { FamilyChatCursor(it.createdAt, it.id) } else null)
    }

    private fun readMessage(rows: Cursor): FamilyChatMessage = FamilyChatMessage.parse(JSONObject(rows.getString(0)))
        .copy(recipientCount = rows.getInt(1), deliveredCount = rows.getInt(2))

    companion object {
        // The validated 2000..2100 message range fits nanoseconds in a signed SQLite integer.
        // Retain fractional precision so distinct instants never become a cursor tie accidentally.
        private fun sortTime(createdAt: String): Long = Instant.parse(createdAt).let {
            Math.addExact(Math.multiplyExact(it.epochSecond, 1_000_000_000L), it.nano.toLong())
        }
        private const val messageProjection = "SELECT m.message," +
            "(SELECT COUNT(*) FROM family_chat_deliveries d WHERE d.room_id=m.room_id AND d.message_id=m.id)," +
            "(SELECT COUNT(*) FROM family_chat_deliveries d WHERE d.room_id=m.room_id AND d.message_id=m.id AND d.completed=1) " +
            "FROM family_chat_messages m"

        internal fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE family_chat_rooms (id TEXT PRIMARY KEY,configuration TEXT NOT NULL)")
            db.execSQL("CREATE TABLE family_chat_active (id INTEGER PRIMARY KEY CHECK(id=1),room_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE family_chat_messages (room_id TEXT NOT NULL,id TEXT NOT NULL,created_at INTEGER NOT NULL," +
                "message TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(room_id,id))")
            db.execSQL("CREATE INDEX family_chat_message_time ON family_chat_messages(room_id,created_at DESC,id DESC)")
            db.execSQL("CREATE TABLE family_chat_deliveries (room_id TEXT NOT NULL,message_id TEXT NOT NULL,peer_id INTEGER NOT NULL," +
                "sent_at INTEGER NOT NULL DEFAULT 0,completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN (0,1))," +
                "PRIMARY KEY(room_id,message_id,peer_id))")
            db.execSQL("CREATE INDEX family_chat_delivery_pending ON family_chat_deliveries(room_id,completed,message_id,peer_id)")
            db.execSQL("CREATE TABLE family_chat_receipts (room_id TEXT NOT NULL,message_id TEXT NOT NULL,peer_id INTEGER NOT NULL,digest TEXT NOT NULL," +
                "PRIMARY KEY(room_id,message_id,peer_id))")
            db.execSQL("CREATE TABLE family_chat_peer_state (room_id TEXT NOT NULL,peer_id INTEGER NOT NULL,retry_after INTEGER NOT NULL," +
                "PRIMARY KEY(room_id,peer_id))")
        }

        internal fun clearTables(db: SQLiteDatabase) {
            listOf("family_chat_active", "family_chat_rooms", "family_chat_deliveries", "family_chat_messages",
                "family_chat_receipts", "family_chat_peer_state").forEach { db.delete(it, null, null) }
        }
    }
}
