package com.ilkay.redmigateway.audio

import org.junit.Assert.assertEquals
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

    // Catches the average keeping the iPhone's slow start in the denominator: a measured call began
    // at 14 frames per second, and the handover then waited 1431 ms even though the recent rate had
    // been 53 frames per second for a while.
    @Test fun aRampedUpStreamOpensTheAudioPath() {
        assertTrue(start(streamMs = 1_431, streamFrames = 76, recentRate = 53))
        assertTrue(start(streamMs = 600, streamFrames = 30, recentRate = 50))
    }

    // Catches the opposite: a stream that is slow right now must not open the audio path.
    @Test fun aSlowStreamDoesNotOpenTheAudioPath() {
        assertFalse(start(recentRate = 14))
        assertFalse(start(recentRate = 37))
    }

    // Catches opening the audio path before there is any real evidence.
    @Test fun tooLittleAudioDoesNotOpenTheAudioPath() {
        assertFalse(start(streamMs = 200))
        assertFalse(start(streamFrames = 11))
        assertFalse(start(live = false))
        assertFalse(start(iphoneFresh = false))
    }

    // The start boundary is exactly the configured evidence: 240 ms, 12 frames, 38 frames a second.
    @Test fun theStartBoundaryIsExactlyTheConfiguredEvidence() {
        assertTrue(start(streamMs = 240, streamFrames = 12, recentRate = 38))
        assertFalse(start(streamMs = 239, streamFrames = 12, recentRate = 38))
        assertFalse(start(streamMs = 240, streamFrames = 11, recentRate = 38))
    }

    private fun start(
        live: Boolean = true,
        iphoneFresh: Boolean = true,
        streamMs: Long = 600,
        streamFrames: Int = 30,
        recentRate: Int = 50
    ) = CutoverDecision.canStartMedia(
        live = live,
        iphoneFresh = iphoneFresh,
        streamMs = streamMs,
        streamFrames = streamFrames,
        recentRate = recentRate,
        minStreamMs = 240,
        minFrames = 12,
        minFramesPerSecond = 38
    )

    // Names the single condition that waits, so a slow handover is never guessed at: an iPhone that
    // ramps up slowly and a gate that is merely strict read the same in one total.
    @Test fun theWaitingConditionIsNamedExactlyOnce() {
        assertEquals("notLive", blocker(live = false))
        assertEquals("stale", blocker(iphoneFresh = false))
        assertEquals("tooShort", blocker(streamMs = 100))
        assertEquals("tooFewFrames", blocker(streamFrames = 5))
        assertEquals("rateLow", blocker(recentRate = 20))
        assertEquals("ready", blocker())
    }

    // The named reason and the decision must never disagree, or the log would explain the opposite
    // of what the code actually did.
    @Test fun theNamedReasonAndTheDecisionAgree() {
        val cases = listOf(
            blocker() to true,
            blocker(live = false) to false,
            blocker(iphoneFresh = false) to false,
            blocker(streamMs = 239) to false,
            blocker(streamFrames = 11) to false,
            blocker(recentRate = 37) to false
        )
        for ((reason, expected) in cases) {
            assertEquals(expected, reason == CutoverDecision.READY)
        }
    }

    private fun blocker(
        live: Boolean = true,
        iphoneFresh: Boolean = true,
        streamMs: Long = 600,
        streamFrames: Int = 30,
        recentRate: Int = 50
    ) = CutoverDecision.startMediaBlocker(
        live = live,
        iphoneFresh = iphoneFresh,
        streamMs = streamMs,
        streamFrames = streamFrames,
        recentRate = recentRate,
        minStreamMs = 240,
        minFrames = 12,
        minFramesPerSecond = 38
    )
}

