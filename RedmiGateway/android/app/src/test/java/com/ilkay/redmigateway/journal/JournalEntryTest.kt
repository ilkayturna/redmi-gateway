package com.ilkay.redmigateway.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the journals hand the iPhone beyond the message itself: whether the Redmi already
 * announced an entry through Apple (CONS-4), the order a batch is written in (CONS-5), and when a
 * late-stored provider row is the same message as a fallback entry (CONS-11).
 */
class JournalEntryTest {

    private fun sms(pushed: Boolean = false, replaces: Long? = null) =
        SmsJournal.Entry(7, 1001, "+905321234567", "Kod 1234", 1_793_000_000_000, "inbox", false, pushed, replaces)

    // ---- "pushed" and "replaces" on the wire ----

    @Test
    fun anSmsThatWasNotPushedCarriesNoFlag() {
        // Older iPhone builds ignore unknown keys, but the flag must not claim a push that never happened.
        val json = sms().toJson()
        assertFalse(json.has("pushed"))
        assertFalse(json.has("replaces"))
    }

    @Test
    fun aPushedSmsSaysSo() {
        assertTrue(sms(pushed = true).toJson().getBoolean("pushed"))
    }

    @Test
    fun aProviderCopyNamesTheFallbackItReplaces() {
        assertEquals(-1_793_000_000_123L, sms(replaces = -1_793_000_000_123L).toJson().getLong("replaces"))
    }

    @Test
    fun theExistingSmsFieldsAreUnchanged() {
        val json = sms(pushed = true).toJson()
        assertEquals(setOf("seq", "id", "address", "body", "date", "box", "read", "pushed"), json.keys().asSequence().toSet())
        assertEquals(1001L, json.getLong("id"))
        assertEquals(7L, json.getLong("seq"))
    }

    @Test
    fun aPushedMissedCallSaysSo() {
        val entry = CallJournal.Entry(3, 55, "05321234567", "missed", 1_793_000_000_000, 0, null, pushed = true)
        assertTrue(entry.toJson().getBoolean("pushed"))
    }

    @Test
    fun anOrdinaryCallCarriesNoFlagAndKeepsItsFields() {
        val json = CallJournal.Entry(3, 55, "05321234567", "incoming", 1_793_000_000_000, 61, "iphone").toJson()
        assertFalse(json.has("pushed"))
        assertEquals(setOf("seq", "id", "number", "type", "date", "duration", "answeredOn"), json.keys().asSequence().toSet())
    }

    // ---- write order ----

    @Test
    fun aBatchIsWrittenOldestFirst() {
        // The provider scan returns newest first; the oldest must get the lowest seq so the newest
        // message is announced last and sits on top of the iPhone's stack.
        assertEquals(listOf(10L, 11L, 12L), smsWriteOrder(listOf(12L, 11L, 10L)) { it })
    }

    @Test
    fun statusChangesOfOlderRowsJoinTheSameAscendingPass() {
        assertEquals(listOf(3L, 40L, 41L), smsWriteOrder(listOf(41L, 40L, 3L)) { it })
    }

    // ---- a late provider row against a fallback ----

    private val sentAt = 1_793_000_000_000L

    @Test
    fun theSameMessageStoredSecondsLaterIsTheFallback() {
        assertTrue(isLateCopyOfFallback("+905321234567", sentAt, "+905321234567", sentAt + 9_000, sentAt))
    }

    @Test
    fun theSenderSpellingDoesNotMatter() {
        assertTrue(isLateCopyOfFallback("+905321234567", sentAt, "05321234567", sentAt + 9_000, 0))
    }

    @Test
    fun anotherSenderIsNotTheFallback() {
        assertFalse(isLateCopyOfFallback("+905321234567", sentAt, "+905329999999", sentAt + 9_000, sentAt))
    }

    @Test
    fun theNetworkSendTimeMatchesEvenWhenStoredMuchLater() {
        // The network held the message for an hour: DATE is late, DATE_SENT still matches.
        assertTrue(isLateCopyOfFallback("TURKCELL", sentAt, "TURKCELL", sentAt + 3_600_000, sentAt + 1_000))
    }

    @Test
    fun withoutASendTimeTheStoreWindowDecides() {
        assertTrue(isLateCopyOfFallback("TURKCELL", sentAt, "TURKCELL", sentAt + LATE_STORE_WINDOW_MS, 0))
        assertFalse(isLateCopyOfFallback("TURKCELL", sentAt, "TURKCELL", sentAt + LATE_STORE_WINDOW_MS + 1, 0))
    }

    @Test
    fun aMessageFromBeforeTheFallbackIsNotIt() {
        assertFalse(isLateCopyOfFallback("TURKCELL", sentAt, "TURKCELL", sentAt - 3 * 60_000, 0))
    }
}
