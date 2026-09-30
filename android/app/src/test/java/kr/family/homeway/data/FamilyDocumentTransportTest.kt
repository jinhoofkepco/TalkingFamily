package kr.family.homeway.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

class FamilyDocumentTransportTest {
    private val room = FamilyChatRoom.create("가족", listOf(
        FamilyChatMember(101, "@member101_bot", "서인", "son"),
        FamilyChatMember(202, "@member202_bot", "아빠", "father"),
        FamilyChatMember(303, "@member303_bot", "서아", "daughter")))

    @Test fun boundedFilePreserves128OriginalIdsDigestsAndUnicodeWithoutNestedWrappers() {
        val messages = (1..140).map { message("가족 기록 $it 👨‍👩‍👧‍👦") }
        val file = FamilyDocumentProtocol.encode(messages.map(FamilyChatProtocol::message), room.id, 101, 202)!!
        assertEquals(128, file.count)
        assertTrue(file.bytes.size <= 256 * 1024)
        val prepared = FamilyDocumentProtocol.prepare(update(file), room, 202)!!
        val originals = FamilyDocumentProtocol.decode(prepared, file.bytes, room, 202)
        val decoded = originals.map { FamilyChatProtocol.receive(it, room, 202)!!.message!! }
        assertEquals(messages.take(128).map { it.id }, decoded.map { it.id })
        assertEquals(messages.take(128).map { it.digest }, decoded.map { it.digest })
        assertEquals(messages.take(128).map { it.text }, decoded.map { it.text })
    }

    @Test fun smallBacklogStaysTextAndRawUtf8BudgetBoundsTheFilePrefix() {
        assertNull(FamilyDocumentProtocol.encode(List(3) { FamilyChatProtocol.message(message("짧은 기록")) }, room.id, 101, 202))
        val text = "가".repeat(1400)
        val texts = List(128) { FamilyChatProtocol.message(message(text)) }
        val file = FamilyDocumentProtocol.encode(texts, room.id, 101, 202)!!
        assertTrue(file.count in 4..127)
        val prepared = FamilyDocumentProtocol.prepare(update(file), room, 202)!!
        assertTrue(prepared.rawBytes <= 512 * 1024)
        assertEquals(file.count, FamilyDocumentProtocol.decode(prepared, file.bytes, room, 202).size)
    }

