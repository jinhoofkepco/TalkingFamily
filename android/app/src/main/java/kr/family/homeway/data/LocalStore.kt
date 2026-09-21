package kr.family.homeway.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class LocalStore private constructor(context: Context) : SQLiteOpenHelper(context, "homeway.db", null, 2), TelegramExchangeStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE outbox (id TEXT PRIMARY KEY, event TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', error TEXT)")
        db.execSQL("CREATE TABLE cache (id INTEGER PRIMARY KEY CHECK(id=1), state TEXT NOT NULL)")
        createTelegramTables(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTelegramTables(db)
    }
    private fun createTelegramTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE telegram_receipts (id TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE telegram_meta (name TEXT PRIMARY KEY, value INTEGER NOT NULL)")
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
    fun enqueue(event: FamilyEvent) {
        writableDatabase.insertWithOnConflict("outbox", null, ContentValues().apply {
            put("id", event.id); put("event", event.json().toString()); put("status", "pending")
        }, SQLiteDatabase.CONFLICT_IGNORE)
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
        writableDatabase.insertWithOnConflict("cache", null, ContentValues().apply { put("id", 1); put("state", state.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun cached(): JSONObject? = readableDatabase.rawQuery("SELECT state FROM cache WHERE id=1", null).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    fun clear() {
        writableDatabase.execSQL("DELETE FROM outbox"); writableDatabase.execSQL("DELETE FROM cache")
        writableDatabase.execSQL("DELETE FROM telegram_receipts"); writableDatabase.execSQL("DELETE FROM telegram_meta")
    }
    companion object {
        @Volatile private var instance: LocalStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: LocalStore(context.applicationContext).also { instance = it }
        }
    }
}
