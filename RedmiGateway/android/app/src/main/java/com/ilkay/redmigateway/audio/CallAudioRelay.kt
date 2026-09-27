package com.ilkay.redmigateway.audio

import android.content.Context
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
import android.os.Build
import android.telecom.CallAudioState
import android.util.Log
import com.ilkay.redmigateway.service.GatewayService
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.telecom.GatewayInCallService
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
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
    // The Redmi's Telephony Tx sink advertises 8/16 kHz, not 48 kHz.
    private const val UPLINK_SAMPLE_RATE = SAMPLE_RATE
    private const val UPLINK_REPEAT = UPLINK_SAMPLE_RATE / SAMPLE_RATE

    private const val TAG = "CallAudioRelay"
    private const val FRAME_BYTES = FRAME_SAMPLES * 2
    private const val UPLINK_FRAME_BYTES = FRAME_BYTES * 2 * UPLINK_REPEAT
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

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners(running: Boolean, detail: String) {
        for (l in listeners) {
            runCatching { l.onAudioRelayState(running, detail) }
        }
    }

    @Deprecated("Use addListener/removeListener instead")
    var listener: Listener?
        get() = listeners.firstOrNull()
        set(value) {
            listeners.clear()
            if (value != null) listeners.add(value)
        }

    /** Mutes the Redmi's own microphone while the iPhone carries the conversation. */
    @Volatile var muteLocalMic = false

    private val random = SecureRandom()
    private val worker = HandlerThread("CallAudioRelay", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(worker.looper)

    private const val WATCHDOG_TIMEOUT_MS = 5000L
    private const val WATCHDOG_CHECK_INTERVAL_MS = 1000L

    private var appContext: Context? = null
    @Volatile private var session = 0
    @Volatile private var peer: SocketAddress? = null
    @Volatile private var expectedClientAddress: InetAddress? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var streaming = false
    @Volatile private var iPhoneAudioConfirmed = false
    @Volatile private var lastUplinkTimestamp = 0L
    @Volatile private var streamingStartedAt = 0L
    @Volatile private var watchdogFellBack = false
    private var receiveThread: Thread? = null
    private var uplinkWriterThread: Thread? = null
    private var sendThread: Thread? = null
    private val uplinkQueue = ArrayBlockingQueue<ByteArray>(20)
    private var startAttempts = 0
    private var savedVoiceVolume = -1
    private var savedVoiceMuted: Boolean? = null
    private var savedMediaVolume = -1
    private var savedMicrophoneMuted: Boolean? = null
    @Volatile private var uplinkRouteChecked = false
    @Volatile private var uplinkRouteIsTelephonyTx = false
    @Volatile private var uplinkUsesPlatformRedirection = false
    @Volatile private var redirectModeHeld = false
    private var platformProbeLogged = false
    private var listeningToCalls = false

    @Volatile private var packetsIn = 0L
    @Volatile private var packetsOut = 0L
    @Volatile private var uplinkPacketsReceived = 0L
    @Volatile private var uplinkWriteTimeMs = 0L
    @Volatile private var uplinkWriteMaxMs = 0L
    @Volatile private var uplinkSequenceGaps = 0L
    @Volatile private var uplinkPacketsDropped = 0L
    private var watchdogLastWritten = 0L
    private var watchdogSlowWindows = 0

    private val armTimeout = Runnable {
        if (session != 0 && !streaming) disarmOnWorker("no call audio within ${ARM_TIMEOUT_MS / 1000}s")
    }
    private val startAttempt = Runnable { tryStartStreaming() }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (streaming) {
                if (redirectModeHeld) {
                    val currentMode = appContext?.getSystemService(AudioManager::class.java)?.mode
                    if (currentMode != MODE_CALL_REDIRECT) {
                        Log.w(TAG, "CALL_REDIRECT ownership lost: actualMode=$currentMode; restoring Redmi audio")
                        disarmOnWorker("call audio route changed; Redmi restored")
                        return
                    }
                }
                val writtenThisSecond = packetsIn - watchdogLastWritten
                watchdogLastWritten = packetsIn
                if (iPhoneAudioConfirmed) {
                    watchdogSlowWindows = if (writtenThisSecond < 20) watchdogSlowWindows + 1 else 0
                } else {
                    watchdogSlowWindows = 0
                }
                val latestPacketOrStart = maxOf(lastUplinkTimestamp, streamingStartedAt)
                val elapsed = SystemClock.elapsedRealtime() - latestPacketOrStart
                if ((elapsed > WATCHDOG_TIMEOUT_MS || watchdogSlowWindows >= 2) && !watchdogFellBack) {
                    watchdogFellBack = true
                    iPhoneAudioConfirmed = false
                    Log.w(TAG, "Watchdog triggered: uplink gap=${elapsed}ms, writtenThisSecond=$writtenThisSecond. Restoring Redmi handset audio.")
                    CallManager.log("Watchdog: iPhone uplink too slow. Handset audio restored.")
                    if (redirectModeHeld) {
                        disarmOnWorker("iPhone audio timed out; Redmi restored")
                        return
                    }
                    appContext?.let { ctx ->
                        restoreRedmiHandset(ctx.getSystemService(AudioManager::class.java))
                    }
                    notifyListeners(false, "iPhone uplink too slow. Handset audio restored.")
                }
                handler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS)
            }
        }
    }

    val isStreaming: Boolean get() = streaming
    val isIPhoneAudioConfirmed: Boolean get() = iPhoneAudioConfirmed
    val isRedirectTrialActive: Boolean get() = streaming && redirectModeHeld
    val isAudioTransferUnavailable: Boolean get() = uplinkUsesPlatformRedirection && !redirectModeHeld && !iPhoneAudioConfirmed

    /**
     * Opens a relay session for the paired iPhone and returns its id. Audio starts as soon as
     * the Redmi call reaches a live state; a session left without a call expires on its own.
     */
    fun arm(context: Context, clientAddress: InetAddress? = null): Int {
        synchronized(this) {
            appContext = context.applicationContext
            expectedClientAddress = clientAddress
            if (session == 0) {
                var id = 0
                while (id == 0) id = random.nextInt()
                session = id
                peer = null
                lastUplinkTimestamp = 0L
                iPhoneAudioConfirmed = false
                try {
                    openSocket()
                } catch (error: Exception) {
                    session = 0
                    expectedClientAddress = null
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

        // Android's dedicated PSTN redirection mode disconnects the handset's normal
        // call patches while retaining access to Telephony Rx/Tx. Keep Telecom's own
        // MODE_IN_CALL request underneath ours so MODE_NORMAL restores it on exit.
        if (mode == AudioManager.MODE_IN_CALL && !enterCallRedirectMode(audioManager)) {
            disarmOnWorker("call redirection mode unavailable; Redmi audio retained")
            return
        }

        val downlink = openDownlink(audioManager)
        val uplink = openUplink(audioManager)
        if (downlink == null || uplink == null) {
            downlink?.release()
            uplink?.release()
            releaseCallRedirectMode(audioManager)
            disarmOnWorker("call redirection endpoints unavailable; Redmi audio retained")
            return
        }

        record = downlink
        track = uplink
        packetsIn = 0
        packetsOut = 0
        uplinkPacketsReceived = 0
        uplinkWriteTimeMs = 0
        uplinkWriteMaxMs = 0
        uplinkSequenceGaps = 0
        uplinkPacketsDropped = 0
        uplinkQueue.clear()
        watchdogLastWritten = 0
        watchdogSlowWindows = 0
        uplinkRouteChecked = false
        uplinkRouteIsTelephonyTx = false
        platformProbeLogged = false
        streaming = true
        streamingStartedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(armTimeout)

        uplink?.play()
        uplinkWriterThread = Thread({ uplinkWriteLoop(uplink) }, "CallAudio-TelephonyTx").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        downlink?.let { recorder ->
            recorder.startRecording()
            sendThread = Thread({ sendLoop(recorder) }, "CallAudio-Downlink").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }

        GatewayService.setCallAudioForeground(true)
        watchdogFellBack = false
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS)

        val detail = "downlink=true, uplink=true, waitingForIPhone=true"
        CallManager.log("Call audio relay streaming ($detail)")
        confirmIPhoneAudio()
    }

    /** Keep the Redmi usable until the paired iPhone actually reaches the UDP relay. */
    private fun confirmIPhoneAudio() {
        if (!streaming || session == 0 || peer == null || record == null || track == null) return
        if (!uplinkRouteIsTelephonyTx) return
        if (packetsIn < 45) return
        val lastPacket = lastUplinkTimestamp
        if (lastPacket == 0L || SystemClock.elapsedRealtime() - lastPacket > WATCHDOG_TIMEOUT_MS) return
        if (iPhoneAudioConfirmed) return
        if (redirectModeHeld) {
            if (!platformProbeLogged) {
                platformProbeLogged = true
                Log.i(TAG, "CALL_REDIRECT trial: iPhone PCM reaches Telephony Tx; microphone ownership requires physical verification")
            }
            return
        }
        if (uplinkUsesPlatformRedirection) {
            if (!platformProbeLogged) {
                platformProbeLogged = true
                Log.w(TAG, "Physical microphone isolation is unavailable on this Telephony Tx route; Redmi handset stays active")
            }
            return
        }
        val handsetQuieted = appContext?.let { ctx ->
            val audioManager = ctx.getSystemService(AudioManager::class.java)
            quietRedmiHandset(audioManager)
        } == true
        if (!handsetQuieted) {
            appContext?.let { ctx -> restoreRedmiHandset(ctx.getSystemService(AudioManager::class.java)) }
            notifyListeners(false, "Redmi audio could not be isolated")
            return
        }
        iPhoneAudioConfirmed = true
        watchdogFellBack = false
        CallManager.log("iPhone audio peer confirmed; handset audio reduced")
        notifyListeners(true, "iPhone audio connected")
    }

    private fun retryStart(reason: String) {
        startAttempts += 1
        if (startAttempts >= MAX_START_ATTEMPTS) {
            CallManager.log("Call audio relay could not start: $reason")
            notifyListeners(false, reason)
            return
        }
        handler.removeCallbacks(startAttempt)
        handler.postDelayed(startAttempt, START_RETRY_MS)
    }

    private fun disarmOnWorker(reason: String) {
        handler.removeCallbacks(startAttempt)
        handler.removeCallbacks(armTimeout)
        handler.removeCallbacks(watchdogRunnable)
        watchdogFellBack = false
        iPhoneAudioConfirmed = false
        uplinkRouteChecked = false
        uplinkRouteIsTelephonyTx = false
        expectedClientAddress = null
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
            notifyListeners(false, reason)
        }
    }

    private fun stopStreaming() {
        if (!streaming) return
        streaming = false
        uplinkWriterThread?.interrupt()
        uplinkWriterThread?.join(500)
        uplinkWriterThread = null
        uplinkQueue.clear()
        handler.removeCallbacks(watchdogRunnable)
        watchdogFellBack = false
        lastUplinkTimestamp = 0L
        streamingStartedAt = 0L
        iPhoneAudioConfirmed = false
        try {
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
            CallManager.log("Call audio relay packets: in=$packetsIn out=$packetsOut")
        } finally {
            appContext?.let { ctx ->
                val audioManager = ctx.getSystemService(AudioManager::class.java)
                releaseCallRedirectMode(audioManager)
                restoreRedmiHandset(audioManager)
            }
        }
    }

    private fun enterCallRedirectMode(audioManager: AudioManager): Boolean {
        return try {
            audioManager.mode = MODE_CALL_REDIRECT
            val selected = audioManager.mode == MODE_CALL_REDIRECT
            redirectModeHeld = selected
            Log.i(TAG, "CALL_REDIRECT requested: selected=$selected actualMode=${audioManager.mode}")
            if (!selected) audioManager.mode = AudioManager.MODE_NORMAL
            selected
        } catch (error: Exception) {
            Log.w(TAG, "CALL_REDIRECT unavailable; retaining Redmi call audio: ${describe(error)}")
            runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
            redirectModeHeld = false
            false
        }
    }

    private fun releaseCallRedirectMode(audioManager: AudioManager) {
        if (!redirectModeHeld) return
        redirectModeHeld = false
        runCatching {
            audioManager.mode = AudioManager.MODE_NORMAL
            Log.i(TAG, "CALL_REDIRECT released: actualMode=${audioManager.mode}")
        }.onFailure { Log.e(TAG, "Could not restore Telecom's audio mode", it) }
    }

    private fun receiveLoop(datagram: DatagramSocket) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        val stereoBuffer = ByteArray(UPLINK_FRAME_BYTES)
        var lastPcmSequence = -1
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

            val expectedAddr = expectedClientAddress
            if (expectedAddr != null && packet.address != expectedAddr) {
                Log.w(TAG, "Dropping packet from unauthorized peer address: ${packet.address} (expected: $expectedAddr)")
                continue
            }

            if (buffer[6] != 0.toByte() && buffer[6] != PAYLOAD_PCM16) continue
            // A keepalive establishes the return address. Only PCM proves that the
            // iPhone microphone engine is producing frames for the cellular uplink.
            peer = packet.socketAddress

            if (buffer[6] != PAYLOAD_PCM16) continue
            val payload = packet.length - HEADER_BYTES
            if (payload != FRAME_BYTES) continue
            if (!streaming) continue
            uplinkPacketsReceived += 1
            val sequence = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
            if (lastPcmSequence >= 0) {
                val gap = (sequence - lastPcmSequence - 1) and 0xFFFF
                if (gap in 1..1000) uplinkSequenceGaps += gap
            }
            lastPcmSequence = sequence

            // Keep the iPhone's 16 kHz samples at the Telephony Tx sink's supported rate;
            // duplicate mono into stereo without changing the 20 ms packet duration.
            var outIdx = 0
            for (inIdx in HEADER_BYTES until HEADER_BYTES + payload step 2) {
                val low = buffer[inIdx]
                val high = buffer[inIdx + 1]
                repeat(UPLINK_REPEAT) {
                    stereoBuffer[outIdx++] = low
                    stereoBuffer[outIdx++] = high
                    stereoBuffer[outIdx++] = low
                    stereoBuffer[outIdx++] = high
                }
            }

            if (!uplinkQueue.offer(stereoBuffer.copyOf())) {
                uplinkQueue.poll()
                uplinkPacketsDropped += 1
                uplinkQueue.offer(stereoBuffer.copyOf())
            }
        }
    }

    private fun uplinkWriteLoop(player: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val recentWrites = ArrayDeque<Long>()
        var levelFrames = 0
        var sumSquares = 0.0
        while (streaming && !Thread.currentThread().isInterrupted) {
            while (uplinkQueue.size > 8) {
                uplinkQueue.poll() ?: break
                uplinkPacketsDropped += 1
            }
            val frame = try {
                uplinkQueue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            val writeStartedAt = SystemClock.elapsedRealtime()
            var offset = 0
            while (streaming && offset < frame.size &&
                SystemClock.elapsedRealtime() - writeStartedAt < 250) {
                val written = player.write(frame, offset, frame.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                if (written < 0) {
                    Log.w(TAG, "Telephony Tx write failed: $written")
                    break
                }
                if (written == 0) SystemClock.sleep(2) else offset += written
            }
            val writeMs = SystemClock.elapsedRealtime() - writeStartedAt
            uplinkWriteTimeMs += writeMs
            if (writeMs > uplinkWriteMaxMs) uplinkWriteMaxMs = writeMs
            if (offset != frame.size) {
                uplinkPacketsDropped += 1
                continue
            }
            if (!uplinkRouteChecked) {
                val routed = player.routedDevice
                if (routed != null) {
                    uplinkRouteIsTelephonyTx = routed.type == AudioDeviceInfo.TYPE_TELEPHONY
                    uplinkRouteChecked = true
                    Log.i(TAG, "CURRENT_TELEPHONY_ROUTE type=${routed.type} id=${routed.id} telephonyTx=$uplinkRouteIsTelephonyTx")
                    if (!uplinkRouteIsTelephonyTx) {
                        handler.post { disarmOnWorker("uplink is not routed to Telephony Tx") }
                        continue
                    }
                }
            }
            if (!uplinkRouteIsTelephonyTx) continue
            val now = SystemClock.elapsedRealtime()
            lastUplinkTimestamp = now
            packetsIn += 1
            for (index in frame.indices step 4 * UPLINK_REPEAT) {
                val sample = ((frame[index + 1].toInt() shl 8) or (frame[index].toInt() and 0xFF)).toShort().toDouble()
                sumSquares += sample * sample
            }
            levelFrames += 1
            if (levelFrames == 250) {
                val rms = sqrt(sumSquares / (levelFrames * FRAME_SAMPLES))
                Log.i(TAG, "REDMI_TELEPHONY_TX_RMS=${"%.1f".format(rms)} REDMI_UPLINK_RECEIVED_PACKETS=$uplinkPacketsReceived REDMI_TELEPHONY_TX_WRITE_COUNT=$packetsIn IPHONE_MEDIA_OWNER=$iPhoneAudioConfirmed")
                levelFrames = 0
                sumSquares = 0.0
            }
            recentWrites.addLast(now)
            while (recentWrites.isNotEmpty() && now - recentWrites.first > 1000) {
                recentWrites.removeFirst()
            }
            if (!iPhoneAudioConfirmed && recentWrites.size >= 45) {
                val expected = session
                handler.post { if (session == expected) confirmIPhoneAudio() }
            }
        }
    }

    private fun sendLoop(recorder: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ByteArray(HEADER_BYTES + FRAME_BYTES)
        var sequence = 0
        var levelFrames = 0
        var sumSquares = 0.0
        var previousReceived = 0L
        var previousWritten = 0L
        var previousWriteMs = 0L
        var previousSequenceGaps = 0L
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
                val received = uplinkPacketsReceived
                val written = packetsIn
                val writeMs = uplinkWriteTimeMs
                val gaps = uplinkSequenceGaps
                Log.i(TAG, "REDMI_DOWNLINK_CAPTURE_RMS=${"%.1f".format(rms)} in=$written out=$packetsOut " +
                    "UPLINK_5S received=${received - previousReceived} written=${written - previousWritten} " +
                    "writeMs=${writeMs - previousWriteMs} maxWriteMs=$uplinkWriteMaxMs " +
                    "dropped=$uplinkPacketsDropped sequenceGaps=${gaps - previousSequenceGaps}")
                previousReceived = received
                previousWritten = written
                previousWriteMs = writeMs
                previousSequenceGaps = gaps
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

    private fun pcmFormat(channelMask: Int, sampleRate: Int = SAMPLE_RATE) = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
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
        // The system call-redirection track sustains 50 packets/s at the Telephony Tx sink's
        // 16 kHz rate. The independent vendor in-call-music output repeatedly stalled on
        // this device, so it is not safe to select for a live call.
        try {
            val method = AudioManager::class.java.getMethod(
                "getCallUplinkInjectionAudioTrack", AudioFormat::class.java
            )
            val player = method.invoke(audioManager, pcmFormat(AudioFormat.CHANNEL_OUT_STEREO, UPLINK_SAMPLE_RATE)) as AudioTrack
            if (player.state == AudioTrack.STATE_INITIALIZED) {
                player.setVolume(1.0f)
                uplinkUsesPlatformRedirection = true
                Log.i(TAG, "Platform call uplink injection track created: sampleRate=${player.sampleRate}, bufferFrames=${player.bufferSizeInFrames}")
                return player
            }
            player.release()
        } catch (error: Exception) {
            Log.w(TAG, "Platform call uplink injection unavailable: ${describe(error)}")
        }
        uplinkUsesPlatformRedirection = false
        return null
    }

    fun quietRedmiHandset(audioManager: AudioManager): Boolean {
        GatewayInCallService.setAudioRoute(CallAudioState.ROUTE_EARPIECE)
        runCatching {
            if (savedMicrophoneMuted == null) savedMicrophoneMuted = audioManager.isMicrophoneMute
            audioManager.isMicrophoneMute = true
            Log.i(TAG, "Redmi microphone mute requested; actual=${audioManager.isMicrophoneMute}")
        }.onFailure { Log.w(TAG, "Could not mute local microphone", it) }
        runCatching {
            if (savedVoiceVolume < 0) {
                savedVoiceVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                savedVoiceMuted = audioManager.isStreamMute(AudioManager.STREAM_VOICE_CALL)
                savedMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            val mediaMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val mediaTarget = maxOf(savedMediaVolume, (mediaMax * 0.8f).toInt().coerceAtLeast(1))
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mediaTarget, 0)
            val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioManager.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
            } else {
                1
            }
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, minVol, 0)
            audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
            Log.i(TAG, "Redmi local downlink muted=${audioManager.isStreamMute(AudioManager.STREAM_VOICE_CALL)}, mediaVolume=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}, physicalMicMuted=${audioManager.isMicrophoneMute}")
        }.onFailure { Log.w(TAG, "Could not isolate local call audio", it) }
        return audioManager.isMicrophoneMute &&
            audioManager.isStreamMute(AudioManager.STREAM_VOICE_CALL) &&
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
    }

    fun restoreRedmiHandset(audioManager: AudioManager?) {
        if (audioManager == null) return
        if (savedMicrophoneMuted == null && savedVoiceVolume < 0 && savedMediaVolume < 0) return
        savedMicrophoneMuted?.let { wasMuted ->
            runCatching { audioManager.isMicrophoneMute = wasMuted }
            savedMicrophoneMuted = null
        }
        runCatching {
            if (savedVoiceMuted == false) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
            }
            if (savedVoiceVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceVolume, 0)
            }
            if (savedMediaVolume >= 0) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMediaVolume, 0)
            Log.i(TAG, "Redmi handset restored: voiceMuted=${audioManager.isStreamMute(AudioManager.STREAM_VOICE_CALL)}, micMuted=${audioManager.isMicrophoneMute}")
        }
        savedVoiceVolume = -1
        savedVoiceMuted = null
        savedMediaVolume = -1
    }

    fun transferAudioToRedmi() {
        handler.post {
            disarmOnWorker("Manual switch to Redmi")
        }
    }

    fun transferAudioToIPhone() {
        handler.post {
            // The authenticated client requests a fresh session. Keep local audio until
            // its first UDP packet arrives; do not mute on a mere button press.
            if (isLiveCallState(CallManager.currentCallState)) {
                notifyListeners(false, "request_iphone_audio")
            }
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
