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
    fun theInboundGateIsTheDocumentedSize() {
        // docs/PROTOCOL.md and the iPhone's batch and part sizes are built around this number.
        assertEquals(16_384, GatewayWebSocketServer.MAX_INBOUND_CHARS)
    }
}
