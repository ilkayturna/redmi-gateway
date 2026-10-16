package com.ilkay.redmigateway.telecom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the missed call decision and the key it is looked up by.
 *
 * This is what decides whether the person sees a missed call at all, so both directions cost
 * something. Report a call that was answered and the notification cries wolf; fail to report one
 * that rang and nobody knows they were called.
 *
 * The "none" sentinel is the part worth writing down. Telecom reports a call id of "none" or an
 * empty string on some paths, and the decision needs something to look the number up by, so the
 * last id that rang stands in. Getting that branch wrong makes missed calls disappear entirely
 * with nothing else looking unusual.
 */
class MissedCallDecisionTest {

    // ---- The key ----

    @Test
    fun aRealCallIdIsUsedAsIs() {
        assertEquals("abc-123", callUiKey("abc-123", "older"))
    }

    @Test
    fun theNoneSentinelFallsBackToTheLastRingingCall() {
        // This is the branch that keeps missed calls working on the paths that send "none".
        assertEquals("last-ring", callUiKey("none", "last-ring"))
        assertEquals("last-ring", callUiKey("", "last-ring"))
        assertEquals("last-ring", callUiKey("   ", "last-ring"))
    }

    @Test
    fun withNoFallbackThereIsNothingToReport() {
        // A null key means the caller posts nothing, which is right: no call to report.
        assertNull(callUiKey("none", null))
        assertNull(callUiKey("", null))
        assertNull(callUiKey("", null))
    }

    @Test
    fun aRealIdIsNeverReplacedByTheFallback() {
        // If this inverted, two calls could be confused with each other.
        assertEquals("current", callUiKey("current", "older"))
    }

    @Test
    fun theSentinelIsExactAndNotAContainsCheck() {
        // A call id that merely contains the word must still be treated as real.
        assertEquals("none-2", callUiKey("none-2", "older"))
        assertEquals("xnone", callUiKey("xnone", "older"))
        assertEquals("NONE", callUiKey("NONE", "older"))
    }

    // ---- The decision ----

    @Test
    fun aCallThatRangAndWasNeverAnsweredIsMissed() {
        assertTrue(wasMissed("05551234567", becameActive = false))
    }

    @Test
    fun aCallThatWasAnsweredIsNotMissed() {
        // It showed as an active call and then ended; that is a finished call, not a missed one.
        assertFalse(wasMissed("05551234567", becameActive = true))
    }

    @Test
    fun aCallWithNoRecordedNumberIsNotMissed() {
        // Nothing rang, so there is nobody to tell the person about.
        assertFalse(wasMissed(null, becameActive = false))
        assertFalse(wasMissed(null, becameActive = true))
    }

    @Test
    fun aHiddenNumberStillCountsAsMissed() {
        // An empty string is a recorded number in the sense that matters: the call did ring.
        assertTrue(wasMissed("", becameActive = false))
    }

    @Test
    fun theTwoInputsAreNotInterchangeable() {
        // Guards against the arguments being passed the wrong way round at a call site.
        val missedAndAnswered = wasMissed("05551234567", becameActive = true)
        val missedAndRang = wasMissed("05551234567", becameActive = false)
        assertFalse(missedAndAnswered)
        assertTrue(missedAndRang)
    }

    @Test
    fun everyCombinationIsDecided() {
        // No branch may be left to throw or fall through; each of the four inputs has an answer.
        val answers = listOf(
            wasMissed("05551234567", false), wasMissed("05551234567", true),
            wasMissed(null, false), wasMissed(null, true),
        )
        assertEquals(listOf(true, false, false, false), answers)
    }
}
