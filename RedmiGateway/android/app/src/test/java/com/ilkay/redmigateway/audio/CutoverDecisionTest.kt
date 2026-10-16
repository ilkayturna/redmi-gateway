package com.ilkay.redmigateway.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoverDecisionTest {
    private fun decide(
        live: Boolean = true,
        writesRate: Int = 50,
        readsRate: Int = 50,
        uplinkAgeMs: Long = 20,
        downlinkAgeMs: Long = 20,
        iphoneFresh: Boolean = true,
        iphoneHearing: Boolean = true
    ) = CutoverDecision.canHandOver(
        live = live,
        writesRate = writesRate,
        readsRate = readsRate,
        minFramesPerSecond = 38,
        uplinkAgeMs = uplinkAgeMs,
        downlinkAgeMs = downlinkAgeMs,
        iphoneFresh = iphoneFresh,
        iphoneHearing = iphoneHearing
    )

    // A live call where both directions are flowing is the only case that may mute the Redmi.
    @Test fun freshAudioInBothDirectionsHandsOver() {
        assertTrue(decide())
        assertTrue(decide(uplinkAgeMs = CutoverDecision.FRESHNESS_MS))
    }

    // Catches the burst-then-death handover: the rates stay full because concealed frames are
    // counted, but the iPhone stopped sending, so muting the Redmi would leave the call silent.
    @Test fun framesThatStoppedArrivingDoNotHandOver() {
        assertFalse(decide(uplinkAgeMs = CutoverDecision.FRESHNESS_MS + 1))
        assertFalse(decide(uplinkAgeMs = 400))
    }

    // Catches the same for the caller's own path: reads that stopped are not a working downlink.
    @Test fun aStalledDownlinkDoesNotHandOver() {
        assertFalse(decide(downlinkAgeMs = CutoverDecision.FRESHNESS_MS + 1))
    }

    // Catches a direction that never delivered anything at all.
    @Test fun aDirectionThatNeverArrivedDoesNotHandOver() {
        assertFalse(decide(uplinkAgeMs = Long.MAX_VALUE))
        assertFalse(decide(downlinkAgeMs = Long.MAX_VALUE))
    }

    // Catches handing over on a call that is no longer live (on hold or being torn down).
    @Test fun aCallThatIsNotLiveDoesNotHandOver() {
        assertFalse(decide(live = false))
    }

    // Catches a rate dip being ignored because the audio happens to be fresh.
    @Test fun aStalledRateDoesNotHandOver() {
        assertFalse(decide(writesRate = 37))
        assertFalse(decide(readsRate = 37))
        assertTrue(decide(writesRate = 38, readsRate = 38))
    }

    // Catches the existing safety rules being dropped while adding the freshness one.
    @Test fun theOlderSafetyRulesStillApply() {
        assertFalse(decide(iphoneFresh = false))
        assertFalse(decide(iphoneHearing = false))
    }
}
