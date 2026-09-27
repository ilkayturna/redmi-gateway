package com.ilkay.redmigateway.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.telecom.CallAudioState
import android.util.Log
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.telecom.GatewayInCallService
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Diagnostics for the Qualcomm call-audio routing on this Redmi. Opens the Telephony Rx
 * extraction and the Telephony Tx injection independently during a live call so the audio
 * HAL's routing of the voice call can be observed step by step. Nothing is stored; the
 * injected signal is a generated tone. Refuses to run while the relay owns call audio.
 */
object CallAudioLab {
    private const val TAG = "CallAudioLab"
    private const val SAMPLE_RATE = 16000
    private const val FRAME_SAMPLES = 320

    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var writerThread: Thread? = null

    // Downlink statistics for the current second
    @Volatile private var dlFrames = 0
    @Volatile private var dlZeroFrames = 0
    @Volatile private var dlSumSquares = 0.0
    @Volatile private var dlToneEnergy = 0.0
    // Uplink statistics for the current second
    @Volatile private var ulFrames = 0
    @Volatile private var ulMaxWriteMs = 0L
    @Volatile private var ulRoutedType = -1

    @Synchronized
    fun handle(context: Context, op: String): JSONObject {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val result = JSONObject().put("op", op)
        if (CallAudioRelay.isStreaming && op != "status") {
            return result.put("error", "relay is streaming")
        }
        if (op != "status") Log.i(TAG, "LAB_OP $op endpoint=${GatewayInCallService.currentEndpointType()}")
        when (op) {
            "downlink_open" -> result.put("ok", openDownlink(audioManager))
            "downlink_close" -> { closeDownlink(); result.put("ok", true) }
            "uplink_open" -> result.put("ok", openUplink(audioManager))
            "music_uplink_open" -> result.put("ok", openMusicUplink(audioManager))
            "mic_mute_on" -> { audioManager.isMicrophoneMute = true; result.put("ok", audioManager.isMicrophoneMute) }
            "mic_mute_off" -> { audioManager.isMicrophoneMute = false; result.put("ok", !audioManager.isMicrophoneMute) }
            "voice_stream_mute_on" -> { audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0); result.put("ok", audioManager.isStreamMute(AudioManager.STREAM_VOICE_CALL)) }
            "voice_stream_mute_off" -> { audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0); result.put("ok", !audioManager.isStreamMute(AudioManager.STREAM_VOICE_CALL)) }
            "rx_devmute_on" -> { audioManager.setParameters("device_mute=true;direction=rx"); result.put("ok", true) }
            "rx_devmute_off" -> { audioManager.setParameters("device_mute=false;direction=rx"); result.put("ok", true) }
            "device_mute_on" -> { audioManager.setParameters("device_mute=true;direction=tx"); result.put("ok", true) }
            "device_mute_off" -> { audioManager.setParameters("device_mute=false;direction=tx"); result.put("ok", true) }
            "uplink_close" -> { closeUplink(); result.put("ok", true) }
            "route_earpiece" -> { GatewayInCallService.setAudioRoute(CallAudioState.ROUTE_EARPIECE); result.put("ok", true) }
            "route_speaker" -> { GatewayInCallService.setAudioRoute(CallAudioState.ROUTE_SPEAKER); result.put("ok", true) }
            "tone" -> { toneOn = true; result.put("ok", true) }
            "silence" -> { toneOn = false; result.put("ok", true) }
            "reapply_endpoint" -> result.put("ok", GatewayInCallService.reapplyCurrentEndpoint())
            "preferred_device" -> result.put("ok", reprefer(audioManager))
            "comm_device" -> result.put("ok", setCommDevice(audioManager))
            "clear_comm_device" -> { runCatching { audioManager.clearCommunicationDevice() }; result.put("ok", true) }
            "close_all" -> { closeUplink(); closeDownlink(); result.put("ok", true) }
            "proxy_unprocessed" -> result.put("ok", openProxyCapture(audioManager, 9))
            "proxy48_unprocessed" -> result.put("ok", openProxyCapture(audioManager, 9, 48000, AudioFormat.CHANNEL_IN_STEREO))
            "proxy_recognition" -> result.put("ok", openProxyCapture(audioManager, 6))
            "proxy_mic" -> result.put("ok", openProxyCapture(audioManager, 1))
            "proxy_communication" -> result.put("ok", openProxyCapture(audioManager, 7))
            "proxy_default" -> result.put("ok", openProxyCapture(audioManager, 0))
            "voice_down_source" -> result.put("ok", openProxyCapture(audioManager, 3))
            "ulrec_open" -> result.put("ok", openUplinkRecord())
            "voice_call_source" -> result.put("ok", openProxyCapture(audioManager, 4))
            "status" -> Unit
            else -> result.put("error", "unknown op")
        }
        return result.put("status", snapshot(audioManager, reset = false))
    }

    /** One-second statistics; resets the counters. */
    fun snapshot(audioManager: AudioManager, reset: Boolean = true): JSONObject {
        val frames = dlFrames
        val json = JSONObject()
            .put("callState", CallManager.currentCallState)
            .put("mode", audioManager.mode)
            .put("micMuted", audioManager.isMicrophoneMute)
            .put("downlinkOpen", record != null)
            .put("dlFrames", frames)
            .put("dlZeroFrames", dlZeroFrames)
            .put("dlRms", if (frames > 0) sqrt(dlSumSquares / (frames * FRAME_SAMPLES)) else 0.0)
            .put("toneRatio", if (dlSumSquares > 0) dlToneEnergy / dlSumSquares else 0.0)
            .put("uplinkOpen", track != null)
            .put("ulFrames", ulFrames)
            .put("ulMaxWriteMs", ulMaxWriteMs)
            .put("ulRoutedType", ulRoutedType)
        if (reset) {
            dlFrames = 0; dlZeroFrames = 0; dlSumSquares = 0.0; dlToneEnergy = 0.0
            ulFrames = 0; ulMaxWriteMs = 0L
        }
        return json
    }

    val isActive: Boolean get() = record != null || track != null

    @Volatile private var toneOn = false

    private fun reprefer(audioManager: AudioManager): Boolean {
        val player = track ?: return false
        val tx = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_TELEPHONY } ?: return false
        player.setPreferredDevice(null)
        val ok = player.setPreferredDevice(tx)
        Log.i(TAG, "LAB_STEP preferred_device ok=$ok")
        return ok
    }

    private fun setCommDevice(audioManager: AudioManager): Boolean {
        val earpiece = audioManager.availableCommunicationDevices
            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } ?: return false
        val ok = audioManager.setCommunicationDevice(earpiece)
        Log.i(TAG, "LAB_STEP comm_device ok=$ok")
        return ok
    }

    private fun format(channelMask: Int) = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(channelMask)
        .build()

    private fun openDownlink(audioManager: AudioManager): Boolean {
        if (record != null) return true
        val recorder = try {
            AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
                .invoke(audioManager, format(AudioFormat.CHANNEL_IN_MONO)) as AudioRecord
        } catch (error: Exception) {
            Log.w(TAG, "LAB downlink unavailable: ${describe(error)}")
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        record = recorder
        recorder.startRecording()
        Log.i(TAG, "LAB_STEP downlink_open")
        readerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frame = ShortArray(FRAME_SAMPLES)
            while (record === recorder) {
                val read = recorder.read(frame, 0, FRAME_SAMPLES)
                if (read <= 0) {
                    SystemClock.sleep(5)
                    continue
                }
                var zero = true
                var sum = 0.0
                for (i in 0 until read) {
                    val s = frame[i].toInt()
                    if (s != 0) zero = false
                    sum += (s * s).toDouble()
                }
                dlFrames += 1
                if (zero) dlZeroFrames += 1
                dlSumSquares += sum
            }
        }, "CallAudioLab-Downlink").apply { start() }
        return true
    }

    /**
     * Captures from the Telephony Rx device with an ordinary capture source instead of
     * VOICE_DOWNLINK, so the HAL may keep its afe-proxy-record usecase rather than
     * converting it into in-call record.
     */
    private fun openProxyCapture(
        audioManager: AudioManager,
        source: Int,
        rate: Int = SAMPLE_RATE,
        channels: Int = AudioFormat.CHANNEL_IN_MONO
    ): Boolean {
        closeDownlink()
        val rx = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_TELEPHONY }
        if (rx == null) {
            Log.w(TAG, "LAB no Telephony Rx input device")
            return false
        }
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(channels)
                    .build())
                .setBufferSizeInBytes(rate / 50 * 4 * 8)
                .build()
        } catch (error: Exception) {
            Log.w(TAG, "LAB proxy capture source=$source failed: ${describe(error)}")
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        val preferred = recorder.setPreferredDevice(rx)
        record = recorder
        recorder.startRecording()
        Log.i(TAG, "LAB_STEP proxy_capture source=$source rate=$rate preferred=$preferred routed=${recorder.routedDevice?.type}")
        startReader(recorder)
        return true
    }

    /** The in-call record uplink tap: what the modem receives. */
    private fun openUplinkRecord(): Boolean {
        closeDownlink()
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_UPLINK)
                .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
                .setBufferSizeInBytes(FRAME_SAMPLES * 2 * 8)
                .build()
        } catch (error: Exception) {
            Log.w(TAG, "LAB uplink record failed: ${describe(error)}")
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        record = recorder
        recorder.startRecording()
        Log.i(TAG, "LAB_STEP ulrec_open")
        startReader(recorder)
        return true
    }

    private fun startReader(recorder: AudioRecord) {
        readerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frame = ShortArray(FRAME_SAMPLES)
            val coeff = 2 * kotlin.math.cos(2 * PI * 440.0 / SAMPLE_RATE)
            while (record === recorder) {
                val read = recorder.read(frame, 0, FRAME_SAMPLES)
                if (read <= 0) {
                    SystemClock.sleep(5)
                    continue
                }
                var zero = true
                var sum = 0.0
                var s1 = 0.0
                var s2 = 0.0
                for (i in 0 until read) {
                    val s = frame[i].toInt()
                    if (s != 0) zero = false
                    sum += (s * s).toDouble()
                    val s0 = s + coeff * s1 - s2
                    s2 = s1
                    s1 = s0
                }
                // Goertzel power at 440 Hz scaled to the same units as sum of squares.
                val mag2 = s1 * s1 + s2 * s2 - coeff * s1 * s2
                dlToneEnergy += 2.0 * mag2 / read
                dlFrames += 1
                if (zero) dlZeroFrames += 1
                dlSumSquares += sum
            }
        }, "CallAudioLab-Capture").apply { start() }
    }

    private fun closeDownlink() {
        val recorder = record ?: return
        record = null
        runCatching { recorder.stop() }
        readerThread?.join(500)
        readerThread = null
        recorder.release()
        Log.i(TAG, "LAB_STEP downlink_close")
    }

    private fun openUplink(audioManager: AudioManager): Boolean {
        if (track != null) return true
        val player = try {
            AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
                .invoke(audioManager, format(AudioFormat.CHANNEL_OUT_STEREO)) as AudioTrack
        } catch (error: Exception) {
            Log.w(TAG, "LAB uplink unavailable: ${describe(error)}")
            return false
        }
        if (player.state != AudioTrack.STATE_INITIALIZED) {
            player.release()
            return false
        }
        track = player
        player.play()
        Log.i(TAG, "LAB_STEP uplink_open tone=$toneOn")
        writerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val stereo = ShortArray(FRAME_SAMPLES * 2)
            var phase = 0.0
            while (track === player) {
                // 440 Hz at roughly -20 dBFS, or digital silence; only a routing probe.
                val amplitude = if (toneOn) 3200 else 0
                for (i in 0 until FRAME_SAMPLES) {
                    val v = (amplitude * sin(phase)).toInt().toShort()
                    stereo[2 * i] = v
                    stereo[2 * i + 1] = v
                    phase += 2 * PI * 440.0 / SAMPLE_RATE
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                val started = SystemClock.elapsedRealtime()
                val written = player.write(stereo, 0, stereo.size, AudioTrack.WRITE_BLOCKING)
                val ms = SystemClock.elapsedRealtime() - started
                if (written < 0) {
                    Log.w(TAG, "LAB uplink write failed: $written")
                    SystemClock.sleep(20)
                    continue
                }
                ulFrames += 1
                if (ms > ulMaxWriteMs) ulMaxWriteMs = ms
                player.routedDevice?.let { ulRoutedType = it.type }
            }
        }, "CallAudioLab-Uplink").apply { start() }
        return true
    }

    /**
     * A media track explicitly routed to Telephony Tx during a call. AudioPolicyManager then
     * selects the INCALL_MUSIC output, which the Qualcomm HAL mixes into the voice uplink.
     */
    private fun openMusicUplink(audioManager: AudioManager): Boolean {
        if (track != null) return true
        val tx = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_TELEPHONY } ?: return false
        val player = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(format(AudioFormat.CHANNEL_OUT_STEREO))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(FRAME_SAMPLES * 4 * 8)
                .build()
        } catch (error: Exception) {
            Log.w(TAG, "LAB music uplink failed: ${describe(error)}")
            return false
        }
        val preferred = player.setPreferredDevice(tx)
        if (player.state != AudioTrack.STATE_INITIALIZED) {
            player.release()
            return false
        }
        track = player
        player.play()
        Log.i(TAG, "LAB_STEP music_uplink_open preferred=$preferred tone=$toneOn")
        startWriter(player)
        return true
    }

    private fun startWriter(player: AudioTrack) {
        writerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val stereo = ShortArray(FRAME_SAMPLES * 2)
            var phase = 0.0
            while (track === player) {
                val amplitude = if (toneOn) 3200 else 0
                for (i in 0 until FRAME_SAMPLES) {
                    val v = (amplitude * sin(phase)).toInt().toShort()
                    stereo[2 * i] = v
                    stereo[2 * i + 1] = v
                    phase += 2 * PI * 440.0 / SAMPLE_RATE
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                val started = SystemClock.elapsedRealtime()
                val written = player.write(stereo, 0, stereo.size, AudioTrack.WRITE_BLOCKING)
                val ms = SystemClock.elapsedRealtime() - started
                if (written < 0) {
                    Log.w(TAG, "LAB uplink write failed: $written")
                    SystemClock.sleep(20)
                    continue
                }
                ulFrames += 1
                if (ms > ulMaxWriteMs) ulMaxWriteMs = ms
                player.routedDevice?.let { ulRoutedType = it.type }
            }
        }, "CallAudioLab-MusicUplink").apply { start() }
    }

    private fun closeUplink() {
        val player = track ?: return
        track = null
        writerThread?.join(500)
        writerThread = null
        runCatching { player.pause() }
        runCatching { player.flush() }
        player.release()
        Log.i(TAG, "LAB_STEP uplink_close")
    }

    private fun describe(error: Throwable): String {
        val cause = if (error is InvocationTargetException) error.targetException ?: error else error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }
}
