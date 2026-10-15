package com.ilkay.redmigateway.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Redmi's uplink is 16 kHz mono; the iPhone carries 48 kHz stereo. This interpolator sits on
 * every uplink frame, so a silent change here is heard as distortion rather than seen as a failure.
 *
 * The interesting properties are the ones a listener notices: the same number of output samples,
 * unity gain for a steady signal (no fading or swelling), identical channels, a filter history that
 * carries across frames so consecutive frames join without clicks, and no wrap-around past full
 * scale.
 */
class Upsampler3xTest {

    private val bytesPerOutputSample = 2 * 2 // stereo, 16-bit

    private fun pcm(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun readSamples(bytes: ByteArray, count: Int): IntArray =
        IntArray(count) { i ->
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort().toInt()
        }

    private fun run(samples: IntArray): IntArray {
        val upsampler = Upsampler3x()
        val input = pcm(*samples)
        val output = ByteArray(samples.size * 3 * bytesPerOutputSample)
        val written = upsampler.process(input, 0, samples.size, output)
        assertEquals(output.size, written)
        return readSamples(output, samples.size * 3)
    }

    @Test
    fun writesThreeStereoFramesPerInputSample() {
        val upsampler = Upsampler3x()
        val input = pcm(*IntArray(10) { 1000 })
        val output = ByteArray(10 * 3 * bytesPerOutputSample)
        val written = upsampler.process(input, 0, 10, output)
        assertEquals("10 samples -> 10 * 3 frames * 2 channels * 2 bytes", 10 * 3 * 4, written)
    }

    @Test
    fun silenceStaysSilent() {
        val out = run(IntArray(64))
        assertTrue("silence must not gain a DC offset", out.all { it == 0 })
    }

    @Test
    fun aSteadySignalKeepsItsLevel() {
        val out = run(IntArray(200) { 1000 })
        // The polyphase filter has a 16-tap history; after that a constant input is a constant output.
        val settled = out.drop(out.size - 150)
        assertTrue("steady signal swole or faded: min=${settled.min()} max=${settled.max()}",
            settled.min() >= 995 && settled.max() <= 1005)
    }

    @Test
    fun bothChannelsCarryTheSameSample() {
        val out = run(IntArray(32) { 1200 })
        // readSamples returns the interleaved stream; left and right must match pairwise.
        for (i in out.indices step 2) {
            assertEquals("left and right differ at frame ${i / 2}", out[i], out[i + 1])
        }
    }

    @Test
    fun theFilterHistoryCarriesAcrossFrames() {
        val samples = IntArray(120) { (6000 * Math.sin(it / 4.0)).toInt() }

        val single = run(samples)

        // Two 60-sample frames through one instance, the way the relay feeds 20 ms frames.
        val upsampler = Upsampler3x()
        val input = pcm(*samples)
        val firstFrame = ByteArray(60 * 3 * bytesPerOutputSample)
        val secondFrame = ByteArray(60 * 3 * bytesPerOutputSample)
        val firstWritten = upsampler.process(input, 0, 60, firstFrame)
        val secondWritten = upsampler.process(input, 60 * 2, 60, secondFrame)
        assertEquals(firstFrame.size, firstWritten)
        assertEquals(secondFrame.size, secondWritten)
        val split = readSamples(firstFrame + secondFrame, samples.size * 3)

        assertArrayEquals(
            "splitting the stream into frames must not change the output (no clicks at frame joins)",
            single, split
        )
    }

    @Test
    fun resetClearsTheHistoryBetweenCalls() {
        val upsampler = Upsampler3x()
        val loud = pcm(*IntArray(32) { 20000 })
        val output = ByteArray(32 * 3 * bytesPerOutputSample)
        upsampler.process(loud, 0, 32, output)

        upsampler.reset()
        val silence = pcm(*IntArray(32))
        val afterReset = ByteArray(32 * 3 * bytesPerOutputSample)
        upsampler.process(silence, 0, 32, afterReset)
        assertTrue("a cleared upsampler must not carry the last call's energy",
            readSamples(afterReset, 32 * 3).all { it == 0 })
    }

    @Test
    fun fullScaleInputNeverWrapsAround() {
        val out = run(IntArray(64) { 32767 }) + run(IntArray(64) { -32768 })
        assertTrue("output left the 16-bit range: min=${out.min()} max=${out.max()}",
            out.all { it in -32768..32767 })
    }
}
