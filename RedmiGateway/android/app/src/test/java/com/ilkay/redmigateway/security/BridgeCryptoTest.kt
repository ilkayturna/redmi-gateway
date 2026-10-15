package com.ilkay.redmigateway.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These primitives are shared with the iPhone (docs/PROTOCOL.md, "Security"), so a silent change
 * here breaks the bridge rather than failing loudly. The HMAC cases are the published RFC 4231
 * vectors: if they pass, the key derivation the two sides agree on is still the same construction.
 *
 * Note: [BridgeCrypto.b64] and [BridgeCrypto.unb64] are deliberately not covered - they call
 * android.util.Base64, which the unit-test runtime stubs out (unitTests.isReturnDefaultValues).
 */
class BridgeCryptoTest {

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // ---- HMAC-SHA256 ----

    @Test
    fun hmacMatchesRfc4231Case1() {
        val key = ByteArray(20) { 0x0b }
        val mac = BridgeCrypto.hmac(key, "Hi There".toByteArray(Charsets.UTF_8))
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            mac.toHex()
        )
    }

    @Test
    fun hmacMatchesRfc4231Case2() {
        val mac = BridgeCrypto.hmac(
            "Jefe".toByteArray(Charsets.UTF_8),
            "what do ya want for nothing?".toByteArray(Charsets.UTF_8)
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            mac.toHex()
        )
    }

    @Test
    fun hmacConcatenatesItsPartsInOrder() {
        val key = BridgeCrypto.randomBytes(BridgeCrypto.KEY_BYTES)
        val joined = BridgeCrypto.hmac(key, "one".toByteArray(), "two".toByteArray())
        val single = BridgeCrypto.hmac(key, "onetwo".toByteArray())
        assertArrayEquals("splitting the message must not change the MAC", joined, single)

        val reordered = BridgeCrypto.hmac(key, "two".toByteArray(), "one".toByteArray())
        assertFalse("order must matter: it is what binds a label to its context", joined.contentEquals(reordered))
    }

    // ---- key derivation ----

    @Test
    fun deriveSeparatesDomainsAndBindsContext() {
        val root = BridgeCrypto.randomBytes(BridgeCrypto.KEY_BYTES)
        val session = BridgeCrypto.derive(root, "session")
        val media = BridgeCrypto.derive(root, "media")
        assertEquals(BridgeCrypto.KEY_BYTES, session.size)
        assertFalse("two labels must not produce the same sub key", session.contentEquals(media))
        assertFalse("the sub key must not be the root key", session.contentEquals(root))

        val bound = BridgeCrypto.derive(root, "session", "peer-a".toByteArray())
        val other = BridgeCrypto.derive(root, "session", "peer-b".toByteArray())
        assertFalse("context must bind into the sub key", bound.contentEquals(other))

        assertArrayEquals(
            "deriving again must be reproducible",
            session,
            BridgeCrypto.derive(root, "session")
        )
    }

    @Test
    fun constantTimeEqualsComparesContentNotIdentity() {
        val a = ByteArray(BridgeCrypto.KEY_BYTES) { it.toByte() }
        val b = a.copyOf()
        assertTrue(BridgeCrypto.constantTimeEquals(a, b))
        b[0] = (b[0] + 1).toByte()
        assertFalse(BridgeCrypto.constantTimeEquals(a, b))
        assertFalse(BridgeCrypto.constantTimeEquals(a, a.copyOf(a.size - 1)))
        assertTrue(BridgeCrypto.constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    // ---- PBKDF2 (the short pairing code) ----

    @Test
    fun pbkdf2IsDeterministicAndSaltDependent() {
        val salt = BridgeCrypto.randomBytes(16)
        // A low round count keeps the test fast; the production constant is asserted separately.
        val first = BridgeCrypto.pbkdf2("3V6PW5", salt, rounds = 1_000)
        val again = BridgeCrypto.pbkdf2("3V6PW5", salt, rounds = 1_000)
        assertArrayEquals(first, again)
        assertEquals(BridgeCrypto.KEY_BYTES, first.size)

        val otherSalt = BridgeCrypto.randomBytes(16)
        assertFalse(BridgeCrypto.pbkdf2("3V6PW5", otherSalt, rounds = 1_000).contentEquals(first))
        assertFalse(BridgeCrypto.pbkdf2("3V6PW6", salt, rounds = 1_000).contentEquals(first))
    }

    @Test
    fun thePairingRoundCountIsNotLoweredByAccident() {
        assertEquals(100_000, BridgeCrypto.PAIRING_PBKDF2_ROUNDS)
    }

    // ---- AES-256-GCM ----

    @Test
    fun sealLaysOutNonceCiphertextAndTag() {
        val key = BridgeCrypto.randomBytes(BridgeCrypto.KEY_BYTES)
        val plaintext = "call audio".toByteArray()
        val sealed = BridgeCrypto.seal(key, plaintext, nonce = hex("000102030405060708090a0b"))
        assertEquals(BridgeCrypto.NONCE_BYTES + plaintext.size + BridgeCrypto.TAG_BYTES, sealed.size)
        assertArrayEquals(hex("000102030405060708090a0b"), sealed.copyOfRange(0, BridgeCrypto.NONCE_BYTES))
        assertEquals(
            "the caller-provided nonce must be the one used",
            "000102030405060708090a0b",
            sealed.copyOfRange(0, BridgeCrypto.NONCE_BYTES).toHex()
        )
    }

    @Test
    fun sealUsesAFreshNonceWhenNoneIsGiven() {
        val key = BridgeCrypto.randomBytes(BridgeCrypto.KEY_BYTES)
        val plaintext = ByteArray(32)
        val first = BridgeCrypto.seal(key, plaintext)
        val second = BridgeCrypto.seal(key, plaintext)
        assertFalse("a repeated nonce under one key breaks GCM", first.contentEquals(second))
        assertArrayEquals(plaintext, BridgeCrypto.open(key, first))
        assertArrayEquals(plaintext, BridgeCrypto.open(key, second))
    }

    @Test
    fun openRoundTripsWithAndWithoutAdditionalData() {
        val key = BridgeCrypto.randomBytes(BridgeCrypto.KEY_BYTES)
        val plaintext = BridgeCrypto.randomBytes(96)
        val aad = "session=7".toByteArray()

        assertArrayEquals(plaintext, BridgeCrypto.open(key, BridgeCrypto.seal(key, plaintext)))
        assertArrayEquals(plaintext, BridgeCrypto.open(key, BridgeCrypto.seal(key, plaintext, aad), aad))
    }

    @Test
    fun openRejectsTamperingAndWrongKeys() {
        val key = BridgeCrypto.randomBytes(BridgeCrypto.KEY_BYTES)
        val otherKey = BridgeCrypto.randomBytes(BridgeCrypto.KEY_BYTES)
        val plaintext = "the iPhone's microphone".toByteArray()
        val aad = "call=42".toByteArray()
        val sealed = BridgeCrypto.seal(key, plaintext, aad)

        assertNull("another key must not open it", BridgeCrypto.open(otherKey, sealed, aad))
        assertNull("missing AAD must fail authentication", BridgeCrypto.open(key, sealed))
        assertNull("other AAD must fail authentication", BridgeCrypto.open(key, sealed, "call=43".toByteArray()))

        val flipped = sealed.copyOf()
        flipped[BridgeCrypto.NONCE_BYTES + 1] = (flipped[BridgeCrypto.NONCE_BYTES + 1].toInt() xor 0x01).toByte()
        assertNull("a flipped ciphertext bit must fail authentication", BridgeCrypto.open(key, flipped, aad))

        val flippedTag = sealed.copyOf()
        flippedTag[flippedTag.size - 1] = (flippedTag[flippedTag.size - 1].toInt() xor 0x01).toByte()
        assertNull("a flipped tag bit must fail authentication", BridgeCrypto.open(key, flippedTag, aad))

        val flippedNonce = sealed.copyOf()
        flippedNonce[0] = (flippedNonce[0].toInt() xor 0x01).toByte()
        assertNull("a flipped nonce must fail authentication", BridgeCrypto.open(key, flippedNonce, aad))
    }

    @Test
    fun openRejectsInputsTooShortToHoldANonceAndATag() {
        val key = BridgeCrypto.randomBytes(BridgeCrypto.KEY_BYTES)
        assertNull(BridgeCrypto.open(key, ByteArray(0)))
        assertNull(BridgeCrypto.open(key, ByteArray(BridgeCrypto.NONCE_BYTES)))
        assertNull(BridgeCrypto.open(key, ByteArray(BridgeCrypto.NONCE_BYTES + BridgeCrypto.TAG_BYTES - 1)))
        // Exactly a nonce plus a tag is the smallest legal frame and decrypts to nothing.
        val empty = BridgeCrypto.seal(key, ByteArray(0))
        assertEquals(BridgeCrypto.NONCE_BYTES + BridgeCrypto.TAG_BYTES, empty.size)
        assertArrayEquals(ByteArray(0), BridgeCrypto.open(key, empty))
    }

    // ---- random source ----

    @Test
    fun randomBytesProducesTheRequestedLengthAndVaries() {
        assertEquals(0, BridgeCrypto.randomBytes(0).size)
        assertEquals(BridgeCrypto.KEY_BYTES, BridgeCrypto.randomBytes(BridgeCrypto.KEY_BYTES).size)
        val a = BridgeCrypto.randomBytes(32)
        val b = BridgeCrypto.randomBytes(32)
        assertNotEquals(a.toHex(), b.toHex())
        assertFalse("a fresh key must not be all zeroes", a.all { it == 0.toByte() })
    }
}
