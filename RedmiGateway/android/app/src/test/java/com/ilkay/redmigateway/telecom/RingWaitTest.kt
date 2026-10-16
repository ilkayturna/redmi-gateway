package com.ilkay.redmigateway.telecom

import com.ilkay.redmigateway.service.IPhoneReachability.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how long the Redmi holds its own ringer back for the iPhone.
 *
 * This is the "when one of them answers the other goes quiet" behaviour seen from the other end.
 * The Redmi deliberately waits before ringing so the two phones do not ring over each other, and
 * the wait is bounded by how the iPhone can actually be reached.
 *
 * Both failure directions are silent, which is why the numbers are asserted rather than described.
 * Wait too long and a call is missed because the iPhone never woke. Wait too little and both
 * phones ring at the person at once, which is the thing the wait exists to prevent.
 */
class RingWaitTest {

    @Test
    fun withTheSwitchOffTheRedmiRingsImmediately() {
        // The user asked for their own ringer, so no waiting at all whatever the iPhone is doing.
        for (level in Level.values()) {
            assertEquals("level=$level", 0L, ringWaitMs(quietWhenIPhoneRings = false, level = level))
        }
    }

    @Test
    fun withNoPairingThereIsNothingToWaitFor() {
        assertEquals(0L, ringWaitMs(quietWhenIPhoneRings = true, level = Level.UNPAIRED))
    }

    @Test
    fun aLinkedIPhoneGetsTheShortWait() {
        assertEquals(RedmiRinger.WAIT_NEARBY_MS, ringWaitMs(true, Level.CONNECTED))
        assertEquals(RedmiRinger.WAIT_NEARBY_MS, ringWaitMs(true, Level.BLUETOOTH))
    }

    @Test
    fun aPushOnlyIPhoneGetsTheLongerWait() {
        // The push leaves after about 1.5 s and then has further to travel, so the wait is longer.
        assertEquals(RedmiRinger.WAIT_PUSH_MS, ringWaitMs(true, Level.PUSH))
    }

    @Test
    fun anOfflineIPhoneIsTreatedAsCloseBy() {
        // Nothing is known to be far away, so the shorter wait applies and the call is not missed.
        assertEquals(RedmiRinger.WAIT_NEARBY_MS, ringWaitMs(true, Level.OFFLINE))
    }

    @Test
    fun theWaitIsAlwaysBoundedSoACallIsNeverHeldIndefinitely() {
        for (level in Level.values()) {
            val wait = ringWaitMs(true, level)
            assertTrue("level=$level waited ${wait}ms", wait in 0..10_000L)
        }
    }

    @Test
    fun thePushWaitIsStrictlyLongerThanTheNearbyOne() {
        // If they ever crossed, the phone that is harder to reach would get less time.
        assertTrue(RedmiRinger.WAIT_PUSH_MS > RedmiRinger.WAIT_NEARBY_MS)
    }

    @Test
    fun everyLevelProducesAWait() {
        val waits = Level.values().map { ringWaitMs(true, it) }.toSet()
        assertEquals("two waits: none and the two bounded ones", setOf(0L, RedmiRinger.WAIT_NEARBY_MS, RedmiRinger.WAIT_PUSH_MS), waits)
    }
}
