package com.ilkay.redmigateway.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleBondWatchTest {
    // The burst the Redmi logged at 01:08:36-01:08:40 on 30.10.2026: ten links, each ending
    // ~140 ms after it connected, about 300 ms apart.
    @Test fun theStalePairingBurstIsSuspectedOnce() {
        val watch = BleBondWatch()
        var changes = 0
        for (i in 0 until 10) if (watch.linkEnded(now = 1_000L + i * 300L, heldMs = 140L, reachedGatt = false)) changes++
        assertTrue(watch.suspected)
        assertTrue(changes == 1)
    }

    // Catches a working link, or one that got as far as the GATT server, being counted as failed.
    @Test fun linksThatWorkedAreNotFailures() {
        val watch = BleBondWatch()
        for (i in 0 until 10) watch.linkEnded(now = 1_000L + i * 300L, heldMs = 140L, reachedGatt = true)
        for (i in 0 until 10) watch.linkEnded(now = 9_000L + i * 300L, heldMs = 60_000L, reachedGatt = false)
        for (i in 0 until 10) watch.linkEnded(now = 20_000L + i * 300L, heldMs = null, reachedGatt = false)
        assertFalse(watch.suspected)
    }

    // Catches a stray scanner that connects now and then from raising the warning.
    @Test fun failuresSpreadOverMinutesAreNotABurst() {
        val watch = BleBondWatch()
        for (i in 0 until 10) watch.linkEnded(now = i * 61_000L, heldMs = 100L, reachedGatt = false)
        assertFalse(watch.suspected)
    }

    @Test fun aWorkingLinkClearsTheSuspicionOnce() {
        val watch = BleBondWatch()
        for (i in 0 until 5) watch.linkEnded(now = i * 300L, heldMs = 140L, reachedGatt = false)
        assertTrue(watch.suspected)
        assertTrue(watch.linkWorks())
        assertFalse(watch.suspected)
        assertFalse(watch.linkWorks())
    }
}
