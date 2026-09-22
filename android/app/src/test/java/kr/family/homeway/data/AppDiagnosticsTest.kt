package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class AppDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun repeatedRendersAreDeduplicatedBeforeAnyIoAndBecomeEligibleAfterOneMinute() {
        val admission = DiagnosticAdmission()
        assertNotNull(admission.prepare("receive", "인터넷 연결을 확인해 주세요.", 1, 1_000))
        repeat(59) { second ->
            assertNull(admission.prepare("receive", "인터넷 연결을 확인해 주세요.", 10_000 - second.toLong(), 2_000 + second * 1_000L))
        }
        assertNotNull(admission.prepare("receive", "인터넷 연결을 확인해 주세요.", 2, 61_000))
        assertNotNull(admission.prepare("tracking", "인터넷 연결을 확인해 주세요.", 2, 61_000))
        assertNotNull(admission.prepare("receive", "권한을 확인해 주세요.", 2, 61_000))
        assertNull(admission.prepare("receive", null, 2, 61_000))
        assertNull(admission.prepare("receive", "  \n", 2, 61_000))
    }

    @Test fun recentMessageMemoryIsBoundedAndRedactionPrecedesTheDedupKey() {
        val admission = DiagnosticAdmission()
        assertNotNull(admission.prepare("transport", "token=first_secret", 0, 0))
        assertNull(admission.prepare("transport", "token=second_secret", 0, 1))
        repeat(DiagnosticAdmission.MAX_RECENT) { index ->
            assertNotNull(admission.prepare("transport", "연결 상태 $index", 0, index + 2L))
        }
        assertNotNull(admission.prepare("transport", "token=third_secret", 0, 300))
    }

    @Test fun tokensUrlsAuthorizationFamilyCodesAndCoordinatesNeverReachTheRecord() {
        val token = "123456789:${"Q".repeat(35)}"
        val values = listOf(
            token to "봇 요청 실패 $token",
            "hidden-auth" to "Authorization: Bearer hidden-auth\n연결 오류",
            "user:password@example.com" to "https://user:password@example.com/path?key=secret",
            "private-family-code" to "가족 연결 오류 familyCode=private-family-code",
            "eHl6X3NlY3JldA" to "TFROOM1:eHl6X3NlY3JldA",
            "private text" to "chat=\"private text\" 전달 실패",
            "private message" to "message='private message'",
            "비공개 대화" to "메시지: \"비공개 대화\"",
            "비공개-연결키" to "가족 연결 키=비공개-연결키",
            "37.5" to "좌표 37.5,127.0 요청 실패",
            "127.0001" to "longitude=127.0001",
        )
        for ((secret, raw) in values) {
            val safe = DiagnosticRedaction.message(raw)!!
            assertFalse("Leaked $secret", safe.contains(secret))
            assertTrue(safe.contains("[숨김]"))
        }
        assertEquals("unknown", DiagnosticRedaction.source("source\n$token"))
        assertEquals("telegram.receive", DiagnosticRedaction.source("telegram.receive"))
    }

    @Test fun oversizedInputsDoNotLeakPartialSecretsAndControlCharactersCannotInjectRows() {
        val large = "safe ".repeat(2_000) + "123456:${"z".repeat(30)}"
        val safe = DiagnosticRedaction.message(large)!!
        assertFalse(safe.contains("123456"))
        assertTrue(safe.length < 100)
        val directory = temporary.newFolder("lines")
        DiagnosticLogStore(directory).append(DiagnosticEntry(0, "receive", "오류\n새 줄\r\u0000\u202e"))
        val lines = File(directory, "current.jsonl").readLines()
        assertEquals(1, lines.size)
        assertEquals("오류 새 줄", JSONObject(lines.single()).getString("message"))
    }

    @Test fun twoFileRotationBoundsUtf8BytesAndRetainsNewestDiagnosticsAcrossReopen() {
        val directory = temporary.newFolder("rotation")
        repeat(100) { index ->
            DiagnosticLogStore(directory, 512).append(DiagnosticEntry(index.toLong(), "receive", "진단 $index " + "가족 연결 실패 ".repeat(4)))
        }
        val files = directory.listFiles()!!.toList()
        assertEquals(setOf("previous.jsonl", "current.jsonl"), files.map { it.name }.toSet())
        assertTrue(files.all { it.length() in 1..512 })
        val output = dump(DiagnosticLogStore(directory, 512))
        val rows = output.lineSequence().filter { it.startsWith("{") }.map(::JSONObject).toList()
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.last().getString("message").startsWith("진단 99 "))
        assertFalse(output.contains("진단 0 "))
        assertEquals(rows.map { it.getString("time") }.sorted(), rows.map { it.getString("time") })
    }

    @Test fun dumpingDiscardsCorruptRowsAndExtraFieldsAndRedactsHistoricalValuesAgain() {
        val directory = temporary.newFolder("dump")
        val token = "123456:${"x".repeat(30)}"
        File(directory, "current.jsonl").writeText("bad row $token\n" + JSONObject()
            .put("time", "2026-09-22T12:00:00Z").put("source", "receive")
            .put("message", "실패 $token").put("extra", "private content").toString() + "\n")
        val output = dump(DiagnosticLogStore(directory))
        assertFalse(output.contains(token))
        assertFalse(output.contains("private content"))
        assertFalse(output.contains("bad row"))
        assertEquals(1, output.lineSequence().count { it.startsWith("{") })
    }

    @Test fun loggingFailureCannotEscapeAndAnOversizedRowCannotExceedTheFileCap() {
        val blocked = temporary.newFile("not-a-directory")
        val store = DiagnosticLogStore(blocked)
        store.append(DiagnosticEntry(0, "receive", "연결 실패"))
        assertTrue(dump(store).contains("No local diagnostics."))
        val directory = temporary.newFolder("small")
        DiagnosticLogStore(directory, 20).append(DiagnosticEntry(0, "receive", "연결 실패"))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    private fun dump(store: DiagnosticLogStore): String = StringWriter().also { output ->
        PrintWriter(output).use(store::dump)
    }.toString()
}
