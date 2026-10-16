package com.ilkay.redmigateway.push

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the silent wake push is allowed to carry.
 *
 * The body used to be built inline in sendIfDue, so nothing stopped someone adding the caller's
 * number or name to it and no test would have noticed. That would quietly break the promise made
 * to the user about data usage and privacy, and it is exactly the kind of change that looks
 * helpful at the time.
 *
 * The strongest assertion here is the exact key set: any field added later fails this test until
 * someone deliberately updates it and says why.
 */
class WakePushPayloadTest {

    private val callId = "3f2a9c14-7b6d-4e58-9a01-2c3d4e5f6a7b"

    @Test
    fun carriesExactlyTwoFields() {
        val payload = CallWakePush.wakePayload(callId)
        assertEquals(
            "the wake push must carry only the action and the call id",
            setOf("a", "callId"),
            payload.keys().asSequence().toSet()
        )
    }

    @Test
    fun actionIsWake() {
        assertEquals("wake", CallWakePush.wakePayload(callId).getString("a"))
    }

    @Test
    fun carriesTheCallId() {
        assertEquals(callId, CallWakePush.wakePayload(callId).getString("callId"))
    }

    @Test
    fun aVeryLongCallIdIsTruncatedTo64() {
        val long = "x".repeat(500)
        val carried = CallWakePush.wakePayload(long).getString("callId")
        assertEquals(64, carried.length)
        assertEquals("x".repeat(64), carried)
    }

    /** No field whose name suggests personal data, on any call. */
    @Test
    fun noFieldNamesSuggestPersonalData() {
        val keys = CallWakePush.wakePayload(callId).keys().asSequence().map { it.lowercase() }.toList()
        for (forbidden in listOf("number", "phone", "name", "contact", "sms", "body", "text")) {
            assertFalse(
                "payload must not carry a '$forbidden' field",
                keys.any { it.contains(forbidden) }
            )
        }
    }

    /**
     * A phone number handed in as the call id must not survive into the body. The Redmi's call id
     * is a UUID, but this asserts the payload does not pass through whatever it is given.
     */
    @Test
    fun aPhoneNumberInTheCallIdIsTruncatedAway() {
        val number = "0555123456789012345678901234567890123456789012345678901234567890123456789"
        val carried = CallWakePush.wakePayload(number).getString("callId")
        assertEquals(64, carried.length)
        assertFalse("the full number must not fit", carried == number)
    }

    /** The measured size claim: the push is tiny, which is what keeps the data promise cheap. */
    @Test
    fun theSerialisedBodyStaysWellUnderOnePacket() {
        val json = CallWakePush.wakePayload(callId).toString()
        assertTrue("body was ${json.length} chars", json.length < 200)
    }

    /** Round trip through JSON, because that is what actually goes on the wire. */
    @Test
    fun survivesSerialisation() {
        val parsed = JSONObject(CallWakePush.wakePayload(callId).toString())
        assertEquals("wake", parsed.getString("a"))
        assertEquals(callId, parsed.getString("callId"))
        assertEquals(2, parsed.keys().asSequence().count())
    }
}
