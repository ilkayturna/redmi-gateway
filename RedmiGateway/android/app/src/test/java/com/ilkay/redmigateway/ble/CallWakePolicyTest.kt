package com.ilkay.redmigateway.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallWakePolicyTest {
    private val started = 1_000_000L

    // The regression the user hit: a thirty second window went dark while the iPhone was still
    // waking, and it only connected at 58 seconds.
    @Test fun theBeaconOutlivesTheOldThirtySecondWindow() {
        assertTrue(CallWakePolicy.outgoingAsksForWake(true, false, started, started + 30_000))
        assertTrue(CallWakePolicy.outgoingAsksForWake(true, false, started, started + 58_000))
        assertTrue(CallWakePolicy.outgoingAsksForWake(true, false, started, started + 120_000))
    }

    // Catches the beacon burning for a call that ended or for an iPhone that already answered.
    @Test fun aCallThatIsOverOrAlreadyLinkedStopsAsking() {
        assertFalse(CallWakePolicy.outgoingAsksForWake(false, false, started, started + 10_000))
        assertFalse(CallWakePolicy.outgoingAsksForWake(true, true, started, started + 10_000))
    }

    // Catches a stuck call state keeping the radio lit forever.
    @Test fun theGuardClosesAVeryLongCall() {
        assertTrue(CallWakePolicy.outgoingAsksForWake(true, false, started, started + CallWakePolicy.WAKE_MAX_MS - 1))
        assertFalse(CallWakePolicy.outgoingAsksForWake(true, false, started, started + CallWakePolicy.WAKE_MAX_MS))
    }

    // Catches a call that was never added from starting a wake window out of nowhere.
    @Test fun aCallWithoutAStartTimeDoesNotAsk() {
        assertFalse(CallWakePolicy.outgoingAsksForWake(true, false, 0L, started))
    }
}
