package com.ilkay.redmigateway.security

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the sealed frame channel: replay, direction and framing.
 *
 * This class had no tests, and could not have had any: it goes through BridgeCrypto's Base64,
 * which was android.util.Base64, which is a stub that returns null under unit tests, so open()
 * always returned null and every assertion would have been vacuous. Base64 moved to java.util,
 * which is available from API 26, and Base64EncodingTest pins that the bytes did not change.
 *
 * What is worth protecting here is small and load bearing. Each side only accepts counters above
 * the last it opened, so a captured frame cannot be replayed. The two directions use different
 * nonces, so a frame sealed by one side cannot be reflected back at it. And anything forged,
 * reordered or malformed comes back as null rather than an exception, because a thrown error
 * would take the whole connection down.
 */
class SecureChannelTest {

    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun aFrameSealsAndOpensAcrossTheTwoSides() {
        val gateway = SecureChannel(key, isGateway = true)
        val client = SecureChannel(key, isGateway = false)
        val sealed = JSONObject(gateway.seal("""{"type":"ping"}"""))
        assertEquals("s", sealed.getString("t"))
        assertEquals(1L, sealed.getLong("c"))
        assertEquals("""{"type":"ping"}""", client.open(sealed.getLong("c"), sealed.getString("d")))
    }

    @Test
    fun theCounterRisesOnEveryFrame() {
        val gateway = SecureChannel(key, isGateway = true)
        for (expected in 1L..5L) {
            assertEquals(expected, JSONObject(gateway.seal("{}")).getLong("c"))
        }
    }

    @Test
    fun aReplayedFrameIsRefused() {
        // The whole point of the counter: capturing a frame and sending it twice must not work.
        val gateway = SecureChannel(key, isGateway = true)
        val client = SecureChannel(key, isGateway = false)
        val frame = JSONObject(gateway.seal("""{"type":"answer"}"""))
        assertNotNull(client.open(frame.getLong("c"), frame.getString("d")))
        assertNull("the same counter must not open twice", client.open(frame.getLong("c"), frame.getString("d")))
    }

    @Test
    fun anOlderFrameIsRefusedAfterANewerOne() {
        val gateway = SecureChannel(key, isGateway = true)
        val client = SecureChannel(key, isGateway = false)
        val first = JSONObject(gateway.seal("""{"n":1}"""))
        val second = JSONObject(gateway.seal("""{"n":2}"""))
        assertNotNull(client.open(second.getLong("c"), second.getString("d")))
        assertNull("a frame from before the last one is a replay", client.open(first.getLong("c"), first.getString("d")))
    }

    @Test
    fun aFrameCannotBeReflectedBackAtItsSender() {
        // Both sides use the same key, so direction tags are the only thing stopping a sealed
        // frame from being echoed back and accepted as if it came from the other side.
        val gateway = SecureChannel(key, isGateway = true)
        val gateway2 = SecureChannel(key, isGateway = true)
        val frame = JSONObject(gateway.seal("""{"type":"dial"}"""))
        assertNull(gateway2.open(frame.getLong("c"), frame.getString("d")))
    }

    @Test
    fun aTamperedBodyIsRefused() {
        val gateway = SecureChannel(key, isGateway = true)
        val client = SecureChannel(key, isGateway = false)
        val frame = JSONObject(gateway.seal("""{"type":"answer"}"""))
        val body = frame.getString("d")
        val flipped = body.mapIndexed { i, c ->
            if (i == body.length / 2) (if (c == 'A') 'B' else 'A') else c
        }.joinToString("")
        assertNull(client.open(frame.getLong("c"), flipped))
    }

    @Test
    fun aDifferentKeyIsRefused() {
        val gateway = SecureChannel(key, isGateway = true)
        val stranger = SecureChannel(ByteArray(32) { (it + 9).toByte() }, isGateway = false)
        val frame = JSONObject(gateway.seal("""{"type":"answer"}"""))
        assertNull(stranger.open(frame.getLong("c"), frame.getString("d")))
    }

    @Test
    fun malformedInputIsRefusedRatherThanThrown() {
        // A throw here would take the connection down; null is the contract.
        val client = SecureChannel(key, isGateway = false)
        assertNull(client.open(1L, "not base64!"))
        assertNull(client.open(1L, ""))
        assertNull(client.open(1L, "AAAA"))
    }

    @Test
    fun aRefusedFrameDoesNotAdvanceTheCounter() {
        // Otherwise a flood of junk would lock the real frames out.
        val gateway = SecureChannel(key, isGateway = true)
        val client = SecureChannel(key, isGateway = false)
        assertNull(client.open(5L, "not base64!"))
        val frame = JSONObject(gateway.seal("""{"type":"answer"}"""))
        assertEquals(1L, frame.getLong("c"))
        assertNotNull("a real first frame must still open", client.open(frame.getLong("c"), frame.getString("d")))
    }

    @Test
    fun theBodyIsCiphertextNotPlaintext() {
        // A sealed frame that still contains its words is not sealed.
        val gateway = SecureChannel(key, isGateway = true)
        val frame = JSONObject(gateway.seal("""{"type":"dial","number":"05551234567"}"""))
        assertTrue("the number must not appear", !frame.getString("d").contains("0555"))
        assertTrue("the type must not appear", !frame.getString("d").contains("dial"))
    }
}
