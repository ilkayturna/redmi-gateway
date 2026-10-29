package com.ilkay.redmigateway.journal

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins how large iPhone reports are received in parts.
 *
 * The iPhone deletes a report once its last part is acked, so the two things that must never go
 * wrong are a success for bytes that are not on disk and a failure that makes the iPhone resend the
 * same report for ever. The folder cap matters as much: these files sit next to the call evidence.
 */
class IosDiagStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    // Real time, because files written by the store carry real modification times and the cap
    // compares them with the times the tests set on older files.
    private val now = System.currentTimeMillis()
    private fun store(dir: File = File(temp.root, "ios-diag")) = IosDiagStore(dir) { now }

    private fun frame(
        id: String = "3F2504E0-4F89-11D3-9A0C-0305E82C3301",
        kind: String = "metrickit-crash",
        part: Int = 0,
        parts: Int = 1,
        data: ByteArray = "hello".toByteArray(),
    ) = JSONObject()
        .put("type", "client_diag")
        .put("id", id)
        .put("kind", kind)
        .put("createdAt", "2026-10-29T08:00:00Z")
        .put("part", part)
        .put("parts", parts)
        .put("enc", "base64")
        .put("data", java.util.Base64.getEncoder().encodeToString(data))

    private fun valid(json: JSONObject): IosDiagStore.Part {
        val parsed = IosDiagStore.parse(json)
        assertTrue("expected a valid part, got ${(parsed as? IosDiagStore.Parsed.Invalid)?.error}",
            parsed is IosDiagStore.Parsed.Valid)
        return (parsed as IosDiagStore.Parsed.Valid).part
    }

    private fun errorOf(json: JSONObject): String? = (IosDiagStore.parse(json) as? IosDiagStore.Parsed.Invalid)?.error

    // ---- Validation ----

    @Test
    fun everyKindTheContractNamesIsAccepted() {
        for (kind in IosDiagStore.KNOWN_KINDS) assertNull(kind, errorOf(frame(kind = kind)))
    }

    @Test
    fun aNewKindWithASafeShapeIsAcceptedSoANewerIphoneDoesNotRetryForEver() {
        assertNull(errorOf(frame(kind = "metrickit-launch")))
    }

    @Test
    fun aKindThatCouldEscapeTheFolderIsRefused() {
        assertEquals("bad_kind", errorOf(frame(kind = "../x")))
        assertEquals("bad_kind", errorOf(frame(kind = "Crash")))
        assertEquals("bad_kind", errorOf(frame(kind = "")))
    }

    @Test
    fun theIdMustBeEightToSixtyFourLettersDigitsOrDashes() {
        assertNull(errorOf(frame(id = "abcd1234")))
        assertNull(errorOf(frame(id = "a".repeat(64))))
        assertEquals("bad_id", errorOf(frame(id = "abc1234")))
        assertEquals("bad_id", errorOf(frame(id = "a".repeat(65))))
        assertEquals("bad_id", errorOf(frame(id = "../../etc/x")))
        assertEquals("bad_id", errorOf(frame(id = "abcd_1234")))
    }

    @Test
    fun partsMustBeBetweenOneAndSixtyFour() {
        assertNull(errorOf(frame(parts = 64, part = 63)))
        assertEquals("bad_parts", errorOf(frame(parts = 0)))
        assertEquals("bad_parts", errorOf(frame(parts = 65)))
    }

    @Test
    fun thePartMustBeInsideTheCount() {
        assertEquals("bad_part", errorOf(frame(part = -1, parts = 3)))
        assertEquals("bad_part", errorOf(frame(part = 3, parts = 3)))
        assertNull(errorOf(frame(part = 2, parts = 3)))
    }

    @Test
    fun onlyBase64IsUnderstood() {
        assertEquals("bad_enc", errorOf(frame().put("enc", "hex")))
        assertNull(errorOf(frame().apply { remove("enc") }))
        assertEquals("bad_data", errorOf(frame().put("data", "@@not base64@@")))
    }

    @Test
    fun aPartOfSixThousandBytesFitsAndAnOversizedOneIsRefused() {
        assertNull(errorOf(frame(data = ByteArray(6000))))
        assertNull(errorOf(frame(data = ByteArray(IosDiagStore.MAX_PART_BYTES))))
        assertEquals("too_large", errorOf(frame(data = ByteArray(IosDiagStore.MAX_PART_BYTES + 1))))
    }

    // ---- Storing and joining ----

    @Test
    fun aSinglePartReportIsStoredUnderItsIdAndKind() {
        val store = store()
        val outcome = store.put(valid(frame(data = "{\"crash\":1}".toByteArray())))

        val completed = (outcome as IosDiagStore.Outcome.Stored).completed
        assertNotNull(completed)
        assertEquals("3F2504E0-4F89-11D3-9A0C-0305E82C3301.metrickit-crash.json", completed!!.file.name)
        assertEquals("{\"crash\":1}", completed.file.readText())
        assertEquals(11L, completed.bytes)
        assertEquals(1, completed.parts)
        assertFalse("the parts folder is gone", File(File(temp.root, "ios-diag"), "partial").listFiles()?.isNotEmpty() == true)
    }

    @Test
    fun partsAreJoinedInIndexOrderOnceTheLastArrives() {
        val store = store()
        assertNull((store.put(valid(frame(part = 0, parts = 3, data = "aa".toByteArray()))) as IosDiagStore.Outcome.Stored).completed)
        assertNull((store.put(valid(frame(part = 1, parts = 3, data = "bb".toByteArray()))) as IosDiagStore.Outcome.Stored).completed)
        val last = store.put(valid(frame(part = 2, parts = 3, data = "cc".toByteArray())))

        val completed = (last as IosDiagStore.Outcome.Stored).completed!!
        assertEquals("aabbcc", completed.file.readText())
        assertEquals(3, completed.parts)
    }

    @Test
    fun aRepeatedPartOverwritesTheEarlierCopy() {
        val store = store()
        store.put(valid(frame(part = 0, parts = 2, data = "old".toByteArray())))
        store.put(valid(frame(part = 0, parts = 2, data = "new".toByteArray())))
        val last = store.put(valid(frame(part = 1, parts = 2, data = "!".toByteArray())))

        assertEquals("new!", (last as IosDiagStore.Outcome.Stored).completed!!.file.readText())
    }

    @Test
    fun aResendAfterALostAckIsAcceptedWithoutWritingAgain() {
        val store = store()
        val first = (store.put(valid(frame(data = "report".toByteArray()))) as IosDiagStore.Outcome.Stored).completed!!

        val again = store.put(valid(frame(data = "something else".toByteArray())))

        assertSame(IosDiagStore.Outcome.AlreadyComplete, again)
        assertEquals("report", first.file.readText())
    }

    @Test
    fun theLastPartWithEarlierPartsMissingFailsSoTheIphoneKeepsTheReport() {
        val store = store()
        store.put(valid(frame(part = 0, parts = 3)))
        val last = store.put(valid(frame(part = 2, parts = 3)))

        assertEquals("missing_parts", (last as IosDiagStore.Outcome.Failed).error)
        assertEquals("missing=1", last.detail)
    }

    @Test
    fun theMissingPartArrivingLaterStillCompletesTheReport() {
        val store = store()
        store.put(valid(frame(part = 0, parts = 3, data = "a".toByteArray())))
        store.put(valid(frame(part = 2, parts = 3, data = "c".toByteArray())))
        val outcome = store.put(valid(frame(part = 1, parts = 3, data = "b".toByteArray())))

        assertEquals("abc", (outcome as IosDiagStore.Outcome.Stored).completed!!.file.readText())
    }

    @Test
    fun aDifferentPartCountForTheSameIdDoesNotMixWithTheEarlierParts() {
        val store = store()
        store.put(valid(frame(part = 0, parts = 3, data = "x".toByteArray())))
        store.put(valid(frame(part = 0, parts = 2, data = "a".toByteArray())))
        val outcome = store.put(valid(frame(part = 1, parts = 2, data = "b".toByteArray())))

        assertEquals("ab", (outcome as IosDiagStore.Outcome.Stored).completed!!.file.readText())
    }

    @Test
    fun anUnwritableFolderFailsInsteadOfClaimingSuccess() {
        val blocker = temp.newFile("ios-diag")
        val outcome = store(blocker).put(valid(frame()))

        assertEquals("io_error", (outcome as IosDiagStore.Outcome.Failed).error)
    }

    // ---- The folder cap ----

    private fun oldReport(dir: File, name: String, bytes: Int, modified: Long): File {
        dir.mkdirs()
        return File(dir, "$name.metrickit-metric.json").apply {
            writeBytes(ByteArray(bytes))
            setLastModified(modified)
        }
    }

    @Test
    fun theFolderKeepsAtMostFiftyReportsAndDropsTheOldestFirst() {
        val dir = File(temp.root, "ios-diag")
        val old = (0 until IosDiagStore.MAX_FILES).map { oldReport(dir, "report-%04d".format(it), 10, now - 100_000L + it) }

        val completed = (store(dir).put(valid(frame())) as IosDiagStore.Outcome.Stored).completed!!

        val left = dir.listFiles()!!.filter { it.isFile && it.name.endsWith(".json") }
        assertEquals(IosDiagStore.MAX_FILES, left.size)
        assertFalse("the oldest is gone", old.first().exists())
        assertTrue("the second oldest stays", old[1].exists())
        assertTrue("the new report stays", completed.file.exists())
        assertEquals(1, completed.pruned)
    }

    @Test
    fun theFolderStaysWithinTwoMegabytesAndTheNewReportIsNeverTheOneDropped() {
        val dir = File(temp.root, "ios-diag")
        val a = oldReport(dir, "report-aaaa", 900 * 1024, now - 3_000L)
        val b = oldReport(dir, "report-bbbb", 900 * 1024, now - 2_000L)

        val completed = (store(dir).put(valid(frame(data = ByteArray(6000)))) as IosDiagStore.Outcome.Stored).completed!!
        // 1.8 MB plus 6 KB is still within the cap.
        assertTrue(a.exists() && b.exists() && completed.file.exists())

        val c = oldReport(dir, "report-cccc", 900 * 1024, now - 1_000L)
        val second = (store(dir).put(valid(frame(id = "second-report-1", data = ByteArray(10)))) as IosDiagStore.Outcome.Stored).completed!!

        assertFalse("the oldest went first", a.exists())
        assertTrue(b.exists() && c.exists() && second.file.exists())
        val total = dir.listFiles()!!.filter { it.isFile }.sumOf { it.length() }
        assertTrue("total $total", total <= IosDiagStore.MAX_DIR_BYTES)
    }

    @Test
    fun partsOfAReportNeverFinishedAgeOutAfterAWeek() {
        val dir = File(temp.root, "ios-diag")
        val store = store(dir)
        store.put(valid(frame(id = "stale-report-1", part = 0, parts = 2)))
        val staleFolder = File(File(dir, "partial"), "stale-report-1.metrickit-crash.2")
        assertTrue(staleFolder.isDirectory)
        val longAgo = now - IosDiagStore.PARTIAL_MAX_AGE_MS - 1
        staleFolder.listFiles()!!.forEach { it.setLastModified(longAgo) }
        staleFolder.setLastModified(longAgo)
        store.put(valid(frame(id = "fresh-report-1", part = 0, parts = 2)))

        store.prune()

        assertFalse("a week-old unfinished report is dropped", staleFolder.exists())
        assertTrue(File(File(dir, "partial"), "fresh-report-1.metrickit-crash.2").isDirectory)
    }

    @Test
    fun aTemporaryFileLeftByACrashIsCleanedUp() {
        val dir = File(temp.root, "ios-diag").apply { mkdirs() }
        val leftover = File(dir, "x.metrickit-crash.json.tmp").apply { writeText("half") }

        store(dir).prune()

        assertFalse(leftover.exists())
    }

    // ---- The atomic write ----

    @Test
    fun anAtomicWriteReplacesTheOldContentAndLeavesNoTemporaryFile() {
        val target = File(temp.root, "iphone-status.json").apply { writeText("old") }

        writeAtomically(target, "new".toByteArray())

        assertArrayEquals("new".toByteArray(), target.readBytes())
        assertFalse(File(temp.root, "iphone-status.json.tmp").exists())
    }
}
