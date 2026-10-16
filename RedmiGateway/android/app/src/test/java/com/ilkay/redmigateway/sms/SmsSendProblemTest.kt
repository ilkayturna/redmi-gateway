package com.ilkay.redmigateway.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the gateway will and will not send as a text message.
 *
 * Sending is a paid action on the user's SIM, so the checks before it matter. Two directions of
 * error, and both are worse than they look: too strict and a real number quietly fails to send,
 * too loose and the message goes somewhere the person did not intend.
 *
 * The shape allowed is an optional plus followed by three to fifteen digits, which is the E.164
 * limit. The recipient is normalised first, so the punctuation a phone keyboard produces does not
 * count against it.
 */
class SmsSendProblemTest {

    private fun check(rawRecipient: String, message: String = "Merhaba"): String? =
        smsSendProblem(SmsSender.normalizeRecipient(rawRecipient), message)

    // ---- Normalising, which happens before the check ----

    @Test
    fun thePunctuationAPhoneKeyboardProducesIsStripped() {
        val forms = listOf(
            "+90 555 123 45 67",
            "+90 (555) 123-45-67",
            "+90.555.123.45.67",
            "  +905551234567  ",
        )
        for (form in forms) {
            assertEquals("form=$form", "+905551234567", SmsSender.normalizeRecipient(form))
        }
    }

    @Test
    fun normalisingDoesNotEatTheLeadingPlus() {
        assertEquals("+905551234567", SmsSender.normalizeRecipient("+90 555 123 45 67"))
        assertEquals("05551234567", SmsSender.normalizeRecipient("0555 123 45 67"))
    }

    // ---- Accepted ----

    @Test
    fun ordinaryNumbersAreAcceptedInEverySpelling() {
        for (number in listOf("05551234567", "+905551234567", "5551234567", "112", "+112")) {
            assertNull("number=$number", check(number))
        }
    }

    @Test
    fun theShortestAllowedNumberIsAccepted() {
        // Three digits is the floor; service numbers are short and must still work.
        assertNull(check("112"))
        assertNull(check("123"))
    }

    @Test
    fun theLongestAllowedNumberIsAccepted() {
        // Fifteen digits is the E.164 maximum, so it must not be rejected by an off by one.
        val longest = "+" + "1".repeat(15)
        assertNull(check(longest))
        assertNull(check("1".repeat(15)))
    }

    // ---- Rejected ----

    @Test
    fun aNumberTooShortToBeOneIsRejected() {
        assertEquals("invalid_recipient", check("12"))
        assertEquals("invalid_recipient", check("1"))
    }

    @Test
    fun aNumberLongerThanE164IsRejected() {
        assertEquals("invalid_recipient", check("1".repeat(16)))
        assertEquals("invalid_recipient", check("+" + "1".repeat(16)))
    }

    @Test
    fun lettersAreRejected() {
        assertEquals("invalid_recipient", check("BANK"))
        assertEquals("invalid_recipient", check("555ABC"))
    }

    @Test
    fun aPlusAnywhereButTheStartIsRejected() {
        assertEquals("invalid_recipient", check("55+51234567"))
        assertEquals("invalid_recipient", check("+90+5551234567"))
    }

    @Test
    fun anEmptyRecipientIsRejected() {
        assertEquals("invalid_recipient", check(""))
        assertEquals("invalid_recipient", check("   "))
        assertEquals("invalid_recipient", check("()"))
    }

    @Test
    fun anEmptyMessageIsRejected() {
        assertEquals("empty_message", check("05551234567", ""))
        assertEquals("empty_message", check("05551234567", "   "))
        assertEquals("empty_message", check("05551234567", "\n\t"))
    }

    @Test
    fun aMessageBeyondTenPartsIsRejected() {
        assertEquals("message_too_long", check("05551234567", "a".repeat(SmsSender.MAX_LENGTH + 1)))
        assertNull(check("05551234567", "a".repeat(SmsSender.MAX_LENGTH)))
    }

    // ---- The order of the checks ----

    @Test
    fun theRecipientIsCheckedBeforeTheMessage() {
        // A bad recipient with a bad message must report the recipient, because that is the fault
        // the person can act on.
        assertEquals("invalid_recipient", check("12", ""))
        assertEquals("invalid_recipient", check("ABC", "a".repeat(SmsSender.MAX_LENGTH + 1)))
    }

    @Test
    fun everyProblemHasAName() {
        val problems = listOf(
            check("12"), check("05551234567", ""), check("05551234567", "a".repeat(SmsSender.MAX_LENGTH + 1)),
        )
        assertEquals(listOf("invalid_recipient", "empty_message", "message_too_long"), problems)
        assertTrue(problems.all { it != null })
    }
}