    @Test fun captionRosterSenderForwardAndMetadataValidationHappenBeforeAnyDownload() {
        val file = validFile()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("message").getJSONObject("from").put("id", 999) },
            { it.getJSONObject("message").getJSONObject("chat").put("id", 303) },
            { it.getJSONObject("message").getJSONObject("from").put("is_bot", false) },
            { it.getJSONObject("message").put("forward_origin", JSONObject()) },
            { it.getJSONObject("message").getJSONObject("document").put("file_size", file.bytes.size + 1) },
            { it.getJSONObject("message").getJSONObject("document").put("file_id", "https://evil.test/file") },
            { editCaption(it) { caption -> caption.put("extra", true) } },
            { editCaption(it) { caption -> caption.put("receiverId", 303) } },
            { editCaption(it) { caption -> caption.put("count", 129) } },
            { editCaption(it) { caption -> caption.put("rawBytes", 512 * 1024 + 1) } }
        )
        for (mutate in mutations) assertNull(FamilyDocumentProtocol.prepare(update(file).also(mutate), room, 202))
    }

    @Test fun corruptHashTruncatedGzipAndInflatedRawSizeAreTerminalSafeRejections() {
        val file = validFile(); val prepared = FamilyDocumentProtocol.prepare(update(file), room, 202)!!
        val corrupt = file.bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertInvalid { FamilyDocumentProtocol.decode(prepared, corrupt, room, 202) }
        val truncated = file.bytes.copyOf(file.bytes.size - 3)
        assertInvalid { FamilyDocumentProtocol.decode(prepared.copy(compressedBytes = truncated.size, sha256 = FamilyDocumentProtocol.hash(truncated)), truncated, room, 202) }
        val bomb = gzip(ByteArray(512 * 1024 + 1) { 65 })
        assertInvalid { FamilyDocumentProtocol.decode(prepared.copy(rawBytes = 512 * 1024, compressedBytes = bomb.size,
            sha256 = FamilyDocumentProtocol.hash(bomb)), bomb, room, 202) }
        val malformedUtf8 = gzip(byteArrayOf(-61, 40))
        assertInvalid { FamilyDocumentProtocol.decode(prepared.copy(rawBytes = 2, compressedBytes = malformedUtf8.size,
            sha256 = FamilyDocumentProtocol.hash(malformedUtf8)), malformedUtf8, room, 202) }
    }

    @Test fun oneForgedOriginalOrNestedWrapperRejectsTheWholeDocument() {
        val messages = List(17) { message("인증된 원본") }
        val valid = messages.map(FamilyChatProtocol::message)
        val forged = FamilyChatProtocol.message(messages.last().copy(senderId = 303))
        val nested = FamilyTransportProtocol.batch(valid.take(2))
        for (bad in listOf(forged, nested, FamilyTransportProtocol.fileCapability(42, false))) {
            val file = FamilyDocumentProtocol.encode(valid.dropLast(1) + bad, room.id, 101, 202)!!
            val prepared = FamilyDocumentProtocol.prepare(update(file), room, 202)!!
            assertInvalid { FamilyDocumentProtocol.decode(prepared, file.bytes, room, 202) }
        }
        val care = FamilyCareProtocol.envelope(room, 101, 101, "child_event", JSONObject()).toString()
        val siblingFile = FamilyDocumentProtocol.encode(valid.dropLast(1) + care, room.id, 101, 303)!!
        val prepared = FamilyDocumentProtocol.prepare(update(siblingFile), room, 303)!!
        assertInvalid { FamilyDocumentProtocol.decode(prepared, siblingFile.bytes, room, 303) }
    }

    @Test fun latestPreviewValidatesEveryBatchOriginalWithoutNegotiationOrStorageMutation() {
        val store = FamilyChatExchangeTest.MemoryStore()
        val client = TelegramClient("202:${"a".repeat(32)}") { _, _, _, _ -> error("No network in preview") }
        val transport = FamilyTransport(client, store, room, 202, now = { 1_000_000 })
        val head = FamilyCareProtocol.outgoing(room, 101, 101, 202, "care_location_head", JSONObject()).text
        val original = FamilyChatProtocol.message(message("지난 기록"))
        val batch = textUpdate(FamilyTransportProtocol.batch(listOf(original, head)))
        assertEquals(1, transport.latestPreviewUpdates(batch).size)
        assertEquals(1, transport.latestPreviewUpdates(textUpdate(head)).size)
        assertEquals(0L, store.meta("transport:${room.id}:101:supportedUntil"))
        assertEquals(0L, store.meta("transport:${room.id}:101:latestSupportedUntil"))
        val forged = FamilyChatProtocol.message(message("다른 발신자").copy(senderId = 303))
        assertTrue(transport.latestPreviewUpdates(textUpdate(FamilyTransportProtocol.batch(listOf(forged, head)))).isEmpty())
        assertTrue(transport.latestPreviewUpdates(batch.also { it.getJSONObject("message").put("forward_origin", JSONObject()) }).isEmpty())
    }

    private fun message(text: String) = FamilyChatMessage(UUID.randomUUID().toString(), room.id, 101, text, "2026-09-29T12:00:00Z")
    private fun validFile() = FamilyDocumentProtocol.encode(List(17) { FamilyChatProtocol.message(message("원본 기록 $it")) }, room.id, 101, 202)!!
    private fun textUpdate(text: String) = JSONObject().put("message", JSONObject().put("text", text)
        .put("from", JSONObject().put("id", 101).put("is_bot", true)).put("chat", JSONObject().put("id", 101).put("type", "private")))
    private fun update(file: EncodedFamilyDocument) = textUpdate("").apply { getJSONObject("message").apply {
        remove("text"); put("caption", file.caption); put("document", JSONObject().put("file_id", "safe_file").put("file_size", file.bytes.size))
    } }
    private fun editCaption(update: JSONObject, mutate: (JSONObject) -> Unit) {
        val message = update.getJSONObject("message")
        message.put("caption", JSONObject(message.getString("caption")).also(mutate).toString())
    }
    private fun gzip(raw: ByteArray) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
    private fun assertInvalid(block: () -> Unit) {
        val error = assertThrows(InvalidFamilyDocumentException::class.java) { block() }
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains("safe_file"))
    }
}
