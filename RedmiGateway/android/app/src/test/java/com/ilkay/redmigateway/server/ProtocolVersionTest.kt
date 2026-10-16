package com.ilkay.redmigateway.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the protocol version this gateway speaks and the one it will still tolerate.
 *
 * Both numbers are written down on both sides. The iPhone reads them out of the hello frame and
 * decides whether it can talk to this Redmi at all, so a change here is a compatibility decision
 * rather than a constant. The iOS side has BridgeProtocol.version on its own, and the handshake
 * compares the two in both directions.
 *
 * The pair is worth stating plainly: two is what this app speaks, and one is what it will still
 * accept. Keeping the minimum at one is what lets an older iPhone build authenticate by sending
 * the pairing code while it updates, and the two must never cross, because a minimum above the
 * spoken version would make the gateway refuse itself.
 */
class ProtocolVersionTest {

    @Test
    fun theSpokenVersionIsTheOneTheIPhoneExpects() {
        // BridgeProtocol.version on the iOS side is 2, and the handshake compares them directly.
        assertEquals(2, GatewayWebSocketServer.PROTOCOL)
    }

    @Test
    fun theMinimumIsStillOneSoOlderBuildsCanUpdate() {
        assertEquals(1, GatewayWebSocketServer.MIN_PROTOCOL)
    }

    @Test
    fun theMinimumIsNeverAboveTheSpokenVersion() {
        // If it were, this gateway would advertise a minimum it does not itself meet.
        assertTrue(
            "min ${GatewayWebSocketServer.MIN_PROTOCOL} > protocol ${GatewayWebSocketServer.PROTOCOL}",
            GatewayWebSocketServer.MIN_PROTOCOL <= GatewayWebSocketServer.PROTOCOL
        )
    }

    @Test
    fun theSpokenVersionIsNotZero() {
        // Zero is what an unauthenticated peer reports, so it can never be the gateway's own.
        assertTrue(GatewayWebSocketServer.PROTOCOL > 0)
    }

    @Test
    fun theSealedFramingIsBoundToTheSpokenVersion() {
        // The websocket additional data ends in the version whose framing it is. If the framing
        // ever changes, that string has to change with it, and these two must not drift apart.
        val aad = String(com.ilkay.redmigateway.security.SecureChannel.AAD, Charsets.UTF_8)
        assertTrue("aad=$aad", aad.endsWith("-${GatewayWebSocketServer.PROTOCOL}"))
    }

    @Test
    fun theVersionNumbersAreSmallAndContiguous() {
        // Two versions exist, and the minimum must be one of them rather than something invented.
        assertEquals(1, GatewayWebSocketServer.MIN_PROTOCOL)
        assertEquals(2, GatewayWebSocketServer.PROTOCOL)
        assertEquals(1, GatewayWebSocketServer.PROTOCOL - GatewayWebSocketServer.MIN_PROTOCOL)
    }
}
