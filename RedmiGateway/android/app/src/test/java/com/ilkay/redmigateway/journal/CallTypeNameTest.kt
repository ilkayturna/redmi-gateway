package com.ilkay.redmigateway.journal

import android.provider.CallLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the names the journal gives call log types.
 *
 * The strings are load bearing rather than cosmetic. The missed call notification is sent when
 * the journal reports "missed", so a typo here stops the iPhone being told about a missed call
 * and nothing else would look wrong: the call would simply never appear on the other phone.
 *
 * Two types collapsing onto the same string would misreport just as quietly, so the uniqueness of
 * the names is asserted as well as each individual mapping.
 */
class CallTypeNameTest {

    /** Every type the journal maps, paired with the name it must produce. */
    private val expected = listOf(
        CallLog.Calls.INCOMING_TYPE to "incoming",
        CallLog.Calls.OUTGOING_TYPE to "outgoing",
        CallLog.Calls.MISSED_TYPE to "missed",
        CallLog.Calls.REJECTED_TYPE to "declined",
        CallLog.Calls.BLOCKED_TYPE to "blocked",
        CallLog.Calls.VOICEMAIL_TYPE to "voicemail",
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE to "answered_elsewhere",
    )

    @Test
    fun everyKnownTypeMapsToItsName() {
        for ((type, name) in expected) {
            assertEquals("type=$type", name, callTypeName(type))
        }
    }

    @Test
    fun aMissedCallIsCalledMissed() {
        // The one string another component depends on by name.
        assertEquals("missed", callTypeName(CallLog.Calls.MISSED_TYPE))
    }

    @Test
    fun noTwoTypesShareAName() {
        val names = expected.map { callTypeName(it.first) }
        assertEquals("duplicate names: $names", names.size, names.toSet().size)
    }

    @Test
    fun anUnknownTypeBecomesOtherRatherThanCrashing() {
        // Call logs carry types this app does not model; they must not take the import down.
        assertNotEquals("", callTypeName(-1))
        assertEquals("other", callTypeName(-1))
        assertEquals("other", callTypeName(0))
        assertEquals("other", callTypeName(9_999))
        assertEquals("other", callTypeName(Int.MAX_VALUE))
        assertEquals("other", callTypeName(Int.MIN_VALUE))
    }

    @Test
    fun anUnknownTypeIsNotMistakenForAMissedCall() {
        // Otherwise an unmodelled type would buzz the iPhone about a call nobody missed.
        for (unknown in listOf(-1, 0, 42, 9_999, Int.MIN_VALUE)) {
            assertNotEquals("unknown=$unknown", "missed", callTypeName(unknown))
        }
    }

    @Test
    fun theNamesAreLowercaseAndFreeOfSpaces() {
        // They travel to the iPhone inside JSON and are compared by name over there.
        for ((_, name) in expected) {
            assertEquals("name=$name", name.lowercase(), name)
            assertEquals("name=$name", -1, name.indexOf(' '))
        }
    }

    @Test
    fun theAnsweredElsewhereNameDoesNotContainTheWordMissed() {
        // A caller who hung up before answering is not the same as an unanswered call.
        val answeredElsewhere = callTypeName(CallLog.Calls.ANSWERED_EXTERNALLY_TYPE)
        assertEquals(-1, answeredElsewhere.indexOf("missed"))
        assertNotEquals("missed", answeredElsewhere)
    }
}
