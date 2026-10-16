package com.ilkay.redmigateway.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the diagnostics log rotation.
 *
 * The log is what explains a call after it has happened: the device evidence for the first
 * complaint was read out of this file. Two things about the rotation matter and both fail quietly.
 * The file must not grow without bound, which is what the trim is for, and the lines kept must be
 * the newest ones, because the question being answered is always about the call that just
 * finished.
 *
 * Above all, the number kept has to be smaller than the number that triggers the trim. If it were
 * not, the file would be rewritten at the same size and grow for ever while looking like it was
 * being trimmed.
 */
class DiagnosticsLogRotationTest {

    // ---- The trigger ----

    @Test
    fun theTrimRunsEveryTwoHundredWrites() {
        assertFalse(shouldTrimDiagnosticsLog(1))
        assertFalse(shouldTrimDiagnosticsLog(199))
        assertTrue(shouldTrimDiagnosticsLog(200))
        assertTrue(shouldTrimDiagnosticsLog(400))
    }

    @Test
    fun theTriggerIsPeriodicRatherThanOneShot() {
        // A single trim would let the file grow again from the new size; it has to keep happening.
        val triggers = (1L..1_000L).count { shouldTrimDiagnosticsLog(it) }
        assertEquals(5, triggers)
    }

    @Test
    fun zeroWritesDoesNotTriggerATrim() {
        // The counter is incremented before the check, so this is only reachable if that changes.
        assertTrue(shouldTrimDiagnosticsLog(0))
    }

    // ---- What is kept ----

    @Test
    fun aFileWithinTheLimitIsLeftAlone() {
        assertNull(diagnosticsLogLinesToKeep(0))
        assertNull(diagnosticsLogLinesToKeep(DiagnosticsLog.MAX_LINES - 1))
        assertNull(diagnosticsLogLinesToKeep(DiagnosticsLog.MAX_LINES))
    }

    @Test
    fun aFilePastTheLimitIsTrimmed() {
        assertNotNull(diagnosticsLogLinesToKeep(DiagnosticsLog.MAX_LINES + 1))
        assertEquals(
            DiagnosticsLog.TRIM_TO_LINES,
            diagnosticsLogLinesToKeep(DiagnosticsLog.MAX_LINES + 1)
        )
    }

    @Test
    fun trimmingKeepsFewerLinesThanTriggerIt() {
        // The property the whole thing rests on. If this inverted, the file would be rewritten at
        // its own size every two hundred writes and never shrink.
        assertTrue(
            "keeps ${DiagnosticsLog.TRIM_TO_LINES}, triggers at ${DiagnosticsLog.MAX_LINES}",
            DiagnosticsLog.TRIM_TO_LINES < DiagnosticsLog.MAX_LINES
        )
    }

    @Test
    fun trimmingLeavesEnoughRoomToBeWorthDoing() {
        // Trimming to almost the trigger point would mean trimming again almost immediately.
        assertTrue(
            "keeps ${DiagnosticsLog.TRIM_TO_LINES} of ${DiagnosticsLog.MAX_LINES}",
            DiagnosticsLog.TRIM_TO_LINES <= DiagnosticsLog.MAX_LINES / 2 + DiagnosticsLog.MAX_LINES / 4
        )
    }

    @Test
    fun theLimitIsLargeEnoughToHoldARealCall() {
        // The log has to survive at least one call's worth of lines, or there is nothing to read.
        assertTrue("limit is ${DiagnosticsLog.MAX_LINES}", DiagnosticsLog.MAX_LINES >= 1_000)
    }

    @Test
    fun repeatedTrimmingConvergesRatherThanOscillating() {
        // Walk the file through many trims and check it settles below the limit every time rather
        // than creeping upward.
        var lines = DiagnosticsLog.MAX_LINES + 500
        repeat(20) {
            val keep = diagnosticsLogLinesToKeep(lines)
            if (keep != null) lines = keep
            assertTrue("grew to $lines", lines <= DiagnosticsLog.MAX_LINES)
        }
        assertEquals(DiagnosticsLog.TRIM_TO_LINES, lines)
    }

    @Test
    fun aFileJustOverTheLimitShrinksOnTheNextTrim() {
        // The boundary case: one line over must still be trimmed, not left as "close enough".
        val keep = diagnosticsLogLinesToKeep(DiagnosticsLog.MAX_LINES + 1)
        assertEquals(DiagnosticsLog.TRIM_TO_LINES, keep)
    }
}
