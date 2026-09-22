package kr.family.homeway.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LocalStore internal constructor(context: Context, databaseName: String = "homeway.db") :
    SQLiteOpenHelper(context, databaseName, null, 6), TelegramExchangeStore {
    val familyChat = SqliteFamilyChatStore(this)
    val familyCare = SqliteFamilyCareStore(this)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE outbox (id TEXT PRIMARY KEY, event TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', error TEXT)")
        db.execSQL("CREATE TABLE cache (id INTEGER PRIMARY KEY CHECK(id=1), state TEXT NOT NULL)")
        createTelegramTables(db)
        createMovementTables(db)
        createPrivateChatTables(db)
        SqliteFamilyChatStore.createTables(db)
        SqliteFamilyCareStore.createTables(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTelegramTables(db)
        if (oldVersion < 3) {
            createMovementTables(db)
            // Only already retained records can be recovered; keep cache/outbox/Telegram cursors intact.
            db.rawQuery("SELECT state FROM cache WHERE id=1", null).use { rows ->
                if (rows.moveToFirst()) archiveState(db, JSONObject(rows.getString(0)))
            }
            db.rawQuery("SELECT event,status,error FROM outbox", null).use { rows ->
                val events = mutableListOf<FamilyEvent>()
                while (rows.moveToNext()) {
                    runCatching { FamilyEvent.parse(JSONObject(rows.getString(0))).copy(
                        delivery = if (rows.getString(1) == "failed") "failed" else "queued",
                        deliveryError = rows.getString(2)) }.getOrNull()?.let { events.add(it) }
                    if (events.size >= 300) { archiveEvents(db, events); events.clear() }
                }
                archiveEvents(db, events)
            }
        }
        if (oldVersion < 4) {
            createPrivateChatTables(db)
            SqliteFamilyChatStore.createTables(db)
            // Recover only retained private chat. No pairing, movement, outbox or receive cursor is reset.
            db.rawQuery("SELECT state FROM cache WHERE id=1", null).use { rows ->
                if (rows.moveToFirst()) archivePrivateState(db, JSONObject(rows.getString(0)))
            }
            db.rawQuery("SELECT event,status,error FROM outbox", null).use { rows ->
                while (rows.moveToNext()) {
                    runCatching { FamilyEvent.parse(JSONObject(rows.getString(0))).copy(
                        delivery = if (rows.getString(1) == "failed") "failed" else "queued",
                        deliveryError = rows.getString(2)) }.getOrNull()?.let { archivePrivateEvents(db, listOf(it)) }
                }
            }
        }
        // New family-care tables are additive. Existing pair/chat ledgers and Telegram cursors stay intact.
        if (oldVersion < 5) SqliteFamilyCareStore.createTables(db)
        else if (oldVersion < 6) db.execSQL("ALTER TABLE family_care_deliveries ADD COLUMN " +
            "send_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(send_confirmed IN (0,1))")
    }
    private fun createTelegramTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE telegram_receipts (id TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE telegram_meta (name TEXT PRIMARY KEY, value INTEGER NOT NULL)")
    }
    private fun createMovementTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE movement_history (id TEXT PRIMARY KEY, measured_at INTEGER NOT NULL, local_day TEXT NOT NULL, event TEXT NOT NULL)")
        db.execSQL("CREATE INDEX movement_history_day_time ON movement_history(local_day DESC, measured_at DESC, id DESC)")
        db.execSQL("CREATE INDEX movement_history_time ON movement_history(measured_at DESC)")
        db.execSQL("CREATE TABLE movement_settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("INSERT INTO movement_settings(name,value) VALUES('zone',?)", arrayOf(ZoneId.systemDefault().id))
    }
    private fun createPrivateChatTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE private_chat_history (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, event TEXT NOT NULL)")
        db.execSQL("CREATE INDEX private_chat_history_time ON private_chat_history(created_at DESC,id DESC)")
    }
    override fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    override fun meta(name: String): Long = readableDatabase.rawQuery("SELECT value FROM telegram_meta WHERE name=?", arrayOf(name)).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }
    override fun setMeta(name: String, value: Long) {
        if (value == 0L && (name.startsWith("legacySentAt:") || name.startsWith("legacyConfirmed:"))) {
            writableDatabase.delete("telegram_meta", "name=?", arrayOf(name))
            return
        }
        writableDatabase.insertWithOnConflict("telegram_meta", null, ContentValues().apply {
            put("name", name); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun queueReceipt(id: String) {
        writableDatabase.insertWithOnConflict("telegram_receipts", null, ContentValues().apply { put("id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    override fun receipts(): List<String> = readableDatabase.rawQuery("SELECT id FROM telegram_receipts ORDER BY rowid", null).use {
        buildList { while (it.moveToNext()) add(it.getString(0)) }
    }
    override fun removeReceipt(id: String) { writableDatabase.delete("telegram_receipts", "id=?", arrayOf(id)) }
    fun enqueue(event: FamilyEvent) = transaction {
        writableDatabase.insertWithOnConflict("outbox", null, ContentValues().apply {
            put("id", event.id); put("event", event.json().toString()); put("status", "pending")
        }, SQLiteDatabase.CONFLICT_IGNORE)
        archivePrivateEvents(writableDatabase, listOf(event.copy(delivery = "queued")))
    }
    override fun pending(): List<FamilyEvent> = readableDatabase.rawQuery("SELECT event FROM outbox WHERE status='pending' ORDER BY rowid", null).use { c ->
        buildList { while (c.moveToNext()) add(FamilyEvent.parse(JSONObject(c.getString(0)))) }
    }
    fun localEvents(): List<FamilyEvent> = readableDatabase.rawQuery("SELECT event,status,error FROM outbox ORDER BY rowid", null).use { c ->
        buildList { while (c.moveToNext()) add(FamilyEvent.parse(JSONObject(c.getString(0))).copy(
            delivery = if(c.getString(1)=="failed") "failed" else "queued",
            deliveryError = c.getString(2))) }
    }
    override fun remove(id: String) { writableDatabase.delete("outbox", "id=?", arrayOf(id)) }
    fun fail(id: String, message: String) {
        writableDatabase.update("outbox", ContentValues().apply { put("status", "failed"); put("error", message) }, "id=?", arrayOf(id))
    }
    override fun cache(state: JSONObject) {
        transaction {
            val db = writableDatabase
            archiveState(db, state)
            archivePrivateState(db, state)
            db.insertWithOnConflict("cache", null, ContentValues().apply { put("id", 1); put("state", state.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
    override fun cached(): JSONObject? = readableDatabase.rawQuery("SELECT state FROM cache WHERE id=1", null).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    fun clear() {
        writableDatabase.execSQL("DELETE FROM outbox"); writableDatabase.execSQL("DELETE FROM cache")
        writableDatabase.execSQL("DELETE FROM telegram_receipts"); writableDatabase.execSQL("DELETE FROM telegram_meta")
        writableDatabase.execSQL("DELETE FROM movement_history")
        writableDatabase.execSQL("DELETE FROM private_chat_history")
        SqliteFamilyChatStore.clearTables(writableDatabase)
        SqliteFamilyCareStore.clearTables(writableDatabase)
    }

    /** Stable older-message pages, returned oldest first for a conversation list. */
    fun privateChatHistory(before: MovementHistoryCursor? = null, limit: Int = 200): PrivateChatHistoryPage {
        require(limit in 1..1000)
        val args = mutableListOf<String>()
        val condition = if (before == null) "" else {
            args.add(before.measuredAt.toString()); args.add(before.measuredAt.toString()); args.add(before.id)
            "WHERE h.created_at<=? AND (h.created_at<? OR h.id<?)"
        }
        val rows = readableDatabase.rawQuery("SELECT h.created_at,h.id,h.event,o.status,o.error FROM private_chat_history h " +
            "LEFT JOIN outbox o ON o.id=h.id $condition ORDER BY h.created_at DESC,h.id DESC LIMIT ${limit + 1}",
            args.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    var event = FamilyEvent.parse(JSONObject(cursor.getString(2)))
                    if (!cursor.isNull(3)) event = event.copy(
                        delivery = if (cursor.getString(3) == "failed") "failed" else "queued",
                        deliveryError = cursor.getString(4))
                    add(MovementHistoryCursor(cursor.getLong(0), cursor.getString(1)) to event)
                }
            }
        }
        return PrivateChatHistoryPage(rows.take(limit).map { it.second }.reversed(),
            if (rows.size > limit) rows[limit - 1].first else null)
    }

    private fun archivePrivateState(db: SQLiteDatabase, state: JSONObject) {
        val rows = state.optJSONArray("events") ?: return
        val events = buildList {
            for (i in 0 until rows.length()) {
                val raw = rows.optJSONObject(i) ?: continue
                if (raw.optString("kind") == "chat") runCatching { FamilyEvent.parse(raw) }.getOrNull()?.let { add(it) }
            }
        }
        archivePrivateEvents(db, events)
    }

    private fun archivePrivateEvents(db: SQLiteDatabase, events: List<FamilyEvent>) {
        for (batch in events.filter { it.kind == "chat" }.chunked(300)) {
            val existing = mutableMapOf<String, String>()
            val placeholders = batch.joinToString(",") { "?" }
            db.rawQuery("SELECT id,event FROM private_chat_history WHERE id IN ($placeholders)", batch.map { it.id }.toTypedArray()).use { rows ->
                while (rows.moveToNext()) existing[rows.getString(0)] = rows.getString(1)
            }
            for (input in batch) {
                val time = runCatching { Instant.parse(input.createdAt).toEpochMilli() }.getOrNull() ?: continue
                val old = existing[input.id]
                var event = input
                if (old != null) {
                    if (old == input.json().toString()) continue
                    val saved = FamilyEvent.parse(JSONObject(old))
                    if (MovementHistoryDates.deliveryRank(saved.delivery) > MovementHistoryDates.deliveryRank(input.delivery)) {
                        event = input.copy(delivery = saved.delivery, deliveryError = saved.deliveryError)
                    }
                }
                val serialized = event.json().toString()
                if (old == serialized) continue
                db.insertWithOnConflict("private_chat_history", null, ContentValues().apply {
                    put("id", event.id); put("created_at", time); put("event", serialized)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    /** Indexed date pages retain movement records even when the main ledger trims its recent timeline. */
    fun movementHistory(day: String? = null, before: MovementHistoryCursor? = null,
        limit: Int = MovementHistoryDates.PAGE_SIZE, zone: ZoneId = ZoneId.systemDefault()): MovementHistoryPage = transaction {
        require(limit in 1..1000)
        val db = writableDatabase
        ensureMovementZone(db, zone)
        val days = db.rawQuery("SELECT DISTINCT local_day FROM movement_history ORDER BY local_day DESC", null).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
        val selected = MovementHistoryDates.selectDay(day, days, zone)
        val parameters = mutableListOf(selected)
        val condition = if (before == null) "" else {
            parameters.add(before.measuredAt.toString()); parameters.add(before.measuredAt.toString()); parameters.add(before.id)
            " AND measured_at<=? AND (measured_at<? OR id<?)"
        }
        val entries = db.rawQuery("SELECT measured_at,id,event FROM movement_history WHERE local_day=?$condition ORDER BY measured_at DESC,id DESC LIMIT ${limit + 1}",
            parameters.toTypedArray()).use { rows ->
            buildList {
                while (rows.moveToNext()) add(MovementHistoryCursor(rows.getLong(0), rows.getString(1)) to FamilyEvent.parse(JSONObject(rows.getString(2))))
            }
        }
        MovementHistoryPage(days, selected, entries.take(limit).map { it.second },
            if (entries.size > limit) entries[limit - 1].first else null)
    }

    private fun archiveState(db: SQLiteDatabase, state: JSONObject) {
        val events = linkedMapOf<String, FamilyEvent>()
        // Array delivery status is newer than latestLocation after a peer ACK.
        state.optJSONObject("latestLocation")?.let { raw ->
            runCatching { FamilyEvent.parse(raw) }.getOrNull()?.let { events[it.id] = it }
        }
        state.optJSONArray("events")?.let { rows ->
            for (i in 0 until rows.length()) {
                val raw = rows.optJSONObject(i) ?: continue
                if (raw.optString("kind") !in MovementHistoryDates.KINDS) continue
                runCatching { FamilyEvent.parse(raw) }.getOrNull()?.let { events[it.id] = it }
            }
        }
        archiveEvents(db, events.values.toList())
    }

    private fun archiveEvents(db: SQLiteDatabase, events: List<FamilyEvent>) {
        if (events.isEmpty()) return
        val zone = ZoneId.systemDefault()
        ensureMovementZone(db, zone)
        // Read only candidates still in the bounded ledger, never reparse the complete archive.
        for (batch in events.filter { it.kind in MovementHistoryDates.KINDS }.chunked(300)) {
            val existing = mutableMapOf<String, String>()
            val placeholders = batch.joinToString(",") { "?" }
            db.rawQuery("SELECT id,event FROM movement_history WHERE id IN ($placeholders)", batch.map { it.id }.toTypedArray()).use { rows ->
                while (rows.moveToNext()) existing[rows.getString(0)] = rows.getString(1)
            }
            for (input in batch) {
                val measuredAt = MovementHistoryDates.epoch(input) ?: continue
                val old = existing[input.id]
                var event = input
                if (old != null) {
                    if (old == input.json().toString()) continue
                    val saved = FamilyEvent.parse(JSONObject(old))
                    // A stale latestLocation or pending outbox must not undo a received/failed status.
                    if (MovementHistoryDates.deliveryRank(saved.delivery) > MovementHistoryDates.deliveryRank(input.delivery)) {
                        event = input.copy(delivery = saved.delivery, deliveryError = saved.deliveryError)
                    }
                }
                val serialized = event.json().toString()
                if (serialized == old) continue
                db.insertWithOnConflict("movement_history", null, ContentValues().apply {
                    put("id", event.id); put("measured_at", measuredAt)
                    put("local_day", Instant.ofEpochMilli(measuredAt).atZone(zone).toLocalDate().toString())
                    put("event", serialized)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    private fun ensureMovementZone(db: SQLiteDatabase, zone: ZoneId) {
        val saved = db.rawQuery("SELECT value FROM movement_settings WHERE name='zone'", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (saved == zone.id) return
        // Rare timezone changes reindex small epoch/date metadata only, without parsing event JSON.
        var lastId = ""
        while (true) {
            val batch = db.rawQuery("SELECT id,measured_at FROM movement_history WHERE id>? ORDER BY id LIMIT 500", arrayOf(lastId)).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0) to rows.getLong(1)) }
            }
            if (batch.isEmpty()) break
            for ((id, time) in batch) db.update("movement_history", ContentValues().apply {
                put("local_day", Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toString())
            }, "id=?", arrayOf(id))
            lastId = batch.last().first
        }
        db.insertWithOnConflict("movement_settings", null, ContentValues().apply {
            put("name", "zone"); put("value", zone.id)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    companion object {
        @Volatile private var instance: LocalStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: LocalStore(context.applicationContext).also { instance = it }
        }
    }
}

data class MovementHistoryCursor(val measuredAt: Long, val id: String)
data class MovementHistoryPage(val days: List<String>, val day: String, val events: List<FamilyEvent>, val next: MovementHistoryCursor?)
data class PrivateChatHistoryPage(val events: List<FamilyEvent>, val nextCursor: MovementHistoryCursor?)

internal object MovementHistoryDates {
    const val PAGE_SIZE = 300
    val KINDS = setOf("location", "vertical")
    fun epoch(event: FamilyEvent): Long? = runCatching { Instant.parse(event.measuredAt).toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(event.createdAt).toEpochMilli() }.getOrNull()
    fun selectDay(requested: String?, days: List<String>, zone: ZoneId, today: LocalDate = LocalDate.now(zone)): String {
        if (requested != null) return LocalDate.parse(requested).toString()
        return today.toString().takeIf { it in days } ?: days.firstOrNull() ?: today.toString()
    }
    fun deliveryRank(delivery: String): Int = when (delivery) {
        "relayed", "delivered", "telegram_sent" -> 2
        "failed" -> 1
        else -> 0
    }
    fun demoPage(events: List<FamilyEvent>, day: String?, before: MovementHistoryCursor? = null,
        zone: ZoneId = ZoneId.systemDefault(), limit: Int = PAGE_SIZE): MovementHistoryPage {
        require(limit in 1..1000)
        val movement = events.filter { it.kind in KINDS }.mapNotNull { event -> epoch(event)?.let { it to event } }
            .sortedWith(compareByDescending<Pair<Long, FamilyEvent>> { it.first }.thenByDescending { it.second.id })
        val days = movement.map { Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate().toString() }.distinct()
        val selected = selectDay(day, days, zone)
        val entries = movement.filter { (time, event) ->
            Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toString() == selected &&
                (before == null || time < before.measuredAt || time == before.measuredAt && event.id < before.id)
        }.take(limit + 1)
        val last = entries.take(limit).lastOrNull()
        return MovementHistoryPage(days, selected, entries.take(limit).map { it.second },
            if (entries.size > limit && last != null) MovementHistoryCursor(last.first, last.second.id) else null)
    }
}
