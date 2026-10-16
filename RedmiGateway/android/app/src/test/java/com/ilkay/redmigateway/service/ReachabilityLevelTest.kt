package com.ilkay.redmigateway.service

import com.ilkay.redmigateway.service.IPhoneReachability.Level
import com.ilkay.redmigateway.service.IPhoneReachability.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the Redmi says about the iPhone, which is the line the user complained about.
 *
 * The complaint was that it said the iPhone was not connected until the app was opened on it, even
 * though the app was installed and the phone was ringing for every call. Only the network socket
 * counted then, and iOS closes that socket on purpose when its app leaves the screen. The
 * Bluetooth link is a real way to reach a sleeping iPhone, so it now counts, and the order these
 * levels are chosen in is the whole meaning of the line.
 */
class ReachabilityLevelTest {

    private fun decide(
        paired: Boolean = true,
        hasSocket: Boolean = false,
        hasBluetoothLink: Boolean = false,
        pushReady: Boolean = false,
    ) = decideLevel(paired, hasSocket, hasBluetoothLink, pushReady)

    // ---- The bug the user reported ----

    @Test
    fun aSleepingIPhoneOnBluetoothIsNotReportedAsNotConnected() {
        // This is the case that used to say "not connected" while the phone was ringing.
        assertEquals(Level.BLUETOOTH, decide(hasBluetoothLink = true))
    }

    @Test
    fun aBluetoothLinkedIPhoneCountsAsLinked() {
        assertTrue(Status(Level.BLUETOOTH, "", "", "").linked)
        assertTrue(Status(Level.BLUETOOTH, "", "", "").reachable)
    }

    @Test
    fun aLiveSocketIsStillPreferred() {
        assertEquals(Level.CONNECTED, decide(hasSocket = true, hasBluetoothLink = true))
        assertTrue(Status(Level.CONNECTED, "", "", "").linked)
    }

    // ---- The order, which is the whole meaning ----

    @Test
    fun theOrderIsSocketThenBluetoothThenPushThenNothing() {
        assertEquals(Level.CONNECTED, decide(hasSocket = true, hasBluetoothLink = true, pushReady = true))
        assertEquals(Level.BLUETOOTH, decide(hasBluetoothLink = true, pushReady = true))
        assertEquals(Level.PUSH, decide(pushReady = true))
        assertEquals(Level.OFFLINE, decide())
    }

    @Test
    fun noPairingOutranksEverythingElse() {
        // Nothing else can be true without a pairing, so this must never be masked by a stale link.
        for (socket in listOf(true, false)) {
            for (bt in listOf(true, false)) {
                for (push in listOf(true, false)) {
                    assertEquals(
                        "socket=$socket bt=$bt push=$push",
                        Level.UNPAIRED,
                        decide(paired = false, hasSocket = socket, hasBluetoothLink = bt, pushReady = push)
                    )
                }
            }
        }
    }

    // ---- What the derived words mean ----

    @Test
    fun unpairedIsNeitherLinkedNorReachable() {
        val unpaired = Status(Level.UNPAIRED, "", "", "")
        assertFalse(unpaired.linked)
        assertFalse(unpaired.reachable)
    }

    @Test
    fun pushIsReachableEvenThoughItIsNotLinked() {
        // Away from home the iPhone is reached by Apple notification, which is not a link.
        val push = Status(Level.PUSH, "", "", "")
        assertFalse(push.linked)
        assertTrue(push.reachable)
    }

    @Test
    fun offlineIsNeither() {
        val offline = Status(Level.OFFLINE, "", "", "")
        assertFalse(offline.linked)
        assertFalse(offline.reachable)
    }

    @Test
    fun reachableAlwaysIncludesLinked() {
        // If a linked iPhone were ever not reachable the status line would contradict itself.
        for (level in Level.values()) {
            val status = Status(level, "", "", "")
            if (status.linked) assertTrue("$level is linked but not reachable", status.reachable)
        }
    }

    @Test
    fun everyLevelIsReachableFromSomeCombination() {
        val seen = mutableSetOf<Level>()
        for (paired in listOf(true, false)) {
            for (socket in listOf(true, false)) {
                for (bt in listOf(true, false)) {
                    for (push in listOf(true, false)) {
                        seen += decide(paired, socket, bt, push)
                    }
                }
            }
        }
        assertEquals(Level.values().toSet(), seen)
    }

    @Test
    fun everyLevelHasSomethingToSay() {
        // A blank status line is how "the iPhone is not connected" becomes unexplainable.
        val samples = listOf(
            decide(), decide(hasSocket = true), decide(hasBluetoothLink = true),
            decide(pushReady = true), decide(paired = false),
        )
        assertEquals(Level.values().toSet(), samples.toSet())
    }
}
