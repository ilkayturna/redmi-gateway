package com.ilkay.redmigateway.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.ilkay.redmigateway.telecom.CallManager
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class AudioProbeResult(
    val sourceName: String,
    val sourceId: Int,
    val status: String,
    val exception: String? = null,
    val sampleRate: Int = 16000,
    val channelConfig: String = "CHANNEL_IN_MONO",
    val encoding: String = "ENCODING_PCM_16BIT",
    val recordState: String = "UNKNOWN",
    val samplesRead: Long = 0,
    val durationSeconds: Double = 0.0,
    val peakAmplitude: Int = 0,
    val rmsEnergy: Double = 0.0,
    val classification: String = "UNKNOWN"
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("sourceName", sourceName)
            put("sourceId", sourceId)
            put("status", status)
            if (exception != null) put("exception", exception)
            put("sampleRate", sampleRate)
            put("channelConfig", channelConfig)
            put("encoding", encoding)
            put("recordState", recordState)
            put("samplesRead", samplesRead)
            put("durationSeconds", durationSeconds)
            put("peakAmplitude", peakAmplitude)
            put("rmsEnergy", rmsEnergy)
            put("classification", classification)
        }
    }
}

object AudioProbeManager {
    private const val TAG = "AudioProbeManager"

    // The 6 specific sources requested
    val TARGET_SOURCES = listOf(
        Pair("MIC", MediaRecorder.AudioSource.MIC),                                         // 1
        Pair("VOICE_COMMUNICATION", MediaRecorder.AudioSource.VOICE_COMMUNICATION),         // 7
        Pair("VOICE_CALL", MediaRecorder.AudioSource.VOICE_CALL),                           // 4
        Pair("VOICE_UPLINK", MediaRecorder.AudioSource.VOICE_UPLINK),                       // 3
        Pair("VOICE_DOWNLINK", MediaRecorder.AudioSource.VOICE_DOWNLINK),                   // 2
        Pair("REMOTE_SUBMIX", MediaRecorder.AudioSource.REMOTE_SUBMIX)                      // 8
    )

    fun runAllProbes(): List<AudioProbeResult> {
        val results = mutableListOf<AudioProbeResult>()
        for ((name, id) in TARGET_SOURCES) {
            val res = runProbe(name, id)
            results.add(res)
            // Short pause between probes to allow HAL to clean up
            Thread.sleep(500)
        }
        return results
    }

    fun runProbeForSource(targetName: String): AudioProbeResult {
        val found = TARGET_SOURCES.firstOrNull { it.first.equals(targetName, ignoreCase = true) }
        return if (found != null) {
            runProbe(found.first, found.second)
        } else {
            AudioProbeResult(
                sourceName = targetName,
                sourceId = -1,
                status = "UNKNOWN_SOURCE",
                exception = "Source '$targetName' not recognized in test suite",
                classification = "ERROR"
            )
        }
    }

    fun runProbe(sourceName: String, sourceId: Int): AudioProbeResult {
        CallManager.log("--> Starting Audio Probe: $sourceName (AudioSource=$sourceId)...")
        Log.i(TAG, "Starting Audio Probe: $sourceName (id=$sourceId)")

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufSize <= 0) {
            val msg = "Invalid minBufferSize ($minBufSize) for $sourceName at $sampleRate Hz"
            CallManager.log("Probe $sourceName: $msg")
            return AudioProbeResult(
                sourceName = sourceName,
                sourceId = sourceId,
                status = "INVALID_BUFFER_SIZE",
                exception = msg,
                sampleRate = sampleRate,
                classification = "FAILED"
            )
        }

        val bufferSize = max(minBufSize * 2, sampleRate * 2) // At least 1 second buffer
        var audioRecord: AudioRecord? = null

