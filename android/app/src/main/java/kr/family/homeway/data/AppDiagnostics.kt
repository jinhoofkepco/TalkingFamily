package kr.family.homeway.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Local support diagnostics only. Call sites pass authored status text, never family content. */
object AppDiagnostics {
    private val admission = DiagnosticAdmission()
    private val executor = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "family-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy())

    /** Best effort and asynchronous: diagnostics cannot block or fail the operation being reported. */
    fun record(context: Context, source: String, message: String?) {
        try {
            val entry = admission.prepare(source, message, System.currentTimeMillis(), System.nanoTime() / 1_000_000)
                ?: return
            val app = context.applicationContext
            executor.execute { try { store(app).append(entry) } catch (_: Exception) { } }
        } catch (_: Exception) { }
    }

    /** Called by the Activity's privileged diagnostic dump; no screen, sharing flow or upload exists. */
    fun dump(context: Context, writer: PrintWriter) {
        try {
            val app = context.applicationContext
            // A queued barrier also includes earlier asynchronous records. The caller never waits
            // indefinitely, and a timed-out task cannot write later into the caller's PrintWriter.
            val task = FutureTask {
                StringWriter().also { output -> PrintWriter(output).use { store(app).dump(it) } }.toString()
            }
            executor.execute(task)
            try { writer.print(task.get(2, TimeUnit.SECONDS)) }
            catch (_: Exception) {
                task.cancel(false)
                writer.println("Local diagnostics are temporarily unavailable.")
            }
        } catch (_: Exception) { writer.println("Local diagnostics are unavailable.") }
    }

    private fun store(context: Context) = DiagnosticLogStore(File(context.noBackupFilesDir, "diagnostics"))
}

internal data class DiagnosticEntry(val timeMillis: Long, val source: String, val message: String)

/** Admission happens before queuing so repeated UI renders do not create repeated disk tasks. */
internal class DiagnosticAdmission {
    private val recent = LinkedHashMap<Pair<String, String>, Long>()

    @Synchronized
    fun prepare(source: String, message: String?, timeMillis: Long, elapsedMillis: Long): DiagnosticEntry? {
        val safeMessage = DiagnosticRedaction.message(message) ?: return null
        val safeSource = DiagnosticRedaction.source(source)
        val key = safeSource to safeMessage
        val previous = recent[key]
        if (previous != null && elapsedMillis >= previous && elapsedMillis - previous < DEDUPE_MILLIS) return null
        recent.remove(key)
        recent[key] = elapsedMillis
        while (recent.size > MAX_RECENT) recent.remove(recent.keys.first())
        return DiagnosticEntry(timeMillis, safeSource, safeMessage)
    }

    companion object {
        const val DEDUPE_MILLIS = 60_000L
        const val MAX_RECENT = 256
    }
}

/** Defense in depth, not a substitute for the call-site rule against logging user content. */
internal object DiagnosticRedaction {
    private const val HIDDEN = "[숨김]"
    private val sourcePattern = Regex("[A-Za-z0-9_.-]{1,48}")
    private val authorization = Regex("(?i)\\b(?:proxy-authorization|authorization)\\s*[:=]\\s*[^\\r\\n]*")
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val url = Regex("(?i)\\b(?:https?|tg|ftp|file|content)://[^\\s<>\"']+")
    private val token = Regex("(?<![A-Za-z0-9_])(?:bot)?[0-9]{1,20}:[A-Za-z0-9_-]{20,}")
    private val familyCode = Regex("TFROOM[0-9]+:[A-Za-z0-9_-]+")
    private val secretField = Regex("(?i)(?:bot[_ -]?token|access[_ -]?token|refresh[_ -]?token|token|api[_ -]?key|" +
        "family[_ -]?(?:key|code)|room[_ -]?id|password|passwd|(?:가족방?|연결)\\s*(?:연결\\s*)?(?:키|코드)|토큰|비밀번호|" +
        "latitude|longitude|lat|lon|lng|위도|경도|chat|text|message|body|채팅|메시지|대화\\s*내용)\\s*[\"']?\\s*[:=]\\s*" +
        "(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)")
    private val coordinatePair = Regex("(?<![0-9])-?[0-9]{1,3}\\.[0-9]+\\s*[,/ ]\\s*-?[0-9]{1,3}\\.[0-9]+(?![0-9])")
    private val controls = Regex("[\\p{Cntrl}\\p{Cf}]")

