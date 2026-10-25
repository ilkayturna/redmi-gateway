package com.ilkay.redmigateway.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Loudness of the PCM16 frames sent to the iPhone, so "the other side sounds quiet" can be told
 * apart from a quiet line: before this nothing in the relay measured level at all.
 *
 * Pauses would drag a plain average down to silence, so the speech level only averages frames
 * whose own RMS is above [SPEECH_GATE_DBFS]. A telephone speech level around -26 to -18 dBFS is
 * normal; well below that the caller's line is quiet before the iPhone ever plays it.
 */
class PcmLevelMeter {
    data class Level(val speechRmsDbfs: Int, val peakDbfs: Int, val activePercent: Int)

    private companion object {
        const val FULL_SCALE = 32768.0
        const val SPEECH_GATE_DBFS = -50.0
        /** Reported when nothing (or only silence) was measured. */
        const val FLOOR_DBFS = -99
    }

    private var frames = 0
    private var activeFrames = 0
    private var activeSumSquares = 0.0
    private var activeSamples = 0L
    private var peak = 0

    @Synchronized fun add(pcm: ByteArray, length: Int = pcm.size) {
        val samples = length / 2
        if (samples == 0) return
        var sumSquares = 0.0
        var framePeak = 0
        for (i in 0 until samples) {
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sumSquares += sample.toDouble() * sample
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > framePeak) framePeak = magnitude
        }
        frames++
        if (framePeak > peak) peak = framePeak
        if (dbfs(sqrt(sumSquares / samples)) >= SPEECH_GATE_DBFS) {
            activeFrames++
            activeSumSquares += sumSquares
            activeSamples += samples
        }
    }

    /** Level since the previous call; the next window starts empty. */
    @Synchronized fun takeLevel(): Level {
        val speech = if (activeSamples > 0) dbfs(sqrt(activeSumSquares / activeSamples)) else Double.NEGATIVE_INFINITY
        val level = Level(
            speechRmsDbfs = rounded(speech),
            peakDbfs = rounded(dbfs(peak.toDouble())),
            activePercent = if (frames > 0) activeFrames * 100 / frames else 0
        )
        frames = 0
        activeFrames = 0
        activeSumSquares = 0.0
        activeSamples = 0
        peak = 0
        return level
    }

    private fun dbfs(amplitude: Double) =
        if (amplitude <= 0.0) Double.NEGATIVE_INFINITY else 20 * log10(amplitude / FULL_SCALE)

    private fun rounded(value: Double) =
        if (value.isInfinite() || value < FLOOR_DBFS) FLOOR_DBFS else Math.round(value).toInt()
}
