package com.ilkay.redmigateway.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the hotspot rules, which are the ones the user asked to keep.
 *
 * A known network means the hotspot goes off to save mobile data. No network at all means it comes
 * on so the iPhone has a way back. While the hotspot is up the Redmi keeps looking for a known
 * network to hand back to. And none of the switching happens mid-call, because the call audio runs
 * over that link and cutting it is worse than any amount of mobile data.
 *
 * These were only ever comments in SmartHotspot.evaluate. A wrong turn here costs the user data
 * allowance or the call audio, so they are asserted rather than described.
 */
class HotspotRulesTest {

    private fun decide(
        enabled: Boolean = true,
        wifi: Boolean,
        hotspot: Boolean,
        inCall: Boolean = false,
        wifiStableMs: Long = 60_000L,
        lostForMs: Long = 60_000L,
    ) = decideHotspotAction(enabled, wifi, hotspot, inCall, wifiStableMs, lostForMs)

    // ---- The rule the user named: a known network closes the hotspot ----

    @Test
    fun aKnownNetworkTurnsTheHotspotOff() {
        assertEquals(
            HotspotAction.STOP_HOTSPOT,
            decide(wifi = true, hotspot = true, wifiStableMs = SmartHotspot.STABLE_MS)
        )
    }

    @Test
    fun aNetworkThatHasOnlyJustAppearedDoesNotCountYet() {
        // A blip must not close the hotspot; the iPhone would lose its link for nothing.
        assertEquals(
            HotspotAction.NONE,
            decide(wifi = true, hotspot = true, wifiStableMs = SmartHotspot.STABLE_MS - 1)
        )
    }

    @Test
    fun anAlreadyClosedHotspotIsLeftAlone() {
        assertEquals(HotspotAction.NONE, decide(wifi = true, hotspot = false))
    }

    // ---- The other half: no network at all brings it back ----

    @Test
    fun losingWifiTurnsTheHotspotOn() {
        assertEquals(
            HotspotAction.START_HOTSPOT,
            decide(wifi = false, hotspot = false, lostForMs = SmartHotspot.LOST_MS)
        )
    }

    @Test
    fun aBriefWifiDropDoesNotBringTheHotspotUp() {
        assertEquals(
            HotspotAction.NONE,
            decide(wifi = false, hotspot = false, lostForMs = SmartHotspot.LOST_MS - 1)
        )
    }

    @Test
    fun anAlreadyRunningHotspotIsNotStartedAgain() {
        assertEquals(
            HotspotAction.TRY_KNOWN_NETWORK,
            decide(wifi = false, hotspot = true, lostForMs = SmartHotspot.LOST_MS)
        )
    }

    // ---- The protection that matters most: never switch mid-call ----

    @Test
    fun theHotspotIsNeverClosedDuringACall() {
        // Closing it would cut the iPhone's link while the call audio is running over it.
        for (stable in listOf(0L, SmartHotspot.STABLE_MS, 600_000L)) {
            assertEquals(
                "stable=${stable}ms",
                HotspotAction.NONE,
                decide(wifi = true, hotspot = true, inCall = true, wifiStableMs = stable)
            )
        }
    }

    @Test
    fun aKnownNetworkIsNotHuntedForDuringACall() {
        // Looking for a network means dropping the one the call is using.
        assertEquals(
            HotspotAction.NONE,
            decide(wifi = false, hotspot = true, inCall = true)
        )
    }

    @Test
    fun bringingTheHotspotUpIsAllowedDuringACall() {
        // The Wi-Fi is already gone, so turning the hotspot on can only help the iPhone.
        assertEquals(
            HotspotAction.START_HOTSPOT,
            decide(wifi = false, hotspot = false, inCall = true)
        )
    }

    // ---- The user's switch ----

    @Test
    fun theSwitchOffMeansNothingHappensAtAll() {
        for (wifi in listOf(true, false)) {
            for (hotspot in listOf(true, false)) {
                for (inCall in listOf(true, false)) {
                    assertEquals(
                        "wifi=$wifi hotspot=$hotspot inCall=$inCall",
                        HotspotAction.NONE,
                        decide(enabled = false, wifi = wifi, hotspot = hotspot, inCall = inCall, wifiStableMs = 600_000L, lostForMs = 600_000L)
                    )
                }
            }
        }
    }

    // ---- The property that makes the data promise hold ----

    @Test
    fun theOnlyWayTheHotspotStaysUpIsWithNoWifi() {
        // If this ever fails, the Redmi is burning mobile data while a known network is available,
        // which is the thing the user asked to protect.
        for (wifi in listOf(true, false)) {
            for (stable in listOf(0L, SmartHotspot.STABLE_MS - 1, SmartHotspot.STABLE_MS, 600_000L)) {
                val action = decide(wifi = wifi, hotspot = true, wifiStableMs = stable)
                if (wifi && stable >= SmartHotspot.STABLE_MS) {
                    assertEquals("hotspot should close", HotspotAction.STOP_HOTSPOT, action)
                } else if (wifi) {
                    assertEquals("hotspot should stay up only briefly", HotspotAction.NONE, action)
                }
            }
        }
    }

    @Test
    fun everyActionIsReachable() {
        val seen = mutableSetOf<HotspotAction>()
        for (wifi in listOf(true, false)) {
            for (hotspot in listOf(true, false)) {
                for (inCall in listOf(true, false)) {
                    for (elapsed in listOf(0L, SmartHotspot.STABLE_MS, 600_000L)) {
                        seen += decide(wifi = wifi, hotspot = hotspot, inCall = inCall, wifiStableMs = elapsed, lostForMs = elapsed)
                    }
                }
            }
        }
        assertEquals(
            setOf(HotspotAction.NONE, HotspotAction.STOP_HOTSPOT, HotspotAction.START_HOTSPOT, HotspotAction.TRY_KNOWN_NETWORK),
            seen
        )
    }
}