    fun source(value: String): String = value.takeIf { sourcePattern.matches(it) } ?: "unknown"

    fun message(value: String?): String? {
        if (value.isNullOrBlank()) return null
        // Reject oversized values before regex work; this also prevents retaining partial secrets
        // when a token or invite straddles a truncation boundary.
        if (value.length > 8_192) return "긴 진단 문구를 생략했어요."
        var safe = authorization.replace(value, HIDDEN)
        safe = bearer.replace(safe, HIDDEN)
        safe = url.replace(safe, HIDDEN)
        safe = token.replace(safe, HIDDEN)
        safe = familyCode.replace(safe, HIDDEN)
        safe = secretField.replace(safe, HIDDEN)
        safe = coordinatePair.replace(safe, HIDDEN)
        safe = controls.replace(safe, " ").trim()
        return safe.take(2_048).takeIf { it.isNotBlank() }
    }
}

/** A private two-file ring. The production caller serializes access on its sole writer thread. */
internal class DiagnosticLogStore(private val directory: File, private val maxFileBytes: Int = MAX_FILE_BYTES) {
    private val current get() = File(directory, "current.jsonl")
    private val previous get() = File(directory, "previous.jsonl")

    fun append(entry: DiagnosticEntry) {
        try {
            val safeMessage = DiagnosticRedaction.message(entry.message) ?: return
            val line = JSONObject().put("time", Instant.ofEpochMilli(entry.timeMillis).toString())
                .put("source", DiagnosticRedaction.source(entry.source)).put("message", safeMessage).toString() + "\n"
            val bytes = line.toByteArray(Charsets.UTF_8)
            if (bytes.size > maxFileBytes || maxFileBytes <= 0) return
            if (!directory.isDirectory && !directory.mkdirs()) return
            if (current.length() + bytes.size > maxFileBytes) {
                if (previous.exists() && !previous.delete()) return
                if (current.exists() && !current.renameTo(previous)) return
            }
            current.appendBytes(bytes)
        } catch (_: Exception) { }
    }

    fun dump(writer: PrintWriter) {
        writer.println("TalkingFamily local diagnostics")
        try {
            var count = 0
            for (file in listOf(previous, current)) {
                if (!file.isFile) continue
                val bytes = ByteArray(maxFileBytes.coerceIn(1, MAX_FILE_BYTES))
                val length = file.inputStream().use { input ->
                    var total = 0
                    while (total < bytes.size) {
                        val read = input.read(bytes, total, bytes.size - total)
                        if (read < 0) break
                        total += read
                    }
                    total
                }
                String(bytes, 0, length, Charsets.UTF_8).lineSequence().forEach { line ->
                    // Revalidate on export. Incomplete/corrupt rows and extra JSON fields are omitted.
                    try {
                        val row = JSONObject(line)
                        val time = Instant.parse(row.getString("time"))
                        val message = DiagnosticRedaction.message(row.getString("message")) ?: return@forEach
                        writer.println(JSONObject().put("time", time.toString())
                            .put("source", DiagnosticRedaction.source(row.getString("source")))
                            .put("message", message).toString())
                        count++
                    } catch (_: Exception) { }
                }
            }
            if (count == 0) writer.println("No local diagnostics.")
        } catch (_: Exception) { writer.println("Local diagnostics are unavailable.") }
    }

    companion object { const val MAX_FILE_BYTES = 256 * 1024 }
}
