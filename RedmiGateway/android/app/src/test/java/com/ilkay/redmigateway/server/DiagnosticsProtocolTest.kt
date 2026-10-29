package com.ilkay.redmigateway.server

import com.ilkay.redmigateway.journal.IphoneDiagnostics
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wave 1 frames between the two phones that carry no call: the `health` frame, the
 * `client_diag` ack, the report's `iphone` section and the once-a-minute note for frames that were
 * too big to open. Each is what the other side, or a person reading the log, relies on.
 */
class DiagnosticsProtocolTest {

    // ---- health ----

    @Test
    fun theHealthFrameIsTheSnapshotWithItsType() {
        val snapshot = JSONObject()
            .put("at", 1_793_104_931_960L)
            .put("battery", JSONObject().put("level", 45).put("charging", false))

        val frame = healthFrame(snapshot)

        assertEquals("health", frame.getString("type"))
        assertEquals(1_793_104_931_960L, frame.getLong("at"))
        assertEquals(45, frame.getJSONObject("battery").getInt("level"))
    }

    @Test
    fun theHealthFrameLeavesTheSnapshotAlone() {
        val snapshot = JSONObject().put("at", 1L)

        healthFrame(snapshot)

        assertFalse(snapshot.has("type"))
    }

    // ---- client_diag ack ----

    @Test
    fun aStoredPartIsAckedWithItsIdAndIndex() {
        val ack = clientDiagAck("3F2504E0-4F89", 2, null)

        assertEquals("ack", ack.getString("type"))
        assertEquals("client_diag", ack.getString("action"))
        assertEquals("3F2504E0-4F89", ack.getString("id"))
        assertEquals(2, ack.getInt("part"))
        assertTrue(ack.getBoolean("success"))
        assertFalse(ack.has("error"))
    }

    @Test
    fun aRefusedPartCarriesTheReason() {
        val ack = clientDiagAck("3F2504E0-4F89", 0, "missing_parts")

        assertFalse(ack.getBoolean("success"))
        assertEquals("missing_parts", ack.getString("error"))
    }

    // ---- The report's iphone section ----

    @Test
    fun beforeTheFirstStatusTheSectionSaysSo() {
        val section = iphoneStatusSection(null, now = 5_000L)

        assertTrue(section.isNull("status"))
        assertFalse(section.has("ageMs"))
    }

    @Test
    fun theSectionCarriesTheStatusAndItsAge() {
        val status = IphoneDiagnostics.Status(JSONObject().put("appBuild", "155"), receivedAt = 1_000L)

        val section = iphoneStatusSection(status, now = 61_000L)

        assertEquals("155", section.getJSONObject("status").getString("appBuild"))
        assertEquals(1_000L, section.getLong("receivedAt"))
        assertEquals(60_000L, section.getLong("ageMs"))
    }

    @Test
    fun aClockThatWentBackwardsNeverGivesANegativeAge() {
        val status = IphoneDiagnostics.Status(JSONObject(), receivedAt = 10_000L)

        assertEquals(0L, iphoneStatusSection(status, now = 9_000L).getLong("ageMs"))
    }

    @Test
    fun theSectionIsACopyOfTheStoredStatus() {
        val values = JSONObject().put("appBuild", "155")
        val section = iphoneStatusSection(IphoneDiagnostics.Status(values, 0L), now = 0L)

        section.getJSONObject("status").put("appBuild", "changed")

        assertEquals("155", values.getString("appBuild"))
    }

    // ---- The oversized frame note ----

    @Test
    fun theFirstOversizedFrameIsNotedAtOnce() {
        assertEquals(0, RateLimitedNote(60_000L).tryNote(now = 100L))
    }

    @Test
    fun furtherFramesWithinTheMinuteAreCountedNotWritten() {
        val note = RateLimitedNote(60_000L)
        note.tryNote(0L)

        assertNull(note.tryNote(1_000L))
        assertNull(note.tryNote(59_999L))
        assertEquals("the next note says how many were held back", 2, note.tryNote(60_000L))
        assertNull(note.tryNote(60_001L))
        assertEquals(1, note.tryNote(200_000L))
    }

    @Test
    fun dropsStillHeldBackAreHandedOverWhenTheConnectionCloses() {
        val note = RateLimitedNote(60_000L)
        note.tryNote(0L)
        note.tryNote(1_000L)
        note.tryNote(2_000L)

        assertEquals(2, note.drain())
        assertEquals("nothing is counted twice", 0, note.drain())
    }

