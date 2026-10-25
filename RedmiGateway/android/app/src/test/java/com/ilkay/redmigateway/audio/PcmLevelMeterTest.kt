package com.ilkay.redmigateway.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmLevelMeterTest {
    // Catches pauses dragging the speech level down: half silence must not lower the reported level.
    @Test fun speechLevelIgnoresSilentFrames() {
        val meter = PcmLevelMeter()
        repeat(10) { meter.add(square(3277)) } // 0.1 of full scale = -20 dBFS
        repeat(10) { meter.add(square(0)) }
        val level = meter.takeLevel()
        assertEquals(-20, level.speechRmsDbfs)
        assertEquals(-20, level.peakDbfs)
        assertEquals(50, level.activePercent)
    }

    // Catches a quiet line being reported as normal speech.
    @Test fun quietLineReportsItsRealLevel() {
        val meter = PcmLevelMeter()
        repeat(5) { meter.add(square(328)) } // 0.01 of full scale = -40 dBFS
        assertEquals(-40, meter.takeLevel().speechRmsDbfs)
    }

    // Catches one window leaking into the next health line.
    @Test fun takeLevelStartsAFreshWindow() {
        val meter = PcmLevelMeter()
        meter.add(square(3277))
        meter.takeLevel()
        val empty = meter.takeLevel()
        assertEquals(-99, empty.speechRmsDbfs)
        assertEquals(-99, empty.peakDbfs)
        assertEquals(0, empty.activePercent)
    }

    /** One 20 ms frame of a +/- amplitude square wave, little-endian PCM16. */
    private fun square(amplitude: Int): ByteArray {
        val pcm = ByteArray(640)
        for (i in 0 until 320) {
            val sample = if (i % 2 == 0) amplitude else -amplitude
            pcm[2 * i] = (sample and 0xFF).toByte()
            pcm[2 * i + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return pcm
    }
}
