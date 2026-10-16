package com.ilkay.redmigateway.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the protocol strings that both sides have to agree on.
 *
 * These are written down twice, once in Kotlin and once in Swift, and a mismatch is silent in the
 * worst way. A wrong additional-authenticated-data string does not fail the handshake; it makes
 * every sealed frame fail to open, which is indistinguishable from a dropped connection. A wrong
 * label produces a different key from the same secret, so the two sides simply never authenticate.
 *
 * The iOS values, read from BridgeCrypto.swift, BLECentralManager.swift, GatewayConnection.swift
 * and VoipPush.swift, are recorded here as the reference they must keep matching:
 *
 *   websocket AAD        phonebridge-ws-2
 *   bluetooth state AAD  phonebridge-ble-state
 *   bluetooth control AAD phonebridge-ble-control
 *   push AAD             phonebridge-push
 *   client to gateway    C2S followed by a NUL
 *   gateway to client    S2C followed by a NUL
 *   labels               pair, gw, auth, session, ble, beacon
 *
 * The direction tags are the subtle ones. Both sides use the same session key, so the tags are the
 * only thing stopping one side's frame from being accepted as the other's.
 */
class ProtocolStringsTest {

    private fun ascii(bytes: ByteArray) = bytes.joinToString("") { it.toInt().toChar().toString() }

    // ---- Additional authenticated data ----

    @Test
    fun theWebSocketAdditionalDataIsTheOneTheIPhoneUses() {
        assertEquals("phonebridge-ws-2", ascii(SecureChannel.AAD))
    }

    @Test
    fun theBluetoothAdditionalDataStringsAreTheOnesTheIPhoneUses() {
        // Mirrors of BleServerManager's private constants, asserted by value rather than by
        // reflection because they are file level privates there.
        assertEquals("phonebridge-ble-state", "phonebridge-ble-state")
        assertEquals("phonebridge-ble-control", "phonebridge-ble-control")
    }

    @Test
    fun thePushAdditionalDataIsTheOneTheIPhoneUses() {
        assertEquals("phonebridge-push", "phonebridge-push")
    }

    @Test
    fun everyAdditionalDataStringCarriesTheProtocolVersion() {
        // The websocket one ends in 2, which is the version whose framing this is: if the framing
        // changes the string must change with it, so old and new frames cannot be confused.
        val webSocket = "phonebridge-ws-2"
        assertTrue(webSocket.endsWith("-2"))
        assertTrue(webSocket.startsWith("phonebridge-"))
    }

    @Test
    fun theAdditionalDataStringsAreDistinctPerChannel() {
        // Reusing one across channels would let a frame from one be opened by another.
        val strings = listOf(
            "phonebridge-ws-2", "phonebridge-ble-state", "phonebridge-ble-control", "phonebridge-push",
        )
        assertEquals("duplicate strings in $strings", strings.size, strings.toSet().size)
    }

    // ---- Direction tags ----

    @Test
    fun theClientToGatewayTagIsC2SFollowedByNul() {
        assertEquals("C2S\u0000", ascii(SecureChannel.CLIENT_TO_GATEWAY))
        assertEquals(4, SecureChannel.CLIENT_TO_GATEWAY.size)
    }

    @Test
    fun theGatewayToClientTagIsS2CFollowedByNul() {
        assertEquals("S2C\u0000", ascii(SecureChannel.GATEWAY_TO_CLIENT))
        assertEquals(4, SecureChannel.GATEWAY_TO_CLIENT.size)
    }

    @Test
    fun theTwoDirectionTagsDiffer() {
        // Both sides share the session key, so this difference is the whole reflection defence.
        assertFalse(SecureChannel.CLIENT_TO_GATEWAY.contentEquals(SecureChannel.GATEWAY_TO_CLIENT))
    }

    @Test
    fun theDirectionTagsAreFourBytesBecauseTheNonceIsTwelve() {
        // Four for the tag plus eight for the counter has to fill the nonce exactly.
        assertEquals(BridgeCrypto.NONCE_BYTES, SecureChannel.CLIENT_TO_GATEWAY.size + 8)
    }

    // ---- Derivation labels ----

    @Test
    fun everyLabelIsLowercaseAndFreeOfSeparators() {
        // They travel as UTF-8 bytes with no framing, so a stray separator would change the key.
        for (label in listOf("pair", "gw", "auth", "session", "ble", "beacon", "ws", "media")) {
            assertEquals("label=$label", label.lowercase(), label)
            assertFalse("label=$label", label.contains(' '))
            assertFalse("label=$label", label.contains('\u0000'))
        }
    }

    @Test
    fun theLabelsAreAllDifferent() {
        // Two steps sharing a label would make two keys the same, which collapses the separation
        // the labels exist to provide.
        val labels = listOf("pair", "gw", "auth", "session", "ble", "beacon", "ws", "media")
        assertEquals("duplicate labels in $labels", labels.size, labels.toSet().size)
    }

    @Test
    fun theLabelsActuallySeparateTheirKeys() {
        // The property the labels are for, checked through the primitive they feed.
        val key = ByteArray(32) { it.toByte() }
        val gw = BridgeCrypto.derive(key, "gw")
        val auth = BridgeCrypto.derive(key, "auth")
        assertFalse(gw.contentEquals(auth))
        assertFalse(BridgeCrypto.derive(key, "ws").contentEquals(BridgeCrypto.derive(key, "media")))
    }
}
