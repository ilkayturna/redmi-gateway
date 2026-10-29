package com.ilkay.redmigateway.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPulsePolicyTest {
    // 30.10.2026: each pulse woke the iPhone, the socket held ~80 ms, and the count started over,
    // so the iPhone was woken every five minutes all night.
    @Test fun aBriefWokenLinkDoesNotResetTheBackoff() {
        var pulses = 0
        val delays = ArrayList<Long>()
        repeat(12) {
            pulses++
            val (count, delay) = ReconnectPulsePolicy.afterLinkEnds(heldMs = 80L, pulses = pulses)
            pulses = count
            delays.add(delay)
        }
        assertEquals(5 * 60_000L, delays[0])
        assertEquals(10 * 60_000L, delays[3])
        assertEquals(30 * 60_000L, delays[11])
    }

    // Catches a real recovery being treated as a failed wake.
    @Test fun aLinkThatHeldStartsOverWithTheGrace() {
        assertEquals(0 to ReconnectPulsePolicy.GRACE_MS, ReconnectPulsePolicy.afterLinkEnds(heldMs = 60_000L, pulses = 7))
    }

    @Test fun neverSoonerThanTheGrace() {
        for (pulses in 0..20) {
            val (_, delay) = ReconnectPulsePolicy.afterLinkEnds(heldMs = null, pulses = pulses)
            assert(delay >= ReconnectPulsePolicy.GRACE_MS)
        }
    }
}
