package com.ilkay.redmigateway.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Base64 the sealed frames travel in.
 *
 * BridgeCrypto used android.util.Base64, whose unit test stub returns null, so SecureChannel.open
 * always returned null under test and none of its replay or direction checks could be asserted on.
 * It now uses java.util.Base64, which is available from API 26 and produces the same bytes. That
 * claim is the whole reason this file exists: if the two ever differed, every sealed frame would
 * fail to authenticate and the iPhone would stop working entirely, with nothing in the log to say
 * why.
 *
 * The vectors are RFC 4648 section 10, so they are the standard rather than this implementation's
 * own output, which is what makes them worth checking against.
 */
class Base64EncodingTest {

    @Test
    fun rfc4648VectorsEncodeAsTheStandardSays() {
        // RFC 4648 section 10, the "BASE64" column.
        assertEquals("", BridgeCrypto.b64("".toByteArray()))
        assertEquals("Zg==", BridgeCrypto.b64("f".toByteArray()))
        assertEquals("Zm8=", BridgeCrypto.b64("fo".toByteArray()))
        assertEquals("Zm9v", BridgeCrypto.b64("foo".toByteArray()))
        assertEquals("Zm9vYg==", BridgeCrypto.b64("foob".toByteArray()))
        assertEquals("Zm9vYmE=", BridgeCrypto.b64("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", BridgeCrypto.b64("foobar".toByteArray()))
    }

    @Test
    fun itRoundTripsEveryByteValue() {
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, BridgeCrypto.unb64(BridgeCrypto.b64(all)))
    }

    @Test
    fun itProducesNoLineBreaks() {
        // android.util.Base64.NO_WRAP meant no wrapping; the JSON body has to hold the whole thing
        // on one line or the frame parser sees a broken string.
        val long = ByteArray(600) { (it % 251).toByte() }
        val encoded = BridgeCrypto.b64(long)
        assertEquals("no newline", -1, encoded.indexOf('\n'))
        assertEquals("no carriage return", -1, encoded.indexOf('\r'))
    }

    @Test
    fun itUsesTheStandardAlphabetNotTheUrlSafeOne() {
        // Bytes chosen so the encoding is forced to use both characters that differ between the
        // standard and URL safe alphabets: 251 and 239 give '+' and '/', never '-' and '_'.
        val encoded = BridgeCrypto.b64(byteArrayOf(251.toByte(), 239.toByte(), 190.toByte()))
        assertEquals(-1, encoded.indexOf('-'))
        assertEquals(-1, encoded.indexOf('_'))
        assertEquals(4, encoded.length)
    }

    @Test
    fun itPadsToAFourCharacterBoundary() {
        for (length in 1..16) {
            val encoded = BridgeCrypto.b64(ByteArray(length) { 1 })
            assertEquals("length=$length", 0, encoded.length % 4)
        }
    }

    @Test
    fun malformedInputDecodesToNullRatherThanThrowing() {
        // every caller treats null as "reject this frame"; a throw would take down the connection.
        assertNull(BridgeCrypto.unb64("not base64!"))
        assertNull(BridgeCrypto.unb64("Zg="))
        assertNull(BridgeCrypto.unb64("===="))
    }

    @Test
    fun decodingIsTheExactInverseOfEncodingForTheSealedBodyShape() {
        // A sealed body is ciphertext plus a 16 byte tag, so lengths are 16, 32, 48... and the
        // padding pattern has to survive both directions.
        for (payload in listOf(16, 32, 33, 47, 48, 49)) {
            val body = ByteArray(payload) { (it * 7).toByte() }
            assertArrayEquals("payload=$payload", body, BridgeCrypto.unb64(BridgeCrypto.b64(body)))
        }
    }
}
