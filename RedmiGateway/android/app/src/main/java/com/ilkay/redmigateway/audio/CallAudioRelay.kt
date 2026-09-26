package com.ilkay.redmigateway.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.ilkay.redmigateway.service.GatewayService
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.telecom.GatewayInCallService
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Carries the cellular call audio between the Redmi and the paired iPhone.
 *
 * The caller's voice is taken from the call downlink with
 * AudioManager#getCallDownlinkExtractionAudioRecord and the iPhone microphone is
 * injected into the call uplink with AudioManager#getCallUplinkInjectionAudioTrack.
 * Both are system APIs guarded by CALL_AUDIO_INTERCEPTION, so the gateway must be
 * installed as a privileged app.
 *
 * UDP packets on port 8791 use the same layout in both directions:
 *   [0..3] session id (uint32 BE)  [4..5] sequence (uint16 BE)
 *   [6]    payload type (0 keepalive, 1 PCM)  [7] reserved
 *   [8..]  20 ms of 16 kHz mono PCM, 16-bit little-endian (640 bytes)
 */
object CallAudioRelay : CallManager.CallListener {
    const val PORT = 8791
    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 320

    private const val TAG = "CallAudioRelay"
    private const val FRAME_BYTES = FRAME_SAMPLES * 2
    private const val HEADER_BYTES = 8
    private const val PAYLOAD_PCM16: Byte = 1
    private const val START_RETRY_MS = 400L
    private const val MAX_START_ATTEMPTS = 25
    private const val ARM_TIMEOUT_MS = 90_000L

    // AudioManager.MODE_CALL_SCREENING / MODE_CALL_REDIRECT; not all compile SDKs expose them.
    private const val MODE_CALL_SCREENING = 4
    private const val MODE_CALL_REDIRECT = 5

    interface Listener {
        fun onAudioRelayState(running: Boolean, detail: String)
    }

    @Volatile var listener: Listener? = null

    /** Mutes the Redmi's own microphone while the iPhone carries the conversation. */
    @Volatile var muteLocalMic = false

    private val random = SecureRandom()
    private val worker = HandlerThread("CallAudioRelay", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(worker.looper)

    private var appContext: Context? = null
    @Volatile private var session = 0
    @Volatile private var peer: SocketAddress? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var streaming = false
    private var receiveThread: Thread? = null
    private var sendThread: Thread? = null
    private var startAttempts = 0
    private var savedVoiceVolume = -1
    private var didMuteLocalMic = false
    private var listeningToCalls = false

    @Volatile private var packetsIn = 0L
    @Volatile private var packetsOut = 0L

    private val armTimeout = Runnable {
        if (session != 0 && !streaming) disarmOnWorker("no call audio within ${ARM_TIMEOUT_MS / 1000}s")
    }
    private val startAttempt = Runnable { tryStartStreaming() }

    val isStreaming: Boolean get() = streaming

    /**
     * Opens a relay session for the paired iPhone and returns its id. Audio starts as soon as
     * the Redmi call reaches a live state; a session left without a call expires on its own.
     */
    fun arm(context: Context): Int {
        synchronized(this) {
            appContext = context.applicationContext
            if (session == 0) {
                var id = 0
                while (id == 0) id = random.nextInt()
                session = id
                peer = null
                try {
                    openSocket()
                } catch (error: Exception) {
                    session = 0
                    throw error
                }
            }
        }
        handler.post {
            if (!listeningToCalls) {
                listeningToCalls = true
                CallManager.addListener(this)
            }
            handler.removeCallbacks(armTimeout)
            handler.postDelayed(armTimeout, ARM_TIMEOUT_MS)
            if (isLiveCallState(CallManager.currentCallState)) scheduleStart(0L)
        }
        CallManager.log("Call audio relay armed")
        return session
    }

    fun disarm(reason: String) {
        handler.post { disarmOnWorker(reason) }
    }

    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        handler.post {
            if (session == 0) return@post
            when {
                isLiveCallState(state) && !streaming -> scheduleStart(0L)
                state == "disconnected" -> disarmOnWorker("call ended")
            }
        }
    }

