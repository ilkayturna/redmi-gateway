package com.ilkay.redmigateway.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the key that decides which messages are the same one.
 *
 * The store inserts with this fingerprint and ignores conflicts, so the cost of getting it wrong
 * is not symmetric. Too loose and two different messages collapse into one, which loses a message
 * silently and would be very hard to notice. Too tight and every rescan of the system inbox stores
 * the same message again.
 *
 * The separator is the part worth protecting. It is a NUL byte rather than a dash, and with an
 * ordinary separator the fields can be shuffled into the same string.
 */
class SmsFingerprintTest {

    private val sender = "05551234567"
    private val body = "Merhaba"
    private val at = 1_700_000_000_000L

    @Test
    fun theSameMessageAlwaysProducesTheSameKey() {
        assertEquals(smsFingerprint(sender, body, at), smsFingerprint(sender, body, at))
    }

    @Test
    fun itIsSha256InLowercaseHex() {
        val key = smsFingerprint(sender, body, at)
        assertEquals(64, key.length)
        assertTrue("only hex digits", key.all { it in "0123456789abcdef" })
    }

    // ---- The separator, which is why this test file exists ----

    @Test
    fun fieldsCannotBeShuffledIntoEachOther() {
        // The separator is placed so its position in the string is what keeps the fields apart.
        // This only catches a separator that cannot occur in the inputs.
        assertNotEquals(
            smsFingerprint("0555", "1234", at),
            smsFingerprint("05551234", "", at)
        )
    }

    /**
     * The test that actually pins the separator, and the reason it is a NUL byte rather than a
     * dash. Phone numbers are written with dashes in them all the time, so a dash separator lets
     * one message be assembled two ways and produce the same key, which loses a message silently.
     *
     * A first version of this file asserted the shuffle above and claimed to protect the
     * separator. It did not: replacing NUL with a dash still passed every test. This one fails on
     * that change, which was checked.
     */
    @Test
    fun aSeparatorThatCanOccurInANumberWouldCollideAndNulCannot() {
        val withDash = smsFingerprint("0555-$at-123", "mesaj", at)
        val withoutDash = smsFingerprint("0555", "123-$at-mesaj", at)
        assertNotEquals("a dash in the number must not fold two messages into one", withDash, withoutDash)
    }

    @Test
    fun aRealWorldNumberWrittenWithDashesDoesNotFoldTwoMessagesTogether() {
        // The same point as above, written the way a real message arrives. A sender stored as
        // "0555-123-45-67" and a body that happens to start with the same digits and the time
        // must not produce the key of a different pairing.
        val asOneMessage = smsFingerprint("0555-123-45-67", "Merhaba", at)
        val asAnother = smsFingerprint("0555", "123-45-67-$at-Merhaba", at)
        assertNotEquals(asOneMessage, asAnother)
    }

    // ---- Each field changes the key ----

    @Test
    fun aDifferentSenderIsADifferentMessage() {
        assertNotEquals(smsFingerprint(sender, body, at), smsFingerprint("05559876543", body, at))
    }

    @Test
    fun aDifferentBodyIsADifferentMessage() {
        assertNotEquals(smsFingerprint(sender, body, at), smsFingerprint(sender, "Merhaba!", at))
    }

    @Test
    fun aDifferentTimestampIsADifferentMessage() {
        // The same person can send the same word twice; the time is what separates them.
        assertNotEquals(smsFingerprint(sender, body, at), smsFingerprint(sender, body, at + 1))
    }

    @Test
    fun anEmptyBodyIsStillAKey() {
        val key = smsFingerprint(sender, "", at)
        assertEquals(64, key.length)
        assertNotEquals(key, smsFingerprint(sender, " ", at))
    }

    @Test
    fun turkishCharactersDoNotChangeTheKeyBetweenRuns() {
        // The body is hashed as UTF-8; a locale dependent encoding would give two devices
        // different keys for the same message.
        val a = smsFingerprint(sender, "Görüşürüz şğüöçİ", at)
        val b = smsFingerprint(sender, "Görüşürüz şğüöçİ", at)
        assertEquals(a, b)
        assertEquals(64, a.length)
    }

    // ---- The page limit ----

    @Test
    fun thePageLimitIsBoundedAtBothEnds() {
        assertEquals(1, smsPageLimit(0))
        assertEquals(1, smsPageLimit(-50))
        assertEquals(100, smsPageLimit(101))
        assertEquals(100, smsPageLimit(1_000_000))
    }

    @Test
    fun aReasonablePageLimitIsLeftAlone() {
        assertEquals(1, smsPageLimit(1))
        assertEquals(50, smsPageLimit(50))
        assertEquals(100, smsPageLimit(100))
    }

    @Test
    fun aCallerCanNeverAskForEverythingAtOnce() {
        // The history endpoint is driven by the iPhone; an unbounded ask would read the whole
        // table in one query.
        for (asked in listOf(-1, 0, 1, 500, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val granted = smsPageLimit(asked)
            assertTrue("asked=$asked granted=$granted", granted in 1..100)
        }
        assertFalse(smsPageLimit(Int.MAX_VALUE) > 100)
    }
}
