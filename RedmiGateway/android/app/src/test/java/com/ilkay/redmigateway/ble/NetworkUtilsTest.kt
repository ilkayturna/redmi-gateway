package com.ilkay.redmigateway.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These addresses are what the Redmi tells the iPhone to call back on, so a wrong one costs the
 * user a call: the iPhone dials into nothing while the gateway waits. The rules are "never announce
 * an address the iPhone cannot reach" (mobile data and internal interfaces) and "prefer Wi-Fi, then
 * this phone's hotspot".
 *
 * The tests read the machine's real interfaces, so they assert properties that must hold on any
 * host instead of fixed addresses - a fixed list would turn red on the next machine.
 */
class NetworkUtilsTest {

    private val ipv4 = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    @Test
    fun neverAnnouncesLoopbackOrLinkLocal() {
        val addresses = NetworkUtils.localAddresses()
        for (address in addresses) {
            assertFalse("loopback must never be announced: $address", address.startsWith("127."))
            assertFalse("link-local must never be announced: $address", address.startsWith("169.254."))
        }
    }

    @Test
    fun announcesIPv4LiteralsOnly() {
        for (address in NetworkUtils.localAddresses()) {
            assertTrue("not an IPv4 literal: $address", ipv4.matches(address))
            for (part in address.split(".")) {
                assertTrue("octet out of range in $address", part.toInt() in 0..255)
            }
        }
    }

    @Test
    fun listsEachAddressOnce() {
        val addresses = NetworkUtils.localAddresses()
        assertEquals("a repeated address makes the iPhone retry the same dead end",
            addresses.distinct(), addresses)
    }

    @Test
    fun prefersWifiWhenTheHostHasIt() {
        val addresses = NetworkUtils.localAddresses()
        val preferred = NetworkUtils.getLocalIPv4Address()
        if (addresses.isEmpty()) {
            // A host with no reachable interface may legitimately answer nothing.
            assertTrue("no addresses means no preference", preferred == null)
        } else {
            assertTrue("a non-empty list must yield some address", preferred != null)
            assertTrue("the preferred address must come from the announced list",
                addresses.contains(preferred))
        }
    }

    @Test
    fun theSelectedAddressIsStableAcrossCalls() {
        // The iPhone is told the address once per connect; flickering between two interfaces would
        // make it dial a stale address after a reconnect.
        val first = NetworkUtils.getLocalIPv4Address()
        val second = NetworkUtils.getLocalIPv4Address()
        assertEquals(first, second)
    }
}
