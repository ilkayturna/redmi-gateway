package com.ilkay.redmigateway.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ringing tone ("çalıyor") the iPhone hears while an outgoing call alerts, for networks that
 * send no early media.
 *
 * On Turkcell's Wi-Fi calling path the downlink stays silent until the callee answers and the
 * phone is expected to play the ringback itself. On the Redmi that local tone comes from Telecom's
 * RingbackPlayer on the Redmi's own earpiece, which the call-audio extraction never sees: on
 * 29.10.2026 21:11 the iPhone got 19 s of digital silence (downlink peak -99 dBFS) while the callee
 * rang, and the owner took the audio back to the Redmi to check whether the call was going through.
 *
 * While the call dials, silent frames are replaced with the Turkish ringing tone (ITU-T E.180:
 * 450 Hz, 2 s on, 4 s off). As soon as the network itself sends audio (its own ringback, a busy
 * tone, an announcement such as "aradığınız kişiye ulaşılamıyor") the real audio passes and the
 * tone stays off for the rest of that call, so nothing the network says is ever covered.
 *
 * Not thread-safe: the downlink sender thread owns it.
 */
class OutgoingRingback(private val sampleRate: Int = 16_000) {
    companion object {
        const val FREQUENCY_HZ = 450.0
        const val ON_MS = 2_000
        const val CYCLE_MS = 6_000
        /** Peak amplitude, about -18 dBFS RMS: a ringing tone, not louder than speech. */
        const val AMPLITUDE = 0.18
        /** Fade in and out over this long, so the cadence does not click. */
        const val RAMP_MS = 10
        /**
         * RMS above which a frame is the network's own audio (about -45 dBFS). Comfort noise and
         * idle codec frames stay below it; a network ringback or announcement is near -20 dBFS.
         */
        const val NETWORK_AUDIO_RMS = 184.0
        /** Consecutive loud frames before the network's audio is trusted: one click is not media. */
        const val NETWORK_AUDIO_FRAMES = 3
    }

    private val onSamples = sampleRate.toLong() * ON_MS / 1000
    private val cycleSamples = sampleRate.toLong() * CYCLE_MS / 1000
    private val rampSamples = (sampleRate.toLong() * RAMP_MS / 1000).coerceAtLeast(1)

    private var position = 0L
    private var loudFrames = 0
    /** The network sent its own audio during this call; the tone stays off until the next one. */
    var networkAudioHeard = false
        private set
    /** Whether the last processed frame carried the tone. */
    var playing = false
        private set

    /**
     * Fills [frame] (mono PCM16 little-endian) with the tone when [dialing] and the frame carries no
     * network audio. Returns whether the frame now holds the tone. Outside dialing it resets, so the
     * next outgoing call starts at the beginning of the cadence and listens for media again.
     */
    fun process(frame: ByteArray, dialing: Boolean): Boolean {
        if (!dialing) {
            reset()
            return false
        }
        if (networkAudioHeard) {
            playing = false
            return false
        }
        if (rms(frame) >= NETWORK_AUDIO_RMS) {
            loudFrames++
            if (loudFrames >= NETWORK_AUDIO_FRAMES) {
                networkAudioHeard = true
                playing = false
                return false
            }
        } else {
            loudFrames = 0
        }
        fillTone(frame)
        playing = true
        return true
    }

    fun reset() {
        position = 0
        loudFrames = 0
        networkAudioHeard = false
        playing = false
    }

    private fun fillTone(frame: ByteArray) {
        val samples = frame.size / 2
        for (i in 0 until samples) {
            val n = position + i
            val inCycle = n % cycleSamples
            val value = if (inCycle < onSamples) {
                val edge = minOf(inCycle, onSamples - 1 - inCycle)
                val envelope = if (edge < rampSamples) edge.toDouble() / rampSamples else 1.0
                AMPLITUDE * envelope * sin(2 * PI * FREQUENCY_HZ * n / sampleRate)
            } else {
                0.0
            }
            val sample = (value * 32767).toInt().coerceIn(-32768, 32767)
            frame[2 * i] = (sample and 0xFF).toByte()
            frame[2 * i + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        position += samples
    }

    private fun rms(frame: ByteArray): Double {
        val samples = frame.size / 2
        if (samples == 0) return 0.0
        var sumSquares = 0.0
        for (i in 0 until samples) {
            val lo = frame[2 * i].toInt() and 0xFF
            val hi = frame[2 * i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / samples)
    }
}
