package com.ilkay.redmigateway.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what an SMS notification may say and when one is sent at all.
 *
 * The body rule is the privacy control the user asked to keep. With the switch off the message
 * text must never leave the Redmi: the notification says only that a message arrived. Apple's
 * servers carry whatever is put in the push, so this is the line between "a message came" and
 * "here is what it said", and it is asserted rather than described.
 */
class SmsPushContentTest {

    // ---- The privacy rule ----

    @Test
    fun withTheSwitchOffTheMessageTextIsNotSent() {
        val secret = "Hesap numaran 1234, dogrulama kodu 5678"
        val sent = smsPushBody(includeContent = false, body = secret)
        assertFalse("the body must not travel", sent.contains("5678"))
        assertFalse("the body must not travel", sent.contains("Hesap"))
        assertEquals("Yeni mesaj", sent)
    }

    @Test
    fun withTheSwitchOnTheMessageTextIsSent() {
        assertEquals("Merhaba", smsPushBody(includeContent = true, body = "Merhaba"))
    }

    @Test
    fun aLongMessageIsCutSoThePushStaysSmall() {
        val long = "a".repeat(5_000)
        assertEquals(400, smsPushBody(includeContent = true, body = long).length)
    }

    @Test
    fun exactlyFourHundredCharactersIsNotCut() {
        val exact = "b".repeat(400)
        assertEquals(400, smsPushBody(includeContent = true, body = exact).length)
        assertEquals(exact, smsPushBody(includeContent = true, body = exact))
    }

    @Test
    fun anEmptyMessageStillSaysSomething() {
        // A blank notification line would look like a fault rather than a message.
        assertEquals("", smsPushBody(includeContent = true, body = ""))
        assertEquals("Yeni mesaj", smsPushBody(includeContent = false, body = ""))
    }

    // ---- When a notification is sent at all ----

    @Test
    fun anUnreadInboxMessageIsPushed() {
        assertTrue(isPushableSms("inbox", read = false))
    }

    @Test
    fun anAlreadyReadMessageIsNot() {
        // It has been seen on the Redmi; buzzing the iPhone about it is noise.
        assertFalse(isPushableSms("inbox", read = true))
    }

    @Test
    fun sentMessagesAreNot() {
        assertFalse(isPushableSms("sent", read = false))
        assertFalse(isPushableSms("outbox", read = false))
        assertFalse(isPushableSms("draft", read = false))
    }

    // ---- The grouping key ----

    @Test
    fun theSameSenderGroupsTogetherHoweverTheNumberIsWritten() {
        val keys = listOf(
            "05551234567", "5551234567", "+90 555 123 45 67", "905551234567",
        ).map { smsPushThreadId(it) }.toSet()
        assertEquals(setOf("sms-5551234567"), keys)
    }

    @Test
    fun differentSendersDoNotGroupTogether() {
        assertFalse(smsPushThreadId("05551234567") == smsPushThreadId("05559876543"))
    }

    @Test
    fun aSenderWithNoDigitsKeepsItsName() {
        // Alphanumeric senders like bank short codes must not produce a bare "sms-": that put every
        // bank and operator message into one stack on the iPhone (N10).
        assertEquals("sms-BANK", smsPushThreadId("BANK"))
        assertEquals("sms-1234", smsPushThreadId("BANK1234"))
        assertFalse(smsPushThreadId("AKBANK") == smsPushThreadId("TURKCELL"))
    }
}
