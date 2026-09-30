package kr.family.homeway.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FamilySyncDiagnosticsTest {
    @Test fun dumpUsesOnlyCachedCountsAndFingerprintsWithoutFamilyContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "care-diagnostics-${UUID.randomUUID()}.db"
        val room = FamilyChatRoom.create("SECRET_FAMILY_NAME", listOf(
            FamilyChatMember(101, "@diagnostic_parent_bot", "SECRET_PARENT_NAME", "father"),
            FamilyChatMember(103, "@diagnostic_child_bot", "SECRET_CHILD_NAME", "son")))
        val epoch = UUID.randomUUID().toString()
        try {
            LocalStore(context, name).use { local ->
                local.familyCare.saveState(FamilyCareState(room.id, 103, epoch, 12, false, TelegramLedger.emptyState()))
                val event = FamilyEvent(UUID.randomUUID().toString(), "location",
                    JSONObject().put("latitude", 37.123456).put("longitude", 127.987654)
                        .put("accuracy", 15).put("source", "automatic"), "child", "2026-09-30T00:00:00Z", "relayed")
                local.familyCare.archiveEvent(room.id, 103, event)
                val outgoing = FamilyCareProtocol.outgoing(room, 101, 103, 103, "sync_request", JSONObject())
                local.familyCare.queuePacket(outgoing)
                FamilySyncDiagnostics.capture(context, local, room, 101, System.currentTimeMillis() + 600_000)
            }
            // The DB is closed: dump cannot take its lock, reopen it, or parse an event on main.
            val output = StringWriter().also { FamilySyncDiagnostics.dump(PrintWriter(it)) }.toString()
            assertTrue(output, output.contains("revision=12 archiveRows=1 archiveDays=1"))
            assertTrue(output, output.contains("pending=[sync_request:1]"))
            assertTrue(output, output.contains("carePending=1 careCompleted=0"))
            listOf(room.id, epoch, "SECRET", "37.123456", "127.987654", eventJsonMarker).forEach {
                assertFalse("Private content in diagnostic dump: $it", output.contains(it))
            }
        } finally { context.deleteDatabase(name) }
    }

    private val eventJsonMarker = "latitude"
}
