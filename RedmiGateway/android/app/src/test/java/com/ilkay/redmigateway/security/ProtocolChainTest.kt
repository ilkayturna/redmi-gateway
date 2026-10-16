package com.ilkay.redmigateway.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the protocol 2 authentication chain end to end, from the pairing code to the two keys the
 * session actually runs on.
 *
 * The vector is fixed, so the values below are golden: if any step of the chain, any nonce
 * ordering, or the Base64 the values travel in ever changes, this file fails and says so. That is
 * the point. BridgeCrypto moved from android.util.Base64 to java.util.Base64 so the sealed frame
 * channel could be tested at all, and every sealed frame and every proof on the wire goes through
 * that encoder. A silent change there would stop the iPhone authenticating with nothing in the log
 * to explain it, and this is the check that would catch it before a device ever saw it.
 *
 * The orderings are the subtle part and are asserted on purpose. gatewayProof takes the client
 * nonce first and the server nonce second, while auth and the session key take them the other way
 * round. Reading either of those backwards still produces a valid looking hash.
 */
class ProtocolChainTest {

    private val code = "482913"
    private val serverNonce = ByteArray(16) { it.toByte() }          // 00 01 02 ... 0f
    private val clientNonce = ByteArray(16) { (it + 16).toByte() }   // 10 11 12 ... 1f

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    // ---- The steps, pinned to their golden values ----

    @Test
    fun thePairingProofIsHmacOfTheCodeOverServerThenClientNonce() {
        val proof = BridgeCrypto.hmac(
            code.toByteArray(Charsets.UTF_8), "pair".toByteArray(), serverNonce, clientNonce
        )
        assertEquals("nHAz4PupQgFaAZXeH42ghXg8t2EwrEwZGwBfyvnpwy4=", BridgeCrypto.b64(proof))
    }

    @Test
    fun thePairingKeyIsPbkdf2OverBothNoncesInServerThenClientOrder() {
        val key = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
        assertEquals("73Oqlz9/fPEqlcXMVQe+bH+gxaIIOwjh4oBxfOsCBns=", BridgeCrypto.b64(key))
        assertEquals(BridgeCrypto.KEY_BYTES, key.size)
    }

    @Test
    fun theGatewayProofTakesTheClientNonceFirst() {
        val key = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
        val proof = BridgeCrypto.hmac(key, "gw".toByteArray(), clientNonce, serverNonce)
        assertEquals("ltDf5ZhpYa1qGPWugdfey1A3dSjz7gnksl+YL5tmcQE=", BridgeCrypto.b64(proof))
    }

    @Test
    fun theAuthProofTakesTheServerNonceFirst() {
        val key = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
        val proof = BridgeCrypto.hmac(key, "auth".toByteArray(), serverNonce, clientNonce)
        assertEquals("JrGUAq0f3B+qZ3clxJZviXaSX4ITH782EqTRSw8wnn8=", BridgeCrypto.b64(proof))
    }

    @Test
    fun theSessionKeyIsHmacOfThePairingKeyUnderItsOwnLabel() {
        val key = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
        val session = BridgeCrypto.hmac(key, "session".toByteArray(), serverNonce, clientNonce)
        assertEquals("oArjPX2SWiBudt2b7tX6v1j8maHafHDsDz8+LVQ1k/U=", BridgeCrypto.b64(session))
    }

    @Test
    fun theTransportKeysAreDomainSeparatedFromTheSession() {
        val key = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
        val session = BridgeCrypto.hmac(key, "session".toByteArray(), serverNonce, clientNonce)
        assertEquals("ZJ8i9DwVPFMykG2JvOqDVysvhgTdvsqwkhahHSAUlV4=", BridgeCrypto.b64(BridgeCrypto.derive(session, "ws")))
        assertEquals("gs1jQ9Kw4LzEyB+6Fb6pFG1GInwiXdhDMtnoY+w9WvE=", BridgeCrypto.b64(BridgeCrypto.derive(session, "media")))
    }

    // ---- The properties that make the orderings matter ----

    @Test
    fun readingTheGatewayProofNonceOrderBackwardsIsCaught() {
        // The two orderings are both valid hashes; only one is the protocol.
        val key = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
        val correct = BridgeCrypto.hmac(key, "gw".toByteArray(), clientNonce, serverNonce)
        val backwards = BridgeCrypto.hmac(key, "gw".toByteArray(), serverNonce, clientNonce)
        assertNotEquals(hex(correct), hex(backwards))
    }

    @Test
    fun readingTheAuthNonceOrderBackwardsIsCaught() {
        val key = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
        val correct = BridgeCrypto.hmac(key, "auth".toByteArray(), serverNonce, clientNonce)
        val backwards = BridgeCrypto.hmac(key, "auth".toByteArray(), clientNonce, serverNonce)
        assertNotEquals(hex(correct), hex(backwards))
    }

    @Test
    fun theTwoProofsAreNotInterchangeable() {
        // If they were, a gateway proof could be replayed as an auth proof.
        val key = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
        val gateway = BridgeCrypto.hmac(key, "gw".toByteArray(), clientNonce, serverNonce)
        val auth = BridgeCrypto.hmac(key, "auth".toByteArray(), serverNonce, clientNonce)
        assertNotEquals(hex(gateway), hex(auth))
        assertNotEquals(hex(gateway), hex(BridgeCrypto.hmac(key, "session".toByteArray(), clientNonce, serverNonce)))
    }

    @Test
    fun theWebSocketAndMediaKeysAreNotInterchangeable() {
        // Both run over the same session, so a shared key would let one decrypt the other.
        val key = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
        val session = BridgeCrypto.hmac(key, "session".toByteArray(), serverNonce, clientNonce)
        assertNotEquals(
            hex(BridgeCrypto.derive(session, "ws")),
            hex(BridgeCrypto.derive(session, "media"))
        )
    }

    @Test
    fun aDifferentPairingCodeProducesADifferentEverything() {
        val good = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
        val wrong = BridgeCrypto.pbkdf2("482914", serverNonce + clientNonce)
        assertNotEquals(hex(good), hex(wrong))
    }

    @Test
    fun swappedNoncesProduceADifferentPairingKey() {
        // serverNonce + clientNonce is not symmetric; concatenation order carries meaning.
        val good = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
        val swapped = BridgeCrypto.pbkdf2(code, clientNonce + serverNonce)
        assertNotEquals(hex(good), hex(swapped))
    }

    @Test
    fun theChainIsDeterministic() {
        // Two runs with the same inputs must agree, or neither side could ever authenticate twice.
        fun run(): String {
            val key = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
            val session = BridgeCrypto.hmac(key, "session".toByteArray(), serverNonce, clientNonce)
            return hex(BridgeCrypto.derive(session, "ws"))
        }
        assertEquals(run(), run())
    }

    @Test
    fun everyKeyInTheChainIsFullLength() {
        val key = BridgeCrypto.pbkdf2(code, serverNonce + clientNonce)
        val session = BridgeCrypto.hmac(key, "session".toByteArray(), serverNonce, clientNonce)
        assertEquals(32, key.size)
        assertEquals(32, session.size)
        assertEquals(32, BridgeCrypto.hmac(key, "gw".toByteArray(), clientNonce, serverNonce).size)
        assertEquals(32, BridgeCrypto.derive(session, "ws").size)
        assertEquals(32, BridgeCrypto.derive(session, "media").size)
        assertTrue(key.isNotEmpty())
        assertFalse(session.contentEquals(key))
    }
}
