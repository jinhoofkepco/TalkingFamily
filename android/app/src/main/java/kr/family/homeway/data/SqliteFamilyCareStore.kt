package kr.family.homeway.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Family care shares the chat/private exchange transaction and never combines different children. */
class SqliteFamilyCareStore internal constructor(private val owner: LocalStore) : FamilyCareStore {
    override fun <T> transaction(block: () -> T): T = owner.transaction(block)

    override fun state(roomId: String, childId: Long): FamilyCareState? = owner.readableDatabase.rawQuery(
        "SELECT state FROM family_care_states WHERE room_id=? AND child_id=?", arrayOf(roomId, childId.toString())
    ).use { if (it.moveToFirst()) FamilyCareState.parse(JSONObject(it.getString(0))) else null }

    override fun saveState(state: FamilyCareState) = transaction {
        val old = this.state(state.roomId, state.childId)
        require(old == null || (old.epoch == state.epoch && old.revision <= state.revision && old.authoritative == state.authoritative)) {
            "자녀의 칭찬판 기준 정보가 달라요. 가족 연결을 확인해 주세요."
        }
        require(old == null || old.revision != state.revision || FamilyCareValidation.digest(old.json()) == FamilyCareValidation.digest(state.json())) {
            "같은 순서의 자녀 칭찬판 내용이 달라요."
        }
        owner.writableDatabase.insertWithOnConflict("family_care_states", null, ContentValues().apply {
            put("room_id", state.roomId); put("child_id", state.childId); put("state", state.json().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override fun outcome(roomId: String, commandId: String): FamilyCareOutcome? = owner.readableDatabase.rawQuery(
        "SELECT outcome FROM family_care_outcomes WHERE room_id=? AND command_id=?", arrayOf(roomId, commandId)
    ).use { if (it.moveToFirst()) FamilyCareOutcome.parse(JSONObject(it.getString(0))) else null }

    override fun saveOutcome(outcome: FamilyCareOutcome) = transaction {
        val old = this.outcome(outcome.roomId, outcome.commandId)
        require(old == null || old == outcome) { "같은 칭찬판 요청의 처리 결과가 달라요." }
        owner.writableDatabase.insertWithOnConflict("family_care_outcomes", null, ContentValues().apply {
            put("room_id", outcome.roomId); put("child_id", outcome.childId); put("command_id", outcome.commandId)
            put("outcome", outcome.json().toString())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        Unit
    }

    override fun outcomes(roomId: String, childId: Long): List<FamilyCareOutcome> = owner.readableDatabase.rawQuery(
        "SELECT outcome FROM family_care_outcomes WHERE room_id=? AND child_id=? ORDER BY rowid", arrayOf(roomId, childId.toString())
    ).use { rows -> buildList { while (rows.moveToNext()) add(FamilyCareOutcome.parse(JSONObject(rows.getString(0)))) } }

    override fun saveCommand(command: FamilyCareCommand) = transaction {
        val db = owner.writableDatabase
        val old = db.rawQuery("SELECT digest FROM family_care_commands WHERE room_id=? AND id=?",
            arrayOf(command.roomId, command.id)).use { if (it.moveToFirst()) it.getString(0) else null }
        require(old == null || old == command.digest) { "같은 칭찬판 요청의 내용이 달라요." }
        db.insertWithOnConflict("family_care_commands", null, ContentValues().apply {
            put("room_id", command.roomId); put("child_id", command.childId); put("id", command.id)
            put("command", command.json().toString()); put("digest", command.digest)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        Unit
    }

    override fun commands(roomId: String, childId: Long): List<FamilyCareCommand> = owner.readableDatabase.rawQuery(
        "SELECT command FROM family_care_commands WHERE room_id=? AND child_id=? ORDER BY rowid", arrayOf(roomId, childId.toString())
    ).use { rows -> buildList { while (rows.moveToNext()) add(FamilyCareCommand.parse(JSONObject(rows.getString(0)))) } }

    override fun receivedDigest(roomId: String, packetId: String): String? = owner.readableDatabase.rawQuery(
        "SELECT digest FROM family_care_received WHERE room_id=? AND packet_id=?", arrayOf(roomId, packetId)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun recordReceived(roomId: String, packetId: String, digest: String) = transaction {
        val old = receivedDigest(roomId, packetId)
        require(old == null || old == digest) { "같은 가족 정보의 내용이 달라요." }
        owner.writableDatabase.insertWithOnConflict("family_care_received", null, ContentValues().apply {
            put("room_id", roomId); put("packet_id", packetId); put("digest", digest)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        Unit
    }

    override fun queuePacket(packet: FamilyCareOutgoing) = transaction {
        val db = owner.writableDatabase
        require(packet.peerId > 0 && packet.childId > 0 && packet.sentAt >= 0 && (!packet.sendConfirmed || packet.sentAt > 0))
        val old = db.rawQuery("SELECT child_id,text,digest FROM family_care_packets WHERE room_id=? AND id=?",
            arrayOf(packet.roomId, packet.packetId)).use { if (it.moveToFirst()) Triple(it.getLong(0), it.getString(1), it.getString(2)) else null }
        require(old == null || old == Triple(packet.childId, packet.text, packet.digest)) {
            "이미 보낸 가족 정보의 내용은 바꿀 수 없어요."
        }
        db.insertWithOnConflict("family_care_packets", null, ContentValues().apply {
            put("room_id", packet.roomId); put("id", packet.packetId); put("child_id", packet.childId)
            put("text", packet.text); put("digest", packet.digest)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        db.insertWithOnConflict("family_care_deliveries", null, ContentValues().apply {
            put("room_id", packet.roomId); put("packet_id", packet.packetId); put("peer_id", packet.peerId)
            put("sent_at", packet.sentAt); put("send_confirmed", if (packet.sendConfirmed) 1 else 0); put("completed", 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        Unit
    }

    override fun pendingPackets(roomId: String): List<FamilyCareOutgoing> = owner.readableDatabase.rawQuery(
        "SELECT p.id,p.child_id,d.peer_id,p.text,p.digest,d.sent_at,d.send_confirmed FROM family_care_packets p " +
            "JOIN family_care_deliveries d ON d.room_id=p.room_id AND d.packet_id=p.id " +
            "WHERE p.room_id=? AND d.completed=0 ORDER BY p.rowid,d.peer_id", arrayOf(roomId)
    ).use { rows -> buildList { while (rows.moveToNext()) add(FamilyCareOutgoing(roomId, rows.getString(0),
        rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getString(4), rows.getLong(5), rows.getInt(6) == 1)) } }

    override fun markSent(roomId: String, packetId: String, peerId: Long, sentAt: Long) {
        require(sentAt > 0)
        owner.writableDatabase.update("family_care_deliveries", ContentValues().apply {
            put("sent_at", sentAt); put("send_confirmed", 0)
        },
            "room_id=? AND packet_id=? AND peer_id=? AND completed=0", arrayOf(roomId, packetId, peerId.toString()))
    }

    override fun markSendConfirmed(roomId: String, packetId: String, peerId: Long) {
        owner.writableDatabase.update("family_care_deliveries", ContentValues().apply { put("send_confirmed", 1) },
            "room_id=? AND packet_id=? AND peer_id=? AND sent_at>0 AND completed=0", arrayOf(roomId, packetId, peerId.toString()))
    }

    override fun acknowledge(roomId: String, packetId: String, peerId: Long, digest: String) {
        owner.writableDatabase.execSQL("UPDATE family_care_deliveries SET completed=1 " +
            "WHERE room_id=? AND packet_id=? AND peer_id=? AND sent_at>0 AND completed=0 " +
            "AND EXISTS(SELECT 1 FROM family_care_packets p WHERE p.room_id=? AND p.id=? AND p.digest=?)",
            arrayOf(roomId, packetId, peerId, roomId, packetId, digest))
    }

    override fun queueReceipt(receipt: FamilyCareReceipt) = transaction {
        val db = owner.writableDatabase
        val old = db.rawQuery("SELECT child_id,digest FROM family_care_receipts WHERE room_id=? AND packet_id=? AND peer_id=?",
            arrayOf(receipt.roomId, receipt.packetId, receipt.peerId.toString())).use {
            if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null
        }
        require(old == null || old == (receipt.childId to receipt.digest)) { "가족 정보의 수신 확인 내용이 달라요." }
        db.insertWithOnConflict("family_care_receipts", null, ContentValues().apply {
            put("room_id", receipt.roomId); put("packet_id", receipt.packetId); put("child_id", receipt.childId)
            put("peer_id", receipt.peerId); put("digest", receipt.digest)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        Unit
    }

    override fun receipts(roomId: String): List<FamilyCareReceipt> = owner.readableDatabase.rawQuery(
        "SELECT packet_id,child_id,peer_id,digest FROM family_care_receipts WHERE room_id=? ORDER BY rowid", arrayOf(roomId)
    ).use { rows -> buildList { while (rows.moveToNext()) add(FamilyCareReceipt(roomId, rows.getString(0),
        rows.getLong(1), rows.getLong(2), rows.getString(3))) } }

    override fun removeReceipt(receipt: FamilyCareReceipt) {
        owner.writableDatabase.delete("family_care_receipts", "room_id=? AND packet_id=? AND child_id=? AND peer_id=? AND digest=?",
            arrayOf(receipt.roomId, receipt.packetId, receipt.childId.toString(), receipt.peerId.toString(), receipt.digest))
    }

    override fun retryAfter(roomId: String, peerId: Long): Long = owner.readableDatabase.rawQuery(
        "SELECT retry_after FROM family_care_peer_state WHERE room_id=? AND peer_id=?", arrayOf(roomId, peerId.toString())
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    override fun setRetryAfter(roomId: String, peerId: Long, until: Long) {
        require(until >= 0)
        owner.writableDatabase.insertWithOnConflict("family_care_peer_state", null, ContentValues().apply {
            put("room_id", roomId); put("peer_id", peerId); put("retry_after", until)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun snapshotChunks(roomId: String, transferId: String): List<FamilyCareChunk> = owner.readableDatabase.rawQuery(
        "SELECT child_id,epoch,revision,chunk_index,chunk_count,digest,encoded FROM family_care_chunks " +
            "WHERE room_id=? AND transfer_id=? ORDER BY chunk_index", arrayOf(roomId, transferId)
    ).use { rows -> buildList { while (rows.moveToNext()) add(FamilyCareChunk(roomId, transferId, rows.getLong(0),
        rows.getString(1), rows.getLong(2), rows.getInt(3), rows.getInt(4), rows.getString(5), rows.getString(6))) } }

    override fun putSnapshotChunk(chunk: FamilyCareChunk) = transaction {
        require(chunk.index in 0 until chunk.count && chunk.count > 0 && chunk.revision >= 0)
        val existing = snapshotChunks(chunk.roomId, chunk.transferId)
        require(existing.all { it.childId == chunk.childId && it.epoch == chunk.epoch && it.revision == chunk.revision &&
            it.count == chunk.count && it.digest == chunk.digest && (it.index != chunk.index || it.encoded == chunk.encoded) }) {
            "가족 칭찬판을 받는 중에 내용이 달라졌어요."
        }
        owner.writableDatabase.insertWithOnConflict("family_care_chunks", null, ContentValues().apply {
            put("room_id", chunk.roomId); put("transfer_id", chunk.transferId); put("child_id", chunk.childId)
            put("epoch", chunk.epoch); put("revision", chunk.revision); put("chunk_index", chunk.index)
            put("chunk_count", chunk.count); put("digest", chunk.digest); put("encoded", chunk.encoded)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        Unit
    }

    override fun removeSnapshotChunks(roomId: String, transferId: String) {
        owner.writableDatabase.delete("family_care_chunks", "room_id=? AND transfer_id=?", arrayOf(roomId, transferId))
    }

    override fun setError(roomId: String, childId: Long, error: String?) {
        if (error == null) owner.writableDatabase.delete("family_care_errors", "room_id=? AND child_id=?", arrayOf(roomId, childId.toString()))
        else owner.writableDatabase.insertWithOnConflict("family_care_errors", null, ContentValues().apply {
            put("room_id", roomId); put("child_id", childId); put("error", error)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun error(roomId: String, childId: Long): String? = owner.readableDatabase.rawQuery(
        "SELECT error FROM family_care_errors WHERE room_id=? AND child_id=?", arrayOf(roomId, childId.toString())
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun archiveEvent(roomId: String, childId: Long, event: FamilyEvent) = archiveMovement(roomId, childId, listOf(event))

    /** Keep the full location/vertical timeline apart from the bounded care state. Heartbeats remain in state. */
    fun archiveMovement(roomId: String, childId: Long, events: List<FamilyEvent>) = transaction {
        require(childId > 0)
        val db = owner.writableDatabase
        val zone = ZoneId.systemDefault()
        ensureMovementZone(db, zone)
        for (batch in events.filter { it.kind in MovementHistoryDates.KINDS }.chunked(300)) {
            val old = mutableMapOf<String, FamilyEvent>()
            val placeholders = batch.joinToString(",") { "?" }
            db.rawQuery("SELECT id,event FROM family_care_movement WHERE room_id=? AND child_id=? AND id IN ($placeholders)",
                (listOf(roomId, childId.toString()) + batch.map { it.id }).toTypedArray()).use { rows ->
                while (rows.moveToNext()) old[rows.getString(0)] = FamilyEvent.parse(JSONObject(rows.getString(1)))
            }
            for (input in batch) {
                val measuredAt = MovementHistoryDates.epoch(input) ?: continue
                val saved = old[input.id]
                val event = if (saved != null && MovementHistoryDates.deliveryRank(saved.delivery) > MovementHistoryDates.deliveryRank(input.delivery)) {
                    input.copy(delivery = saved.delivery, deliveryError = saved.deliveryError)
                } else input
                if (saved?.json()?.toString() == event.json().toString()) continue
                db.insertWithOnConflict("family_care_movement", null, ContentValues().apply {
                    put("room_id", roomId); put("child_id", childId); put("id", event.id)
                    put("measured_at", measuredAt); put("local_day", day(measuredAt, zone)); put("event", event.json().toString())
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        Unit
    }

    /** Import retained paired history exactly once for the explicitly identified child. */
    fun importLegacyMovement(roomId: String, childId: Long) = transaction {
        require(childId > 0)
        val db = owner.writableDatabase
        val key = "legacy:$roomId:$childId"
        val imported = db.rawQuery("SELECT value FROM family_care_settings WHERE name=?", arrayOf(key)).use { it.moveToFirst() }
        if (!imported) {
            val zone = ZoneId.systemDefault()
            ensureMovementZone(db, zone)
            var lastId = ""
            while (true) {
                val batch = db.rawQuery("SELECT id,event FROM movement_history WHERE id>? ORDER BY id LIMIT 300", arrayOf(lastId)).use { rows ->
                    buildList { while (rows.moveToNext()) add(rows.getString(0) to FamilyEvent.parse(JSONObject(rows.getString(1)))) }
                }
                if (batch.isEmpty()) break
                archiveMovement(roomId, childId, batch.map { it.second })
                lastId = batch.last().first
            }
            db.insertOrThrow("family_care_settings", null, ContentValues().apply { put("name", key); put("value", "1") })
        }
        Unit
    }

    /** Date and paging indexes always include both the room and child identity. */
    fun movementHistory(roomId: String, childId: Long, day: String? = null, before: MovementHistoryCursor? = null,
        limit: Int = MovementHistoryDates.PAGE_SIZE, zone: ZoneId = ZoneId.systemDefault()): MovementHistoryPage = transaction {
        require(childId > 0 && limit in 1..1000)
        val db = owner.writableDatabase
        ensureMovementZone(db, zone)
        val days = db.rawQuery("SELECT DISTINCT local_day FROM family_care_movement WHERE room_id=? AND child_id=? ORDER BY local_day DESC",
            arrayOf(roomId, childId.toString())).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        val selected = MovementHistoryDates.selectDay(day, days, zone)
        val args = mutableListOf(roomId, childId.toString(), selected)
        val condition = if (before == null) "" else {
            args.add(before.measuredAt.toString()); args.add(before.measuredAt.toString()); args.add(before.id)
            " AND measured_at<=? AND (measured_at<? OR id<?)"
        }
        val entries = db.rawQuery("SELECT measured_at,id,event FROM family_care_movement " +
            "WHERE room_id=? AND child_id=? AND local_day=?$condition ORDER BY measured_at DESC,id DESC LIMIT ${limit + 1}",
            args.toTypedArray()).use { rows -> buildList { while (rows.moveToNext()) add(
                MovementHistoryCursor(rows.getLong(0), rows.getString(1)) to FamilyEvent.parse(JSONObject(rows.getString(2)))) } }
        MovementHistoryPage(days, selected, entries.take(limit).map { it.second },
            if (entries.size > limit) entries[limit - 1].first else null)
    }

    private fun ensureMovementZone(db: SQLiteDatabase, zone: ZoneId) {
        val previous = db.rawQuery("SELECT value FROM family_care_settings WHERE name='movement_zone'", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (previous == zone.id) return
        var lastRow = 0L
        while (true) {
            val batch = db.rawQuery("SELECT rowid,measured_at FROM family_care_movement WHERE rowid>? ORDER BY rowid LIMIT 500",
                arrayOf(lastRow.toString())).use { rows -> buildList { while (rows.moveToNext()) add(rows.getLong(0) to rows.getLong(1)) } }
            if (batch.isEmpty()) break
            for ((row, time) in batch) db.update("family_care_movement", ContentValues().apply { put("local_day", day(time, zone)) },
                "rowid=?", arrayOf(row.toString()))
            lastRow = batch.last().first
        }
        db.insertWithOnConflict("family_care_settings", null, ContentValues().apply {
            put("name", "movement_zone"); put("value", zone.id)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        private fun day(time: Long, zone: ZoneId) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toString()

        internal fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE family_care_states (room_id TEXT NOT NULL,child_id INTEGER NOT NULL,state TEXT NOT NULL,PRIMARY KEY(room_id,child_id))")
            db.execSQL("CREATE TABLE family_care_outcomes (room_id TEXT NOT NULL,child_id INTEGER NOT NULL,command_id TEXT NOT NULL,outcome TEXT NOT NULL,PRIMARY KEY(room_id,command_id))")
            db.execSQL("CREATE INDEX family_care_outcome_child ON family_care_outcomes(room_id,child_id)")
            db.execSQL("CREATE TABLE family_care_commands (room_id TEXT NOT NULL,child_id INTEGER NOT NULL,id TEXT NOT NULL,command TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(room_id,id))")
            db.execSQL("CREATE INDEX family_care_command_child ON family_care_commands(room_id,child_id)")
            db.execSQL("CREATE TABLE family_care_received (room_id TEXT NOT NULL,packet_id TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(room_id,packet_id))")
            db.execSQL("CREATE TABLE family_care_packets (room_id TEXT NOT NULL,id TEXT NOT NULL,child_id INTEGER NOT NULL,text TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(room_id,id))")
            db.execSQL("CREATE TABLE family_care_deliveries (room_id TEXT NOT NULL,packet_id TEXT NOT NULL,peer_id INTEGER NOT NULL,sent_at INTEGER NOT NULL DEFAULT 0," +
                "send_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(send_confirmed IN (0,1))," +
                "completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN (0,1)),PRIMARY KEY(room_id,packet_id,peer_id))")
            db.execSQL("CREATE INDEX family_care_delivery_pending ON family_care_deliveries(room_id,completed,packet_id,peer_id)")
            db.execSQL("CREATE TABLE family_care_receipts (room_id TEXT NOT NULL,packet_id TEXT NOT NULL,child_id INTEGER NOT NULL,peer_id INTEGER NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(room_id,packet_id,peer_id))")
            db.execSQL("CREATE TABLE family_care_peer_state (room_id TEXT NOT NULL,peer_id INTEGER NOT NULL,retry_after INTEGER NOT NULL,PRIMARY KEY(room_id,peer_id))")
            db.execSQL("CREATE TABLE family_care_chunks (room_id TEXT NOT NULL,transfer_id TEXT NOT NULL,child_id INTEGER NOT NULL,epoch TEXT NOT NULL,revision INTEGER NOT NULL," +
                "chunk_index INTEGER NOT NULL,chunk_count INTEGER NOT NULL,digest TEXT NOT NULL,encoded TEXT NOT NULL,PRIMARY KEY(room_id,transfer_id,chunk_index))")
            db.execSQL("CREATE TABLE family_care_errors (room_id TEXT NOT NULL,child_id INTEGER NOT NULL,error TEXT NOT NULL,PRIMARY KEY(room_id,child_id))")
            db.execSQL("CREATE TABLE family_care_movement (room_id TEXT NOT NULL,child_id INTEGER NOT NULL,id TEXT NOT NULL,measured_at INTEGER NOT NULL,local_day TEXT NOT NULL,event TEXT NOT NULL," +
                "PRIMARY KEY(room_id,child_id,id))")
            db.execSQL("CREATE INDEX family_care_movement_day_time ON family_care_movement(room_id,child_id,local_day DESC,measured_at DESC,id DESC)")
            db.execSQL("CREATE TABLE family_care_settings (name TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("INSERT INTO family_care_settings(name,value) VALUES('movement_zone',?)", arrayOf(ZoneId.systemDefault().id))
        }

        internal fun clearTables(db: SQLiteDatabase) {
            listOf("family_care_states", "family_care_outcomes", "family_care_commands", "family_care_received", "family_care_deliveries", "family_care_packets",
                "family_care_receipts", "family_care_peer_state", "family_care_chunks", "family_care_errors", "family_care_movement",
                "family_care_settings").forEach { db.delete(it, null, null) }
        }
    }
}
