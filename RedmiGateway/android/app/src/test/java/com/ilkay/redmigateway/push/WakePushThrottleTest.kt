package com.ilkay.redmigateway.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePushThrottleTest {
    private val call = "call-1"

    // Catches a call that pushes on every state change: the first tick sends, the next must wait.
    @Test fun aCallPushesOnceWhenItStarts() {
        val throttle = WakePushThrottle()
        assertTrue(throttle.onTick(call, now = 1_000))
        assertFalse(throttle.onTick(call, now = 1_500))
        assertFalse(throttle.onTick(call, now = 9_000))
    }

    // Catches the retry never happening when the socket is still missing ten seconds in.
    @Test fun aMissingLinkGetsOneRetryAfterTenSeconds() {
        val throttle = WakePushThrottle()
        assertTrue(throttle.onTick(call, now = 1_000))
        assertTrue(throttle.onTick(call, now = 1_000 + WakePushThrottle.RETRY_AFTER_MS))
        assertEquals(2, throttle.total)
    }

    // Catches a spam loop: the retry must not keep repeating for the rest of the call.
    @Test fun theBudgetIsSpentAfterTwo() {
        val throttle = WakePushThrottle()
        assertTrue(throttle.onTick(call, now = 0))
        assertTrue(throttle.onTick(call, now = 30_000))
        assertFalse(throttle.onTick(call, now = 60_000))
        assertFalse(throttle.onTick(call, now = 600_000))
        assertEquals(2, throttle.total)
    }

    // Catches a new call being silenced by the previous call's spent budget.
    @Test fun aNewCallStartsWithAFreshBudget() {
        val throttle = WakePushThrottle()
        assertTrue(throttle.onTick(call, now = 0))
        assertTrue(throttle.onTick(call, now = 30_000))
        assertFalse(throttle.onTick(call, now = 60_000))
        assertTrue(throttle.onTick("call-2", now = 120_000))
        assertEquals(3, throttle.total)
    }

    // Catches a reconnect during a call handing out a fresh budget.
    @Test fun aReconnectDoesNotRefillTheBudget() {
        val throttle = WakePushThrottle()
        assertTrue(throttle.onTick(call, now = 0))
        assertTrue(throttle.onTick(call, now = 10_000))
        // The socket came back and dropped again: same call, so nothing new is allowed.
        assertFalse(throttle.onTick(call, now = 200_000))
    }

    // The diagnostic clock starts empty rather than claiming a push at time zero.
    @Test fun theLastPushTimeIsZeroUntilSomethingIsSent() {
        val throttle = WakePushThrottle()
        assertEquals(0L, throttle.lastAt)
        throttle.onTick(call, now = 5_000)
        assertEquals(5_000L, throttle.lastAt)
    }
}