        try {
            audioRecord = AudioRecord(sourceId, sampleRate, channelConfig, audioFormat, bufferSize)
            val state = audioRecord.state
            val stateStr = if (state == AudioRecord.STATE_INITIALIZED) "INITIALIZED" else "UNINITIALIZED"

            if (state != AudioRecord.STATE_INITIALIZED) {
                val err = "AudioRecord created but STATE_UNINITIALIZED (HAL/Policy declined source)"
                CallManager.log("Probe $sourceName: $err")
                audioRecord.release()
                return AudioProbeResult(
                    sourceName = sourceName,
                    sourceId = sourceId,
                    status = "UNINITIALIZED",
                    exception = err,
                    sampleRate = sampleRate,
                    recordState = stateStr,
                    classification = "INITIALIZATION_FAILED"
                )
            }

            CallManager.log("Probe $sourceName: Initialized successfully. Starting recording (5s)...")
            audioRecord.startRecording()

            val recState = audioRecord.recordingState
            if (recState != AudioRecord.RECORDSTATE_RECORDING) {
                val err = "Failed to start: recordingState=$recState"
                CallManager.log("Probe $sourceName: $err")
                audioRecord.stop()
                audioRecord.release()
                return AudioProbeResult(
                    sourceName = sourceName,
                    sourceId = sourceId,
                    status = "RECORD_START_FAILED",
                    exception = err,
                    sampleRate = sampleRate,
                    recordState = "STATE_INITIALIZED, RECSTATE=$recState",
                    classification = "FAILED"
                )
            }

            // Collect ~5.0 seconds of PCM chunks
            val chunkSamples = sampleRate / 10 // 100ms per read (1600 samples)
            val tempBuffer = ShortArray(chunkSamples)
            var totalSamplesRead: Long = 0
            var sumSquare: Double = 0.0
            var peak: Int = 0

            val startTime = System.currentTimeMillis()
            val targetDurationMs = 5000L

            while (System.currentTimeMillis() - startTime < targetDurationMs) {
                val read = audioRecord.read(tempBuffer, 0, chunkSamples)
                if (read > 0) {
                    for (i in 0 until read) {
                        val sample = tempBuffer[i].toInt()
                        val absSample = abs(sample)
                        if (absSample > peak) {
                            peak = absSample
                        }
                        sumSquare += sample.toDouble() * sample.toDouble()
                    }
                    totalSamplesRead += read
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned error code: $read")
                }
            }

            audioRecord.stop()
            audioRecord.release()
            audioRecord = null

            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
            val rms = if (totalSamplesRead > 0) sqrt(sumSquare / totalSamplesRead) else 0.0

            val classification = when {
                totalSamplesRead == 0L -> "ZERO_SAMPLES_READ"
                rms < 5.0 && peak < 30 -> "SILENT"
                rms < 30.0 -> "FAINT_NOISE"
                else -> "AUDIO_DETECTED"
            }

            val summaryMsg = "Probe $sourceName: RMS=%.2f, Peak=%d, Dur=%.1fs, Classification=%s".format(
                rms, peak, elapsedSec, classification
            )
            CallManager.log(summaryMsg)

            return AudioProbeResult(
                sourceName = sourceName,
                sourceId = sourceId,
                status = "SUCCESS",
                sampleRate = sampleRate,
                recordState = "INITIALIZED",
                samplesRead = totalSamplesRead,
                durationSeconds = elapsedSec,
                peakAmplitude = peak,
                rmsEnergy = rms,
                classification = classification
            )

        } catch (e: SecurityException) {
            val err = "SecurityException: ${e.message}"
            CallManager.log("Probe $sourceName: $err")
            audioRecord?.release()
            return AudioProbeResult(
                sourceName = sourceName,
                sourceId = sourceId,
                status = "SECURITY_EXCEPTION",
                exception = e.message ?: "Permission denied by OS",
                sampleRate = sampleRate,
                classification = "SECURITY_EXCEPTION"
            )
        } catch (e: IllegalArgumentException) {
            val err = "IllegalArgumentException: ${e.message}"
            CallManager.log("Probe $sourceName: $err")
            audioRecord?.release()
            return AudioProbeResult(
                sourceName = sourceName,
                sourceId = sourceId,
                status = "ILLEGAL_ARGUMENT",
                exception = e.message ?: "Invalid parameters",
                sampleRate = sampleRate,
                classification = "ILLEGAL_ARGUMENT"
            )
        } catch (e: Exception) {
            val err = "${e.javaClass.simpleName}: ${e.message}"
            CallManager.log("Probe $sourceName: $err")
            audioRecord?.release()
            return AudioProbeResult(
                sourceName = sourceName,
                sourceId = sourceId,
                status = "EXCEPTION",
                exception = err,
                sampleRate = sampleRate,
                classification = "EXCEPTION"
            )
        }
    }
}
