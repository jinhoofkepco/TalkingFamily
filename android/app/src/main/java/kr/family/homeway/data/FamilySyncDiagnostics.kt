package kr.family.homeway.data

import android.content.Context
import java.io.PrintWriter
import java.security.MessageDigest

/** Counts only: no token, invite, name, message, coordinate, or event JSON enters diagnostics. */
internal object FamilySyncDiagnostics {
    private data class Snapshot(val capturedAt: Long, val lines: List<String>)
    @Volatile private var snapshot: Snapshot? = null
    private var nextCaptureAt = 0L

    /** Called under the repository lock on IO. A UI/service dump reads the immutable cache only. */
    fun capture(context: Context, store: LocalStore, room: FamilyChatRoom?, ownId: Long, now: Long) {
        if (now < nextCaptureAt && now >= nextCaptureAt - INTERVAL) return
        nextCaptureAt = now + INTERVAL
        try {
            val db = store.readableDatabase
            val lines = mutableListOf<String>()
            val legacy = db.rawQuery("SELECT count(*) FROM outbox WHERE status='pending'", null)
                .use { it.moveToFirst(); it.getLong(0) }
            lines += "scope=${room?.id?.let(::fingerprint) ?: "private"} legacyPending=$legacy"
            room?.members?.filter { it.relationship in FamilyCareValidation.childRelationships }
                ?.forEachIndexed { index, child ->
                    val args = arrayOf(room.id, child.botId.toString())
                    val state = store.familyCare.stateMetadata(room.id, child.botId)
                    val archive = db.rawQuery("SELECT count(*),count(DISTINCT local_day) FROM family_care_movement " +
                        "WHERE room_id=? AND child_id=?", args).use { it.moveToFirst(); it.getLong(0) to it.getLong(1) }
                    val packets = db.rawQuery("SELECT p.packet_type,count(*) FROM family_care_packets p " +
                        "JOIN family_care_deliveries d ON d.room_id=p.room_id AND d.packet_id=p.id " +
                        "WHERE p.room_id=? AND p.child_id=? AND d.completed=0 GROUP BY p.packet_type", args).use { rows ->
                        buildList { while (rows.moveToNext()) {
                            // packet_type is local transport metadata; never print unknown values.
                            val type = rows.getString(0)?.takeIf { it in TYPES } ?: "other"
                            add("$type:${rows.getLong(1)}")
                        } }.sorted().joinToString(",")
                    }
                    val receipts = db.rawQuery("SELECT count(*) FROM family_care_receipts WHERE room_id=? AND child_id=?", args)
                        .use { it.moveToFirst(); it.getLong(0) }
                    val live = db.rawQuery("SELECT count(*) FROM family_care_live_outgoing WHERE room_id=? AND child_id=? AND completed=0", args)
                        .use { it.moveToFirst(); it.getLong(0) }
                    val history = store.familyCare.historyProgress(room.id, child.botId)
                    val peer = if (child.botId == ownId) room.members.firstOrNull {
                        it.relationship in FamilyCareValidation.parentRelationships }?.botId else child.botId
                    val features = peer?.let {
                        "delta=${FamilyTransport.supportsCareDeltas(store, room.id, it, now)} " +
                            "latest=${FamilyTransport.supportsLatestLocation(store, room.id, it, now)} " +
                            "history=${FamilyTransport.supportsMovementHistory(store, room.id, it, now)}"
                    } ?: "delta=false latest=false history=false"
                    lines += "childSlot=${index + 1} own=${child.botId == ownId} " +
                        "epoch=${state?.epoch?.let(::fingerprint) ?: "none"} revision=${state?.revision ?: -1} " +
                        "archiveRows=${archive.first} archiveDays=${archive.second} pending=[$packets] " +
                        "receipts=$receipts livePending=$live careError=${store.familyCare.error(room.id, child.botId) != null} $features"
                    if (history != null) lines += "childSlot=${index + 1} historyStream=${history.streamId?.let(::fingerprint) ?: "none"} " +
                        "historyCursor=${history.cursor} historyAnchor=${history.anchor} sourceRecordCount=${history.count} " +
                        "historyComplete=${history.complete}"
                }
            room?.members?.filter { it.botId != ownId }?.forEachIndexed { index, peer ->
                val pending = db.rawQuery("SELECT count(*),sum(d.completed) FROM family_care_deliveries d " +
                    "WHERE d.room_id=? AND d.peer_id=?", arrayOf(room.id, peer.botId.toString())).use {
                    it.moveToFirst(); val completed = it.getLong(1); (it.getLong(0) - completed) to completed
                }
                val prefix = "transport:${room.id}:${peer.botId}:"
                val fileLease = store.meta(prefix + "fileSupportedUntil") > now
                val fallback = (store.meta(prefix + "fileFallbackUntil") - now).coerceAtLeast(0)
                val retry = (store.familyCare.retryAfter(room.id, peer.botId) - now).coerceAtLeast(0)
                lines += "peerSlot=${index + 1} carePending=${pending.first} careCompleted=${pending.second} " +
                    "fileLease=$fileLease fileFallbackRemainingMillis=$fallback careRetryRemainingMillis=$retry"
            }
            snapshot = Snapshot(now, lines)
            lines.forEach { AppDiagnostics.record(context, "telegram.care_state", it) }
        } catch (error: Exception) {
            AppDiagnostics.recordFailure(context, "telegram.care_diagnostics", error)
        }
    }

    fun dump(writer: PrintWriter) {
        val captured = snapshot
        if (captured == null) { writer.println("careDiagnostics=not_captured"); return }
        writer.println("careDiagnosticsAgeMillis=${(System.currentTimeMillis() - captured.capturedAt).coerceAtLeast(0)}")
        captured.lines.forEach(writer::println)
    }

    internal fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }

    private const val INTERVAL = 60_000L
    private val TYPES = setOf("command", "outcome", "child_event", "snapshot_chunk", "care_delta", "care_sync",
        "sync_request", "history_request", "history_event", "history_checkpoint")
}