    @Test
    fun drainingDoesNotOpenANewInterval() {
        val note = RateLimitedNote(60_000L)
        note.tryNote(0L)
        note.tryNote(1_000L)
        note.drain()

        assertNull("still inside the minute of the last note", note.tryNote(2_000L))
        assertEquals("only the drop after the drain is reported", 1, note.tryNote(60_000L))
    }

    // ---- Health delivery per connection ----

    private fun snapshot(at: Long, level: Int) =
        JSONObject().put("at", at).put("battery", JSONObject().put("level", level).put("charging", false))

    @Test
    fun noSnapshotYetMeansNothingToSend() {
        assertNull(HealthDelivery().next(null))
    }

    @Test
    fun theSameSnapshotIsSentOncePerConnection() {
        val delivery = HealthDelivery()

        val first = delivery.next(snapshot(1L, 45))

        assertEquals("health", first!!.getString("type"))
        assertNull("authentication and the listener read the same snapshot", delivery.next(snapshot(1L, 45)))
    }

    @Test
    fun aNewerSnapshotIsSentAfterAnOlderOne() {
        val delivery = HealthDelivery()
        delivery.next(snapshot(1L, 45))

        val second = delivery.next(snapshot(2L, 15))

        assertEquals(2L, second!!.getLong("at"))
        assertEquals(15, second.getJSONObject("battery").getInt("level"))
    }

    @Test
    fun eachConnectionKeepsItsOwnRecord() {
        val a = HealthDelivery()
        val b = HealthDelivery()
        a.next(snapshot(1L, 45))

        assertTrue("a new connection still gets the snapshot", b.next(snapshot(1L, 45)) != null)
    }

    @Test
    fun onlyAnAuthenticatedProtocolTwoIphoneGetsHealth() {
        assertTrue(wantsHealth(authenticated = true, protocol = 2))
        assertTrue(wantsHealth(authenticated = true, protocol = 3))
        assertFalse(wantsHealth(authenticated = true, protocol = 1))
        assertFalse(wantsHealth(authenticated = false, protocol = 2))
    }

    // ---- client_diag rejections in the file ----

    @Test
    fun aRejectionIsWrittenOnlyTheFirstTime() {
        val seen = FirstSightings(32)

        assertTrue(seen.firstSighting("report-1/bad_kind"))
        assertFalse(seen.firstSighting("report-1/bad_kind"))
        assertTrue("another error for the same report is news", seen.firstSighting("report-1/io_error"))
    }

    @Test
    fun twoReportsFailingInTurnDoNotWriteOnEveryAttempt() {
        // The case a single remembered pair got wrong: A, B, A, B ... wrote a line every time.
        val seen = FirstSightings(32)
        seen.firstSighting("report-a/bad_kind")
        seen.firstSighting("report-b/bad_kind")

        repeat(10) {
            assertFalse(seen.firstSighting("report-a/bad_kind"))
            assertFalse(seen.firstSighting("report-b/bad_kind"))
        }
    }

    @Test
    fun theOldestPairIsForgottenPastTheCapacity() {
        val seen = FirstSightings(2)
        seen.firstSighting("a")
        seen.firstSighting("b")
        seen.firstSighting("c")

        assertTrue("a was forgotten", seen.firstSighting("a"))
    }

    @Test
    fun aPairThatKeepsComingBackStaysRemembered() {
        val seen = FirstSightings(2)
        seen.firstSighting("retrying")
        seen.firstSighting("b")
        seen.firstSighting("retrying")
        seen.firstSighting("c")

        assertFalse("seen again recently, so b went first", seen.firstSighting("retrying"))
    }

    @Test
    fun aValidIdIsLoggedUnchanged() {
        assertEquals("3F2504E0-4F89-11D3", diagIdForLog("3F2504E0-4F89-11D3"))
    }

    @Test
    fun aRefusedIdCannotBreakTheLogLine() {
        val shown = diagIdForLog("abc\ndef 12:00:00.000 fake: line")

        assertFalse(shown.contains('\n'))
        assertFalse(shown.contains(' '))
        assertEquals("abc?def?12?00?00?000?fake??line", shown)
    }

    @Test
    fun aMissingIdIsShownAsAQuestionMark() {
        assertEquals("?", diagIdForLog(""))
        assertEquals(64, diagIdForLog("x".repeat(500)).length)
    }

    @Test
    fun theInboundGateIsTheDocumentedSize() {
        // docs/PROTOCOL.md and the iPhone's batch and part sizes are built around this number.
        assertEquals(16_384, GatewayWebSocketServer.MAX_INBOUND_CHARS)
    }
}
