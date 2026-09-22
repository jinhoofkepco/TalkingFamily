package kr.family.homeway.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

/** Isolated synthetic directories only; never reads the installed family's diagnostic history. */
@RunWith(AndroidJUnit4::class)
class AppDiagnosticsStorageTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var source: String

    @Before fun prepare() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.noBackupFilesDir, "diagnostics-test-${UUID.randomUUID()}").apply { mkdirs() }
        source = "test-${UUID.randomUUID().toString().take(8)}"
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    @After fun cleanUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir.canonicalFile
        assertEquals(base, directory.canonicalFile.parentFile)
        assertTrue(directory.name.startsWith("diagnostics-test-"))
        directory.deleteRecursively()
    }

    @Test fun asyncRecordDedupeAndDiagnosticDumpUseOnlyThePrivateNoBackupDirectory() {
        val token = "123456:${"test_secret_".repeat(3)}"
        repeat(20) { AppDiagnostics.record(context, source, "연결 실패 $token") }
        val output = StringWriter().also { AppDiagnostics.dump(context, PrintWriter(it)) }.toString()
        val files = File(directory, "diagnostics").listFiles()!!.toList()
        assertEquals(listOf("current.jsonl"), files.map { it.name })
        val persisted = files.single().readText()
        assertFalse(persisted.contains(token))
        assertFalse(output.contains(token))
        val rows = persisted.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
        assertEquals(1, rows.size)
        assertEquals(source, rows.single().getString("source"))
        assertEquals("연결 실패 [숨김]", rows.single().getString("message"))
        assertTrue(output.contains("연결 실패 [숨김]"))
    }

    @Test fun invalidStorageDoesNotThrowOrExposeTheOriginalSensitiveMessage() {
        val blocked = File(directory, "blocked").apply { writeText("test") }
        val invalidContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = blocked
        }
        AppDiagnostics.record(invalidContext, source, "Authorization: Bearer private-test-value")
        val output = StringWriter().also { AppDiagnostics.dump(invalidContext, PrintWriter(it)) }.toString()
        assertFalse(output.contains("private-test-value"))
        assertTrue(File(directory, "diagnostics").listFiles().isNullOrEmpty())
    }
}