    override fun onLogMessage(message: String) = Unit

    private fun isLiveCallState(state: String) =
        state == "dialing" || state == "connecting" || state == "active"

    private fun scheduleStart(delayMs: Long) {
        handler.removeCallbacks(startAttempt)
        if (delayMs == 0L) startAttempts = 0
        handler.postDelayed(startAttempt, delayMs)
    }

    private fun openSocket() {
        if (socket?.isClosed == false) return
        val datagram = DatagramSocket(null).apply {
            reuseAddress = true
            receiveBufferSize = 64 * 1024
            sendBufferSize = 64 * 1024
            bind(InetSocketAddress(PORT))
        }
        socket = datagram
        receiveThread = Thread({ receiveLoop(datagram) }, "CallAudio-Uplink").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun tryStartStreaming() {
        if (session == 0 || streaming) return
        val context = appContext ?: return
        val audioManager = context.getSystemService(AudioManager::class.java)
        val mode = audioManager.mode
        val inCallMode = mode == AudioManager.MODE_IN_CALL ||
            mode == MODE_CALL_SCREENING || mode == MODE_CALL_REDIRECT
        if (!inCallMode) {
            retryStart("audio mode $mode is not in-call yet")
            return
        }

        val downlink = openDownlink(audioManager)
        val uplink = openUplink(audioManager)
        if (downlink == null && uplink == null) {
            retryStart("call audio endpoints unavailable")
            return
        }

        record = downlink
        track = uplink
        packetsIn = 0
        packetsOut = 0
        streaming = true
        handler.removeCallbacks(armTimeout)

        uplink?.play()
        downlink?.let { recorder ->
            recorder.startRecording()
            sendThread = Thread({ sendLoop(recorder) }, "CallAudio-Downlink").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }

        GatewayService.setCallAudioForeground(true)
        if (muteLocalMic && downlink != null && uplink != null) quietRedmiHandset(audioManager)

        val detail = "downlink=${downlink != null}, uplink=${uplink != null}, localMicMuted=$muteLocalMic"
        CallManager.log("Call audio relay streaming ($detail)")
        listener?.onAudioRelayState(true, detail)
    }

    private fun retryStart(reason: String) {
        startAttempts += 1
        if (startAttempts >= MAX_START_ATTEMPTS) {
            CallManager.log("Call audio relay could not start: $reason")
            listener?.onAudioRelayState(false, reason)
            return
        }
        handler.removeCallbacks(startAttempt)
        handler.postDelayed(startAttempt, START_RETRY_MS)
    }

    private fun disarmOnWorker(reason: String) {
        handler.removeCallbacks(startAttempt)
        handler.removeCallbacks(armTimeout)
        val wasActive = session != 0
        stopStreaming()
        synchronized(this) {
            session = 0
            peer = null
            socket?.close()
            socket = null
        }
        receiveThread?.join(500)
        receiveThread = null
        if (listeningToCalls) {
            listeningToCalls = false
            CallManager.removeListener(this)
        }
        if (wasActive) {
            CallManager.log("Call audio relay stopped: $reason")
            listener?.onAudioRelayState(false, reason)
        }
    }

    private fun stopStreaming() {
        if (!streaming) return
        streaming = false
        val context = appContext
        record?.let { recorder ->
            runCatching { recorder.stop() }
        }
        sendThread?.join(500)
        sendThread = null
        record?.release()
        record = null
        track?.let { player ->
            runCatching { player.pause() }
            runCatching { player.flush() }
            player.release()
        }
        track = null
        GatewayService.setCallAudioForeground(false)
        if (context != null) restoreRedmiHandset(context.getSystemService(AudioManager::class.java))
        CallManager.log("Call audio relay packets: in=$packetsIn out=$packetsOut")
    }

    private fun receiveLoop(datagram: DatagramSocket) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!datagram.isClosed) {
            try {
                packet.setLength(buffer.size)
                datagram.receive(packet)
            } catch (error: Exception) {
                if (!datagram.isClosed) Log.w(TAG, "Uplink receive failed", error)
                continue
            }
            if (packet.length < HEADER_BYTES) continue
            val header = ByteBuffer.wrap(buffer, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            val packetSession = header.int
            val expected = session
            if (expected == 0 || packetSession != expected) continue
            peer = packet.socketAddress
            if (buffer[6] != PAYLOAD_PCM16) continue
            val payload = packet.length - HEADER_BYTES
            if (payload <= 0 || payload % 2 != 0) continue
            track?.write(buffer, HEADER_BYTES, payload, AudioTrack.WRITE_NON_BLOCKING)
            packetsIn += 1
        }
    }

