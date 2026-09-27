package com.ilkay.redmigateway.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming 16 kHz -> 48 kHz interpolator for 16-bit mono speech, writing interleaved stereo.
 *
 * Zero-stuffs by 3 and filters with a windowed-sinc low-pass at 7.2 kHz, implemented as three
 * polyphase branches so each output sample costs TAPS / 3 multiplies. Filter history carries
 * across calls, so consecutive 20 ms frames join without clicks.
 */
class Upsampler3x {
    private companion object {
        const val FACTOR = 3
        const val TAPS = 48 // 16 per phase
        const val PHASE_TAPS = TAPS / FACTOR
        val PHASES: Array<FloatArray> = buildPhases()

        fun buildPhases(): Array<FloatArray> {
            val cutoff = 7200.0 / 48000.0 // normalized to the 48 kHz output rate
            val h = DoubleArray(TAPS)
            val mid = (TAPS - 1) / 2.0
            var sum = 0.0
            for (n in 0 until TAPS) {
                val x = n - mid
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val blackman = 0.42 - 0.5 * cos(2 * PI * n / (TAPS - 1)) + 0.08 * cos(4 * PI * n / (TAPS - 1))
                h[n] = sinc * blackman
                sum += h[n]
            }
            // Unity passband gain after zero-stuffing requires a total gain of FACTOR.
            val scale = FACTOR / sum
            return Array(FACTOR) { phase ->
                FloatArray(PHASE_TAPS) { k -> (h[k * FACTOR + phase] * scale).toFloat() }
            }
        }
    }

    private val history = FloatArray(PHASE_TAPS)
    private var head = 0

    fun reset() {
        history.fill(0f)
        head = 0
    }

    /**
     * Converts [samples] mono samples read little-endian from [input] at [offset] into
     * interleaved stereo 48 kHz little-endian PCM in [output]. Returns bytes written
     * (samples * 3 frames * 2 channels * 2 bytes).
     */
    fun process(input: ByteArray, offset: Int, samples: Int, output: ByteArray): Int {
        var out = 0
        for (i in 0 until samples) {
            val lo = input[offset + 2 * i].toInt() and 0xFF
            val hi = input[offset + 2 * i + 1].toInt()
            head = if (head == 0) PHASE_TAPS - 1 else head - 1
            history[head] = ((hi shl 8) or lo).toShort().toFloat()
            for (phase in 0 until FACTOR) {
                val coeffs = PHASES[phase]
                var acc = 0f
                var idx = head
                for (k in 0 until PHASE_TAPS) {
                    acc += coeffs[k] * history[idx]
                    idx += 1
                    if (idx == PHASE_TAPS) idx = 0
                }
                val v = when {
                    acc > 32767f -> 32767
                    acc < -32768f -> -32768
                    else -> acc.toInt()
                }
                val b0 = (v and 0xFF).toByte()
                val b1 = ((v shr 8) and 0xFF).toByte()
                output[out++] = b0; output[out++] = b1 // left
                output[out++] = b0; output[out++] = b1 // right
            }
        }
        return out
    }
}
