package com.ilkay.redmigateway.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OutgoingRingbackTest {
    private fun silent() = ByteArray(640)

    private fun loud(amplitude: Int = 3277): ByteArray {
        val pcm = ByteArray(640)
        for (i in 0 until 320) {
            val sample = if (i % 2 == 0) amplitude else -amplitude
            pcm[2 * i] = (sample and 0xFF).toByte()
            pcm[2 * i + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return pcm
    }

    private fun peak(pcm: ByteArray): Int {
        var peak = 0
        for (i in 0 until pcm.size / 2) {
            val sample = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()
            peak = maxOf(peak, abs(sample))
        }
        return peak
    }

    // Catches the 29.10.2026 21:11 silence: a dialing call with a silent downlink gets the tone.
    @Test fun silentDialingFramesCarryTheRingingTone() {
        val ringback = OutgoingRingback()
        var frame = silent()
        // The first frame fades in from zero; by the second the tone is at full level.
        assertTrue(ringback.process(frame, dialing = true))
        frame = silent()
        assertTrue(ringback.process(frame, dialing = true))
        assertTrue("tone near -15 dBFS peak, got ${peak(frame)}", peak(frame) in 5000..6000)
        assertTrue(ringback.playing)
    }

    // Catches a cadence error: 2 s on, then 4 s of silence, then on again.
    @Test fun cadenceIsTwoSecondsOnFourOff() {
        val ringback = OutgoingRingback()
        val peaks = (0 until 300).map { // 300 frames = 6 s
            val frame = silent()
            ringback.process(frame, dialing = true)
            peak(frame)
        }
        assertTrue(peaks.subList(1, 99).all { it > 4000 })
        assertTrue(peaks.subList(101, 300).all { it == 0 })
        ringback.process(silent(), dialing = true) // frame 300: the next cycle fades in
        ringback.process(silent(), dialing = true)
        val again = silent()
        ringback.process(again, dialing = true)
        assertTrue(peak(again) > 4000)
    }

    // Catches covering the network: its own ringback or an announcement must pass untouched.
    @Test fun networkAudioPassesAndTurnsTheToneOffForTheCall() {
        val ringback = OutgoingRingback()
        ringback.process(silent(), dialing = true)
        repeat(OutgoingRingback.NETWORK_AUDIO_FRAMES - 1) {
            val frame = loud()
            assertTrue("a single loud frame is not yet media", ringback.process(frame, dialing = true))
        }
        val announcement = loud()
        assertFalse(ringback.process(announcement, dialing = true))
        assertEquals(3277, peak(announcement))
        assertTrue(ringback.networkAudioHeard)
        // Silence after the announcement stays silence: the network is in charge of this call.
        val after = silent()
        assertFalse(ringback.process(after, dialing = true))
        assertEquals(0, peak(after))
    }

    // Catches comfort noise mistaken for media: a faint frame keeps the tone going.
    @Test fun faintNoiseIsNotNetworkAudio() {
        val ringback = OutgoingRingback()
        repeat(10) {
            val frame = loud(amplitude = 100) // about -50 dBFS
            assertTrue(ringback.process(frame, dialing = true))
        }
        assertFalse(ringback.networkAudioHeard)
    }

    // Catches the tone leaking into an answered call, and a new call starting deaf to media.
    @Test fun leavingDialingStopsAndResets() {
        val ringback = OutgoingRingback()
        repeat(OutgoingRingback.NETWORK_AUDIO_FRAMES) { ringback.process(loud(), dialing = true) }
        assertTrue(ringback.networkAudioHeard)

        val answered = silent()
        assertFalse(ringback.process(answered, dialing = false))
        assertEquals(0, peak(answered))
        assertFalse(ringback.networkAudioHeard)
        assertFalse(ringback.playing)

        assertTrue(ringback.process(silent(), dialing = true))
    }
}