    private fun sendLoop(recorder: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ByteArray(HEADER_BYTES + FRAME_BYTES)
        var sequence = 0
        var levelFrames = 0
        var sumSquares = 0.0
        while (streaming) {
            val read = recorder.read(frame, HEADER_BYTES, FRAME_BYTES)
            if (read <= 0) {
                if (read < 0) SystemClock.sleep(10)
                continue
            }
            for (index in HEADER_BYTES until HEADER_BYTES + read - 1 step 2) {
                val sample = ((frame[index + 1].toInt() shl 8) or (frame[index].toInt() and 0xFF)).toShort().toDouble()
                sumSquares += sample * sample
            }
            levelFrames += 1
            if (levelFrames == 250) {
                val rms = sqrt(sumSquares / (levelFrames * FRAME_SAMPLES))
                Log.i(TAG, "Downlink level over 5s: rms=${"%.1f".format(rms)} in=$packetsIn out=$packetsOut")
                levelFrames = 0
                sumSquares = 0.0
            }

            val target = peer ?: continue
            val datagram = socket ?: break
            ByteBuffer.wrap(frame, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
                .putInt(session)
                .putShort(sequence.toShort())
                .put(PAYLOAD_PCM16)
                .put(0)
            sequence = (sequence + 1) and 0xFFFF
            try {
                datagram.send(DatagramPacket(frame, HEADER_BYTES + read, target))
                packetsOut += 1
            } catch (error: Exception) {
                if (!datagram.isClosed) Log.w(TAG, "Downlink send failed", error)
            }
        }
    }

    private fun pcmFormat(channelMask: Int) = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(channelMask)
        .build()

    private fun openDownlink(audioManager: AudioManager): AudioRecord? {
        try {
            val method = AudioManager::class.java.getMethod(
                "getCallDownlinkExtractionAudioRecord", AudioFormat::class.java
            )
            val recorder = method.invoke(audioManager, pcmFormat(AudioFormat.CHANNEL_IN_MONO)) as AudioRecord
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            recorder.release()
        } catch (error: Exception) {
            Log.w(TAG, "Downlink extraction unavailable: ${describe(error)}")
        }

        // Older path: capture the downlink directly with CAPTURE_AUDIO_OUTPUT.
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_DOWNLINK, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_BYTES * 8)
            )
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder
            } else {
                recorder.release()
                null
            }
        } catch (error: Exception) {
            Log.w(TAG, "VOICE_DOWNLINK capture unavailable: ${describe(error)}")
            null
        }
    }

    private fun openUplink(audioManager: AudioManager): AudioTrack? {
        try {
            val method = AudioManager::class.java.getMethod(
                "getCallUplinkInjectionAudioTrack", AudioFormat::class.java
            )
            val player = method.invoke(audioManager, pcmFormat(AudioFormat.CHANNEL_OUT_MONO)) as AudioTrack
            if (player.state == AudioTrack.STATE_INITIALIZED) return player
            player.release()
        } catch (error: Exception) {
            Log.w(TAG, "Uplink injection unavailable: ${describe(error)}")
        }

        // Fallback: route a voice track explicitly to the telephony TX device.
        val telephonyTx = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            ?: return null
        return try {
            val player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(pcmFormat(AudioFormat.CHANNEL_OUT_MONO))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(FRAME_BYTES * 8)
                .build()
            player.setPreferredDevice(telephonyTx)
            if (player.state == AudioTrack.STATE_INITIALIZED) player else {
                player.release()
                null
            }
        } catch (error: Exception) {
            Log.w(TAG, "Telephony TX track unavailable: ${describe(error)}")
            null
        }
    }

    private fun quietRedmiHandset(audioManager: AudioManager) {
        GatewayInCallService.setGatewayMuted(true)
        didMuteLocalMic = true
        runCatching {
            savedVoiceVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                audioManager.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL),
                0
            )
        }
    }

    private fun restoreRedmiHandset(audioManager: AudioManager) {
        if (didMuteLocalMic) {
            GatewayInCallService.setGatewayMuted(false)
            didMuteLocalMic = false
        }
        if (savedVoiceVolume >= 0) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceVolume, 0) }
            savedVoiceVolume = -1
        }
    }

    /**
     * Measures both call audio directions during a live call: the caller's voice level on the
     * downlink and whether a 1 kHz tone can be written to the uplink. Nothing is stored.
     */
    fun selfTest(context: Context, muteMic: Boolean, seconds: Int = 5): JSONObject {
        val result = JSONObject()
        val audioManager = context.getSystemService(AudioManager::class.java)
        result.put("callState", CallManager.currentCallState)
        result.put("audioMode", audioManager.mode)
        result.put("interceptable", runCatching {
            AudioManager::class.java.getMethod("isPstnCallAudioInterceptable").invoke(audioManager) as Boolean
        }.getOrElse { "error: ${describe(it)}" })
        if (streaming) {
            result.put("error", "relay is streaming; end the iPhone audio session first")
            return result
        }

        val recorder = openDownlink(audioManager)
        val player = openUplink(audioManager)
        result.put("downlinkOpened", recorder != null)
        result.put("uplinkOpened", player != null)
        recorder?.let { result.put("downlinkSource", it.audioSource) }
        if (muteMic) GatewayInCallService.setGatewayMuted(true)

        val frames = seconds * 50
        val input = ShortArray(FRAME_SAMPLES)
        val tone = ShortArray(FRAME_SAMPLES)
        var phase = 0.0
        var sumSquares = 0.0
        var peak = 0
        var samplesRead = 0L
        var samplesWritten = 0L
        try {
            recorder?.startRecording()
            player?.play()
            for (frame in 0 until frames) {
                for (index in tone.indices) {
                    tone[index] = (6000 * sin(phase)).toInt().toShort()
                    phase += 2 * PI * 1000.0 / SAMPLE_RATE
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                player?.let { samplesWritten += maxOf(0, it.write(tone, 0, tone.size)) }
                if (recorder != null) {
                    val read = recorder.read(input, 0, input.size)
                    for (index in 0 until maxOf(0, read)) {
                        val sample = input[index].toInt()
                        sumSquares += sample.toDouble() * sample
                        peak = maxOf(peak, abs(sample))
                    }
                    samplesRead += maxOf(0, read)
                } else {
                    SystemClock.sleep(20)
                }
            }
        } catch (error: Exception) {
            result.put("error", describe(error))
        } finally {
            runCatching { recorder?.stop() }
            recorder?.release()
            runCatching { player?.stop() }
            player?.release()
            if (muteMic) GatewayInCallService.setGatewayMuted(false)
        }

        result.put("downlinkSamples", samplesRead)
        result.put("downlinkRms", if (samplesRead > 0) sqrt(sumSquares / samplesRead) else 0.0)
        result.put("downlinkPeak", peak)
        result.put("uplinkSamplesWritten", samplesWritten)
        result.put("localMicMuted", muteMic)
        CallManager.log("Call audio self-test: $result")
        return result
    }

    private fun describe(error: Throwable): String {
        val cause = if (error is InvocationTargetException) error.targetException ?: error else error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }
}
