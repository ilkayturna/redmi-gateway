package com.ilkay.redmigateway.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.ilkay.redmigateway.security.BridgeCrypto
import com.ilkay.redmigateway.service.GatewayService
import com.ilkay.redmigateway.telecom.CallManager
import java.lang.reflect.InvocationTargetException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos

/**
 * Moves the Redmi's cellular call audio to the paired iPhone and back.
 *
 * Why this exact audio path (measured on this Redmi Note 8, Android 16 community ROM,
 * Qualcomm SM6125 HAL; see docs/AUDIO_ARCHITECTURE.md before changing anything here):
 *
 *  - Downlink (caller -> iPhone): AudioManager#getCallDownlinkExtractionAudioRecord. The HAL
 *    serves it from the in-call record downlink tap, which only works while the call stays on
 *    its normal handset path.
 *  - Uplink (iPhone -> caller): a media AudioTrack explicitly routed to Telephony Tx during a
 *    call. AudioPolicyManager gives it the INCALL_MUSIC output, which the HAL mixes into the
 *    voice uplink without re-routing the call. The call-uplink-injection system API is NOT
 *    used: it opens the afe-proxy output, and the next routing event moves the whole call onto
 *    the proxy device, where the downlink tap reads only zeros.
 *  - Redmi isolation: the Qualcomm "device_mute" parameters mute the handset microphone device
 *    and the earpiece device. AudioManager#setMicrophoneMute is not used: it can mute the whole
 *    uplink stream, including the iPhone audio.
 *
 * The iPhone becomes the audio owner only after its microphone frames arrive continuously, the
 * uplink track drains in real time and the downlink capture delivers frames. Any later failure
 * returns the call to the Redmi's own microphone and earpiece without ending it.
 *
 * UDP packets on port 8791 carry 20 ms of 16 kHz mono PCM16 (640 bytes) in both directions.
 * Protocol 2 sessions (sealed):
 *   [0..3] session id  [4..7] packet counter (uint32 BE, per direction and session)  [8] type
 *   [9] flags: iPhone -> Redmi bit 0 = the iPhone is receiving downlink frames; otherwise 0
 *   [10..] AES-256-GCM(payload) || tag, nonce = "UPLK"|"DNLK" + counter + session, AAD = header
 * With sealed sessions the iPhone owns the call only while audio is confirmed in both
 * directions: the Redmi sees iPhone frames, and the iPhone's flag says it hears the Redmi.
 * Protocol 1 sessions (iPhone builds up to 1.0 (33)):
 *   [0..3] session id  [4..5] sequence  [6] type  [7] 0  [8..] payload in clear
 * Type 0 is a keepalive, 1 is PCM. Sealed packets must arrive with increasing counters.
 */
object CallAudioRelay : CallManager.CallListener {
    const val PORT = 8791
    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 320

    enum class Owner { REDMI, PREPARING_IPHONE, IPHONE }

    interface Listener {
        /** [reason] is a short machine-readable code; UI text is chosen by the listener. */
        fun onAudioOwnerChanged(owner: Owner, reason: String)
    }

    private const val TAG = "CallAudioRelay"
    private const val FRAME_BYTES = FRAME_SAMPLES * 2
    private const val HEADER_BYTES = 8
    private const val SEALED_HEADER_BYTES = 10
    private val UPLINK_TAG = "UPLK".toByteArray(Charsets.US_ASCII)
    private val DOWNLINK_TAG = "DNLK".toByteArray(Charsets.US_ASCII)
    private const val PAYLOAD_KEEPALIVE: Byte = 0
    private const val PAYLOAD_PCM16: Byte = 1
    /**
     * A PCM packet that also repeats the previous frame: payload is [previous 640][current 640].
     * The iPhone only sends it when [redundancyCapability] advertises it, and the copy is dropped
     * unless the packet it belongs to was actually lost.
     */
    private const val PAYLOAD_PCM16_REDUNDANT: Byte = 2
    /** Frames of reserve the copy needs while redundancy is on: it must arrive before playback. */
    private const val REDUNDANCY_MIN_DEPTH_FRAMES = 3
    private const val UPLINK_RATE = 48000
    private const val UPLINK_FRAME_BYTES = FRAME_SAMPLES * 3 * 2 * 2 // 48 kHz stereo 16-bit
    private const val UPLINK_BUFFER_FRAMES = 6 // ~120 ms AudioTrack buffer (6 * 20 ms frames)
    private const val FRAME_MS = 20L
    // The writer keeps only this cushion inside the track. The track's own buffer stays at six
    // frames of headroom, but a full buffer is latency: a burst of network frames used to be pushed
    // into the track as fast as it arrived (measured maxWriteMs=71, i.e. the loop was blocked on a
    // full track), which added up to the whole 120 ms to the uplink and starved the loop.
    private const val UPLINK_PRIME_FRAMES = 4 // ~80 ms of device cushion, filled once at cutover
    private const val RAMP_SAMPLES = 32
    private val RAMP_UP = FloatArray(RAMP_SAMPLES) { i ->
        (0.5 * (1.0 - cos(PI * i / RAMP_SAMPLES))).toFloat()
    }

    private const val ARM_TIMEOUT_MS = 90_000L
    private const val PREPARE_TIMEOUT_MS = 8_000L
    // The iPhone answers and starts sending within a second; a shorter window hands the audio over
    // fast ("the sound jumps to the iPhone at once") while still checking both directions. Both
    // paths must still deliver this much continuous audio at the rate above before the Redmi's own
    // microphone and earpiece are muted.
    private const val VERIFY_WINDOW_MS = 300L
    private const val VERIFY_TIMEOUT_MS = 2_500L
    private const val MONITOR_INTERVAL_MS = 150L
    private const val IPHONE_SILENCE_LIMIT_MS = 1800L
    private const val MIN_FRAMES_PER_SECOND = 38
    /**
     * The gate reads the rate over its own short window instead of over the whole arm. The old
     * measure divided every frame of the arm by the whole arm's length, so a slow start poisoned it
     * for good: on 16.10.2026 23:28 the iPhone's engine restarted twice during setup and ran at 27
     * frames a second (the gate wants 38), the arm gave up at 8.02 s, and the audio only moved when
     * the user pressed the button by hand - at which point the same engine ran at 49/s and the
     * cutover took 243 ms. The window's origin is written when the first frame of the arm arrives,
     * so it has no "zero means unset" state to get stuck in (AGENTS 6.6).
     */
    private const val GATE_RATE_WINDOW_MS = 1_000L
    /**
     * While the iPhone is still sending frames, the arm keeps waiting this long before giving up.
     * The 8 s cap stays for the case that matters: nothing is arriving any more.
     */
    private const val PREPARE_GIVE_UP_MS = 30_000L
    // The iPhone streams within a second of answering. Waiting for a full one second window to
    // close delayed the cutover by up to 500 ms, so a partial window is read as a rate as soon as
    // it holds this much speech. The threshold above and the freshness rule are unchanged.
    private const val ARRIVAL_WINDOW_MIN_MS = 240L
    private const val ARRIVAL_WINDOW_MIN_FRAMES = 12
    /** Shortest gap between two event driven gate evaluations. */
    private const val GATE_KICK_MS = 60L
    /** How long a "take the audio" press stays valid while the iPhone has no control link. */
    private const val AUDIO_REQUEST_GRACE_MS = 30_000L

    private val lastValidPcm = ByteArray(FRAME_BYTES)
    private val plcBuf = ByteArray(FRAME_BYTES)
    // Reused packet scratch, one owner thread each. Sealed layout: 10 byte header,
    // 12 byte nonce, 640 byte payload, 16 byte tag.
    private val rxHeader = ByteArray(SEALED_HEADER_BYTES)
    private val rxSealedPcm = ByteArray(BridgeCrypto.NONCE_BYTES + FRAME_BYTES + BridgeCrypto.TAG_BYTES)
    private val rxSealedPcmRedundant = ByteArray(BridgeCrypto.NONCE_BYTES + 2 * FRAME_BYTES + BridgeCrypto.TAG_BYTES)
    private val rxSealedKeepalive = ByteArray(BridgeCrypto.NONCE_BYTES + BridgeCrypto.TAG_BYTES)
    private val txHeader = ByteArray(SEALED_HEADER_BYTES)
    private val txNonce = ByteArray(BridgeCrypto.NONCE_BYTES)
    private var plcConsecutiveFrames = 0
    private var hasValidPcmHistory = false

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    private val random = SecureRandom()
    private val worker = HandlerThread("CallAudioRelay", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(worker.looper)

    private var appContext: Context? = null
    @Volatile var owner: Owner = Owner.REDMI
        private set
    @Volatile var lastReason: String = "idle"
        private set
    @Volatile private var session = 0
    @Volatile private var mediaKey: ByteArray? = null
    @Volatile private var lastRxCounter = 0L
    /** Counter of the last packet that carried PCM, so a redundant copy is only used when new. */
    @Volatile private var lastPcmCounter = 0L
    // Downlink packet counter for the whole session: a GCM nonce must never repeat under one key,
    // so it is not reset when media restarts inside the same session.
    private val txCounter = AtomicLong()
    private val rejectedPackets = AtomicInteger()
    @Volatile private var expectedClientAddress: InetAddress? = null
    @Volatile private var peer: SocketAddress? = null
    @Volatile private var socket: DatagramSocket? = null
    /** Local address the audio socket is bound to: the one the iPhone's control link uses. */
    @Volatile private var boundAddress: InetAddress? = null
    private var receiveThread: Thread? = null

    // Media endpoints; only non-null between cutover and release.
    @Volatile private var downlink: AudioRecord? = null
    @Volatile private var uplink: AudioTrack? = null
    @Volatile private var mediaRunning = false
    private var writerThread: Thread? = null
    private var senderThread: Thread? = null
    private val uplinkQueue = UplinkJitterBuffer()
    private val upsampler = Upsampler3x()
    @Volatile private var isolationApplied = false
    private var listeningToCalls = false
    private var cutoverStartedAt = 0L
    private var preparingSince = 0L
    // A session armed right after dial exists before Telecom creates the call.
    private var callSeenLive = false
    private var badWindows = 0
    private var windowFresh = false

    // Health counters (written by audio threads, read by the monitor).
    @Volatile private var lastIPhonePcmAt = 0L
    // Cutover timeline, all measured from the arm and the cutover moment: this is what tells the
    // iPhone's own startup apart from the gate that waits for its frames.
    @Volatile private var armedAt = 0L
    @Volatile private var firstPcmAt = 0L
    /**
     * Origin and frame count of the gate's short window (see [GATE_RATE_WINDOW_MS]). Written when
     * the first frame of an arm arrives, never left at a "zero means unset" state that a condition
     * would then have to work around.
     */
    private var gateRateWindowAt = 0L
    private var gateRateWindowFrames = 0
    @Volatile private var firstCutoverWriteAt = 0L
    @Volatile private var firstCutoverReadAt = 0L
    /** When the caller's path last delivered a frame: the cutover needs both paths live now. */
    @Volatile private var lastCutoverReadAt = 0L
    /** True once the one-shot decision is queued for the moment both spans are long enough. */
    @Volatile private var verificationScheduled = false
    /** Rate the gate accepted, for the start line in the diagnostics log. */
    @Volatile private var lastGateRate = 0
    /** Wait, frames and rate as they were the instant the gate opened, not at the cutover. */
    @Volatile private var gateMsAtStart = -1L
    @Volatile private var gateFramesAtStart = -1
    /** Last condition that kept the gate closed, and when it was logged, for the wake-up trace. */
    private var lastGateBlocker = ""
    private var lastGateLogAt = 0L
    /** Last event driven gate evaluation, so a fast stream does not flood the handler. */
    @Volatile private var lastGateKickAt = 0L
    /** A "take the audio" press that landed while no client was authenticated. */
    @Volatile private var pendingAudioRequestAt = 0L
    // Sealed sessions only: last time the iPhone reported that it is receiving downlink.
    @Volatile private var lastIPhoneHearingAt = 0L
    private const val FLAG_IPHONE_HEARING = 0x01
    private const val IPHONE_DEAF_LIMIT_MS = 2000L

    /**
     * The iPhone reaches this Redmi over the internet (Tailscale, or any non-local address), for
     * example Redmi at home and iPhone at work. Mobile and VPN paths stall for longer than home
     * Wi-Fi, and falling back to a Redmi nobody is holding would lose the conversation, so the
     * limits are wider there. Local links keep the tighter, verified limits.
     */
    @Volatile private var remoteLink = false
    private val silenceLimitMs get() = if (remoteLink) 3_000L else IPHONE_SILENCE_LIMIT_MS
    private val deafLimitMs get() = if (remoteLink) 5_000L else IPHONE_DEAF_LIMIT_MS
    private val stallWindows get() = if (remoteLink) 4 else 2

    private fun isRemoteAddress(address: InetAddress?): Boolean {
        if (address == null || address.isLoopbackAddress || address.isLinkLocalAddress) return false
        val bytes = address.address
        if (address is java.net.Inet4Address) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 100 && second in 64..127) return true // Tailscale and other CGNAT-range VPNs
            return !address.isSiteLocalAddress
        }
        // Tailscale IPv6 (fd7a:115c:a1e0::/48); other unique-local addresses are this network.
        if (bytes[0] == 0xfd.toByte() && bytes[1] == 0x7a.toByte()) return true
        if ((bytes[0].toInt() and 0xfe) == 0xfc) return false
        // A global IPv6 address in one of this phone's /64 networks is local.
        val prefixes = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet6Address>().map { it.address.copyOf(8).toList() }
        }.getOrDefault(emptyList())
        return bytes.copyOf(8).toList() !in prefixes
    }
    private val iphoneFrames = AtomicInteger()
    /** Every PCM frame since the arm: the handover gate must not be diluted by the window roll. */
    private val pcmSinceArm = AtomicInteger()
    private val uplinkWrites = AtomicInteger()
    private val uplinkPcmWrites = AtomicInteger()
    private val insertedSilenceWrites = AtomicInteger()
    private val partialUplinkWrites = AtomicInteger()
    private val downlinkReads = AtomicInteger()
    // Counters since the cutover began. The per second window above is reset on every roll, so it
    // cannot measure the first partial window; these two make the handover decision at
    // VERIFY_WINDOW_MS instead of at the next one second boundary.
    private val cutoverWrites = AtomicInteger()
    private val cutoverReads = AtomicInteger()
    private var lastHealthLogAt = 0L
    @Volatile private var maxWriteMs = 0L
    /** Same measure over one health interval: tells a one-off cutover spike from real blocking. */
    @Volatile private var windowMaxWriteMs = 0L
    private var windowIPhone = 0
    private var windowWrites = 0
    private var windowPcmWrites = 0
    private var windowSilenceWrites = 0
    private var windowPartialWrites = 0
    private var windowReads = 0
    private var windowStartedAt = 0L

    val isIPhoneOwner: Boolean get() = owner == Owner.IPHONE
    val isStreaming: Boolean get() = mediaRunning

    /**
     * Off until the receiver is proven in the field: while it is off the gateway advertises 0 and
     * the iPhone keeps sending one frame per packet. Once on, it is offered to local links only
     * (hotspot, home Wi-Fi) because the copy doubles the audio bitrate and a mobile data link must
     * not pay for it - the user's data rule.
     */
    @Volatile var redundancyEnabled = false

    /** 1 when this link may carry a repeated frame, 0 otherwise. */
    fun redundancyCapability(): Int = if (redundancyEnabled && !remoteLink) 1 else 0

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Snapshot for Diagnostics; no audio content. */
    fun diagnostics(): Map<String, Any> {
        val queue = uplinkQueue.snapshot()
        return mapOf(
            "owner" to owner.name,
            "reason" to lastReason,
            "session" to (session != 0),
            "mediaRunning" to mediaRunning,
            "isolation" to isolationApplied,
            "iphoneFramesPerSecond" to windowIPhone,
            "uplinkFramesPerSecond" to windowWrites,
            "uplinkPcmFramesPerSecond" to windowPcmWrites,
            "insertedSilenceFramesPerSecond" to windowSilenceWrites,
            "partialUplinkWritesPerSecond" to windowPartialWrites,
            "downlinkFramesPerSecond" to windowReads,
            "droppedFrames" to queue.dropped,
            "expiredFrames" to queue.expired,
            "maxAuthenticatedArrivalGapMs" to queue.maxAuthenticatedArrivalGapMs,
            "maxAuthenticatedArrivalBurstFrames" to queue.maxAuthenticatedArrivalBurstFrames,
            "sealed" to (mediaKey != null),
            "rejectedPackets" to rejectedPackets.get(),
            "underruns" to queue.underruns,
            "jitterTargetFrames" to queue.targetFrames,
            "jitterTargetMs" to queue.targetFrames * FRAME_MS.toInt(),
            "redundancy" to redundancyCapability(),
            "pendingAudioRequestMs" to (if (pendingAudioRequestAt == 0L) -1L
                else SystemClock.elapsedRealtime() - pendingAudioRequestAt),
            "gateRate" to lastGateRate,
            "gateMs" to gateMsAtStart,
            "gateFrames" to gateFramesAtStart,
            "gateBlocker" to lastGateBlocker,
            "firstPcmMs" to (if (firstPcmAt == 0L) -1L else firstPcmAt - armedAt),
            "startMediaMs" to (if (cutoverStartedAt == 0L) -1L else cutoverStartedAt - armedAt),
            "maxWriteMs" to maxWriteMs
        )
    }

    /**
     * Opens (or reuses) a relay session for the authenticated iPhone at [clientAddress] and
     * returns its id. The Redmi keeps the call audio until the iPhone's frames prove it ready.
     */
    /**
     * [localAddress] is the Redmi address the iPhone reached for its control link. The audio
     * socket binds to it, so replies leave from the address the iPhone sends to: a Redmi with
     * Wi-Fi and a hotspot (or mobile data) at once would otherwise answer from another address,
     * and the iPhone drops those packets.
     */
    fun arm(context: Context, clientAddress: InetAddress?, sessionMediaKey: ByteArray? = null,
            localAddress: InetAddress? = null): Int {
        synchronized(this) {
            appContext = context.applicationContext
            expectedClientAddress = clientAddress
            remoteLink = isRemoteAddress(clientAddress)
            if (remoteLink) Log.i(TAG, "iPhone is on a remote link; wider audio limits")
            if (session == 0 || !sameKey(mediaKey, sessionMediaKey)) {
                if (session != 0) {
                    // A different key means a new authenticated connection; start a fresh session.
                    handler.post { releaseMediaAndKeepSession("iphone_reconnected") }
                }
                var id = 0
                while (id == 0) id = random.nextInt()
                session = id
                mediaKey = sessionMediaKey
                lastRxCounter = 0L
                lastPcmCounter = 0L
                txCounter.set(0L)
                rejectedPackets.set(0)
                peer = null
                lastIPhonePcmAt = 0L
                openSocket(localAddress)
            }
        }
        handler.post {
            if (!listeningToCalls) {
                listeningToCalls = true
                CallManager.addListener(this)
            }
            handler.removeCallbacks(armTimeout)
            handler.postDelayed(armTimeout, ARM_TIMEOUT_MS)
            if (isLiveCallState(CallManager.currentCallState)) callSeenLive = true
            if (owner == Owner.REDMI) {
                preparingSince = SystemClock.elapsedRealtime()
                // The iPhone answered the request: stop replaying the kept press.
                pendingAudioRequestAt = 0L
                setOwner(Owner.PREPARING_IPHONE, "waiting_for_iphone_audio")
            }
            armedAt = SystemClock.elapsedRealtime()
            firstPcmAt = 0L
            pcmSinceArm.set(0)
            gateRateWindowAt = 0L
            gateRateWindowFrames = 0
            scheduleMonitor()
        }
        CallManager.log("Call audio session armed")
        return session
    }

    /** Ends the session and returns audio to the Redmi. Safe to call at any time. */
    fun disarm(reason: String) {
        handler.post { releaseAll(reason) }
    }

    /**
     * The control socket died. That is **not** the same thing as the iPhone going away: the call
     * audio runs on its own UDP socket and keeps arriving while the iOS app reconnects.
     *
     * Measured on 16.10.2026 23:21, a 25 second call: six control-socket deaths, and every one of
     * them pulled the audio back to the Redmi and re-armed it 0.4-2 s later. The user heard the
     * call "go to the iPhone, come back, go again" while the media never actually stopped.
     *
     * So the session is kept while the iPhone's frames are still arriving. Nothing is lost by
     * waiting: the monitor already takes the call back the moment the media itself stops
     * ([silenceLimitMs] without a frame, or a stalled path over two windows), which is the honest
     * signal that the iPhone is really gone.
     */
    fun onControlLinkLost(reason: String) {
        handler.post {
            val now = SystemClock.elapsedRealtime()
            val iphoneStillSending = mediaRunning && owner == Owner.IPHONE &&
                lastIPhonePcmAt != 0L && now - lastIPhonePcmAt < silenceLimitMs
            if (!iphoneStillSending) {
                releaseAll(reason)
                return@post
            }
            Log.i(TAG, "Control link lost ($reason) but the iPhone audio is still flowing; keeping the call on the iPhone")
            CallManager.log("Control link lost; iPhone audio still flowing, keeping it")
        }
    }

    /** The user chose to continue on the Redmi. The call itself is untouched. */
    fun transferAudioToRedmi() {
        handler.post { releaseAll("manual_redmi") }
    }

    /**
     * Asks the iPhone to take the call audio; it answers with audio_start. The request travels the
     * existing way: every listener (the control socket is one) gets the state with this reason.
     *
     * A backgrounded iPhone closes its socket on purpose, so the press usually lands while no
     * client is authenticated - the user then saw nothing happen at all. The request is kept for
     * [AUDIO_REQUEST_GRACE_MS] and replayed as soon as a client authenticates again.
     */
    fun requestIPhoneAudio() {
        handler.post {
            if (isLiveCallState(CallManager.currentCallState) && owner == Owner.REDMI) {
                // A sleeping iPhone may refuse the pickup with its screen off, and the region beacon
                // is what brings the app forward; ask for it while this request is in flight.
                com.ilkay.redmigateway.ble.BleServerManager.wakeForAudioTransfer()
                // Keep the request even when a client looks authenticated right now: that socket may
                // die before the answer arrives (measured on 16.10.2026: sessions lived 60-190 ms
                // while the iPhone reconnected every ~20 s). A press made into a dying socket used
                // to be lost with nothing to retry it, and the user saw the audio stay on the Redmi.
                pendingAudioRequestAt = SystemClock.elapsedRealtime()
                if (GatewayService.authenticatedClientCount() == 0) {
                    Log.w(TAG, "No iPhone on the control link; keeping the audio request for $AUDIO_REQUEST_GRACE_MS ms")
                }
                for (l in listeners) runCatching { l.onAudioOwnerChanged(Owner.REDMI, "request_iphone_audio") }
            }
        }
    }

    /**
     * A client just authenticated: replay a recent "take the audio" press, so the press the user
     * made while the iPhone was asleep still moves the call once it comes back.
     *
     * The request stays armed for the whole grace window and is replayed on **every** new
     * authentication inside it, not just the first: the iPhone often reconnects several times
     * before its audio answer lands, and clearing the marker after one replay threw the user's
     * press away. The marker is dropped when the request is fulfilled (the relay arms and takes
     * ownership) or when the window expires.
     */
    fun onControlClientAvailable() {
        handler.post {
            val askedAt = pendingAudioRequestAt
            if (askedAt == 0L) return@post
            if (SystemClock.elapsedRealtime() - askedAt > AUDIO_REQUEST_GRACE_MS) {
                pendingAudioRequestAt = 0L
                return@post
            }
            if (!isLiveCallState(CallManager.currentCallState) || owner != Owner.REDMI) {
                // The call ended, or somebody already armed the audio: nothing left to ask for.
                pendingAudioRequestAt = 0L
                return@post
            }
            Log.i(TAG, "iPhone is back on the control link; sending the kept audio request")
            CallManager.log("iPhone reconnected; sending the kept audio request")
            for (l in listeners) runCatching { l.onAudioOwnerChanged(Owner.REDMI, "request_iphone_audio") }
        }
    }

    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        handler.post {
            if (session == 0) return@post
            if (state == "disconnected" || state == "idle") {
                // Listener registration reports the current state; before the armed call
                // exists that is "idle" and must not close the session.
                if (callSeenLive && !CallManager.isCallLive()) releaseAll("call_ended")
            } else if (isLiveCallState(state)) {
                if (!callSeenLive) {
                    callSeenLive = true
                    // The readiness timeout counts from the moment there is call audio to move.
                    if (owner == Owner.PREPARING_IPHONE && !mediaRunning) preparingSince = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    override fun onLogMessage(message: String) = Unit

    private fun isLiveCallState(state: String) =
        state == "dialing" || state == "connecting" || state == "active"

    private val armTimeout = Runnable {
        if (session != 0 && owner != Owner.IPHONE) releaseAll("no_call_audio_timeout")
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (session == 0) return
            tick()
            if (session != 0) handler.postDelayed(this, MONITOR_INTERVAL_MS)
        }
    }

    /**
     * One extra decision the moment the iPhone's frames have proven themselves, instead of waiting
     * for the next 150 ms monitor tick. It never re-arms itself: the monitor keeps the cadence.
     */
    private val decideNow = Runnable { if (session != 0) tick() }

    /**
     * Decides the cutover the moment both directions have run for a full verification window,
     * instead of waiting for the next monitor tick (a 400 ms window measured 668 ms that way).
     */
    private fun scheduleVerificationDecision() {
        if (firstCutoverWriteAt == 0L || firstCutoverReadAt == 0L || verificationScheduled) return
        verificationScheduled = true
        handler.removeCallbacks(decideNow)
        handler.postDelayed(decideNow, VERIFY_WINDOW_MS + 20)
    }

    private fun scheduleMonitor() {
        handler.removeCallbacks(monitor)
        resetWindow()
        handler.postDelayed(monitor, MONITOR_INTERVAL_MS)
    }

    private fun resetWindow() {
        windowStartedAt = SystemClock.elapsedRealtime()
        iphoneFrames.set(0); uplinkWrites.set(0); downlinkReads.set(0)
        uplinkPcmWrites.set(0); insertedSilenceWrites.set(0); partialUplinkWrites.set(0)
    }

    /** Runs on the worker thread every 250 ms while a session exists. */
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        windowFresh = false
        if (now - windowStartedAt >= 1000L) {
            windowIPhone = iphoneFrames.getAndSet(0)
            windowWrites = uplinkWrites.getAndSet(0)
            windowPcmWrites = uplinkPcmWrites.getAndSet(0)
            windowSilenceWrites = insertedSilenceWrites.getAndSet(0)
            windowPartialWrites = partialUplinkWrites.getAndSet(0)
            windowReads = downlinkReads.getAndSet(0)
            windowStartedAt = now
            windowFresh = true
        }
        if (mediaRunning && now - lastHealthLogAt >= 5000L) {
            lastHealthLogAt = now
            val queue = uplinkQueue.snapshot()
            // The session maximum never recovers from one spike, so the window maximum is what
            // tells a one-off cutover hiccup (the HAL re-routes when the Redmi is muted) from a
            // writer that is genuinely blocking.
            val windowWriteMs = windowMaxWriteMs
            windowMaxWriteMs = 0
            val health = "RELAY_HEALTH owner=$owner iphone=$windowIPhone/s uplink=$windowWrites/s " +
                "pcm=$windowPcmWrites/s silence=$windowSilenceWrites/s partial=$windowPartialWrites/s " +
                "downlink=$windowReads/s queued=${queue.queued} targetFrames=${queue.targetFrames} " +
                "dropped=${queue.dropped} " +
                "expired=${queue.expired} underruns=${queue.underruns} maxWriteMs=$maxWriteMs " +
                "windowMaxWriteMs=$windowWriteMs " +
                "maxAuthenticatedArrivalGapMs=${queue.maxAuthenticatedArrivalGapMs} " +
                "maxAuthenticatedArrivalBurstFrames=${queue.maxAuthenticatedArrivalBurstFrames}"
            Log.i(TAG, health)
            com.ilkay.redmigateway.journal.DiagnosticsLog.record("Audio", health)
        }
        val live = isLiveCallState(CallManager.currentCallState)
        val iphoneFresh = lastIPhonePcmAt != 0L && now - lastIPhonePcmAt < silenceLimitMs
        // Protocol 1 iPhones cannot report what they receive; for them this stays true.
        val iphoneHearing = mediaKey == null ||
            (lastIPhoneHearingAt != 0L && now - lastIPhoneHearingAt < silenceLimitMs)
        when (owner) {
            Owner.REDMI -> Unit
            Owner.PREPARING_IPHONE -> {
                // "ringing" after an answer is the rollback path: there is no audio to carry, and
                // the AudioRecord/AudioTrack keep counting silence frames, so an unchecked
                // verification would hand a ringing call to the iPhone (a ghost cutover). The
                // session stays armed so a later answer can arm the same relay again.
                if (CallManager.currentCallState == "ringing") {
                    if (mediaRunning) releaseMediaAndKeepSession("call_ringing_again")
                    return
                }
                if (!mediaRunning) {
                    // The gate reads the average rate since the iPhone's first frame in this arm.
                    //
                    // A windowed rate was tried here and it took the audio away completely. The
                    // sampling origin started at zero and the elapsed time was forced to zero
                    // while the origin was zero, so the branch that would have set the origin was
                    // never reached: the elapsed time stayed zero, the rate stayed zero, and the
                    // gate never opened on any call. Every call ended eight seconds later with
                    // iphone_audio_not_ready, and the log said nothing about why. The average has
                    // no such state to get stuck in, and it is what the last working handover ran
                    // on, at 10:33 on the sixteenth with the iPhone ramping from 14 frames a
                    // second to 52.
                    val framesNow = pcmSinceArm.get()
                    val streamMs = if (firstPcmAt == 0L) 0L else now - firstPcmAt
                    val lifetimeRate = if (streamMs > 0L) (framesNow * 1000L / streamMs).toInt() else 0
                    val windowRate = gateWindowRate(framesNow, now)
                    // The window is the fast path; the arm's average stays as the fallback so a
                    // window that somehow cannot fill can never hold the gate shut.
                    val recentRate = if (windowRate > 0) windowRate else lifetimeRate
                    val blocker = CutoverDecision.startMediaBlocker(
                        live = live,
                        iphoneFresh = iphoneFresh,
                        streamMs = streamMs,
                        streamFrames = framesNow,
                        recentRate = recentRate,
                        minStreamMs = ARRIVAL_WINDOW_MIN_MS,
                        minFrames = ARRIVAL_WINDOW_MIN_FRAMES,
                        minFramesPerSecond = MIN_FRAMES_PER_SECOND
                    )
                    if (blocker == CutoverDecision.READY) {
                        // Both are read at this instant: reading the frame count later, at the
                        // cutover, made a healthy 300 ms gate look like 1520 ms of piled up audio.
                        lastGateRate = recentRate
                        gateMsAtStart = streamMs
                        gateFramesAtStart = framesNow
                        Log.i(TAG, "GATE_OPEN wait=${streamMs}ms frames=$framesNow rate=$recentRate/s")
                        startMedia()
                    } else {
                        // Name the condition that waits. Guessing between "the iPhone ramps up
                        // slowly" and "the gate is too strict" is how a healthy stream got blamed
                        // for a slow start that was never measured.
                        if (blocker != lastGateBlocker || now - lastGateLogAt >= 500L) {
                            lastGateBlocker = blocker
                            lastGateLogAt = now
                            Log.i(TAG, "GATE_WAIT reason=$blocker wait=${streamMs}ms frames=$framesNow " +
                                "rate=$recentRate/s window=$windowRate/s avg=$lifetimeRate/s " +
                                "live=$live fresh=$iphoneFresh")
                        }
                        if (live) {
                            val waitedMs = now - preparingSince
                            // Give up only when there is nothing left to wait for: nothing arriving
                            // any more, or an arm preparing far past any plausible ramp-up. Giving up
                            // at 8 s while frames were still arriving is exactly what made the user
                            // press the button by hand on 16.10.2026 23:28.
                            val nothingArriving = !iphoneFresh && waitedMs > PREPARE_TIMEOUT_MS
                            if (nothingArriving || waitedMs > PREPARE_GIVE_UP_MS) {
                                Log.w(TAG, "Giving up the handover after ${waitedMs}ms: " +
                                    "fresh=$iphoneFresh frames=$framesNow rate=$recentRate/s")
                                releaseMediaAndKeepSession("iphone_audio_not_ready")
                            }
                        }
                    }
                    return
                }
                // Media running: the handover needs VERIFY_WINDOW_MS of audio in both directions.
                // Each direction is timed from its own first frame, so the AudioTrack/AudioRecord
                // startup is not counted as a stall; the decision lands at 400-550 ms after the
                // slower path has actually been running for that long.
                val elapsed = now - cutoverStartedAt
                if (elapsed >= VERIFY_WINDOW_MS) {
                    val writeSpan = if (firstCutoverWriteAt == 0L) 0L else now - firstCutoverWriteAt
                    val readSpan = if (firstCutoverReadAt == 0L) 0L else now - firstCutoverReadAt
                    // Both paths must have delivered a full window before their rate means anything.
                    val writesRate = if (writeSpan >= VERIFY_WINDOW_MS)
                        (cutoverWrites.get() * 1000L / writeSpan).toInt() else 0
                    val readsRate = if (readSpan >= VERIFY_WINDOW_MS)
                        (cutoverReads.get() * 1000L / readSpan).toInt() else 0
                    // A call that is not live (on hold, or being torn down) never takes the audio,
                    // and both directions have to be flowing right now: a link that delivered a
                    // burst and died keeps the counters up with concealed frames, and muting the
                    // Redmi for it costs seconds of one-way silence before the fallback.
                    val uplinkAgeMs = if (lastIPhonePcmAt == 0L) Long.MAX_VALUE else now - lastIPhonePcmAt
                    val downlinkAgeMs = if (lastCutoverReadAt == 0L) Long.MAX_VALUE else now - lastCutoverReadAt
                    val healthy = CutoverDecision.canHandOver(
                        live = live,
                        writesRate = writesRate,
                        readsRate = readsRate,
                        minFramesPerSecond = MIN_FRAMES_PER_SECOND,
                        uplinkAgeMs = uplinkAgeMs,
                        downlinkAgeMs = downlinkAgeMs,
                        iphoneFresh = iphoneFresh,
                        iphoneHearing = iphoneHearing
                    )
                    if (healthy) {
                        if (applyIsolation()) {
                            setOwner(Owner.IPHONE, "iphone_confirmed")
                            CallManager.log("Call audio moved to iPhone (uplink=$writesRate/s downlink=$readsRate/s " +
                                "settle=${elapsed}ms firstWrite=${firstCutoverWriteAt - cutoverStartedAt}ms " +
                                "firstRead=${firstCutoverReadAt - cutoverStartedAt}ms " +
                                "firstPcm=${if (firstPcmAt == 0L) -1 else firstPcmAt - armedAt}ms " +
                                "startMedia=${cutoverStartedAt - armedAt}ms " +
                                // Read when the gate opened: reading the frame count here, at the
                                // cutover, made a healthy gate look like 1520 ms of piled up audio.
                                "gateMs=$gateMsAtStart gateFrames=$gateFramesAtStart " +
                                "gateRate=$lastGateRate/s)")
                            Log.i(TAG, "Cutover freshness: pcmAge=${uplinkAgeMs}ms readAge=${downlinkAgeMs}ms")
                        } else {
                            releaseMediaAndKeepSession("isolation_failed")
                        }
                    } else if (elapsed > VERIFY_TIMEOUT_MS) {
                        val reason = when {
                            !live -> "call_not_live"
                            uplinkAgeMs > CutoverDecision.FRESHNESS_MS -> "iphone_audio_stalled"
                            downlinkAgeMs > CutoverDecision.FRESHNESS_MS -> "downlink_stalled"
                            writesRate < MIN_FRAMES_PER_SECOND -> "uplink_path_unavailable"
                            readsRate < MIN_FRAMES_PER_SECOND -> "downlink_unavailable"
                            !iphoneFresh -> "iphone_audio_lost"
                            else -> "iphone_not_receiving"
                        }
                        Log.w(TAG, "Cutover verification failed: $reason writes=$writesRate/s reads=$readsRate/s iphone=$windowIPhone")
                        releaseMediaAndKeepSession(reason)
                    }
                }
            }
            Owner.IPHONE -> {
                if (!live) return
                // Losing the iPhone microphone is decided per tick; rate dips must persist for
                // two consecutive 1 s windows, because call setup briefly slows both paths.
                if (!iphoneFresh) {
                    fallBack("iphone_audio_lost")
                    return
                }
                if (mediaKey != null && now - lastIPhoneHearingAt > deafLimitMs) {
                    fallBack("iphone_not_receiving")
                    return
                }
                if (!windowFresh || now - cutoverStartedAt < 2000) return
                val slow = when {
                    windowWrites < MIN_FRAMES_PER_SECOND -> "uplink_stalled"
                    windowReads < MIN_FRAMES_PER_SECOND -> "downlink_stalled"
                    else -> null
                }
                badWindows = if (slow == null) 0 else badWindows + 1
                if (slow != null && badWindows >= stallWindows) fallBack(slow)
            }
        }
    }

    private fun fallBack(reason: String) {
        Log.w(TAG, "Returning call audio to Redmi: $reason (iphone=$windowIPhone writes=$windowWrites reads=$windowReads)")
        releaseMediaAndKeepSession(reason)
    }

    /** The call whose audio the iPhone carried at some point; read when the call ends. */
    @Volatile var carriedCallId: String? = null
        private set

    private fun setOwner(next: Owner, reason: String) {
        if (next == Owner.IPHONE) carriedCallId = CallManager.currentCallId
        val changed = owner != next || lastReason != reason
        owner = next
        lastReason = reason
        GatewayService.setCallAudioForeground(next != Owner.REDMI)
        if (changed) {
            Log.i(TAG, "AUDIO_OWNER=$next reason=$reason")
            com.ilkay.redmigateway.journal.DiagnosticsLog.record("Audio", "AUDIO_OWNER=$next reason=$reason")
            for (l in listeners) runCatching { l.onAudioOwnerChanged(next, reason) }
        }
    }

    private fun openSocket(localAddress: InetAddress?) {
        val current = socket
        if (current != null && !current.isClosed && boundAddress == localAddress) return
        current?.close()
        receiveThread?.join(300)
        val datagram = try {
            newAudioSocket(if (localAddress != null) InetSocketAddress(localAddress, PORT) else InetSocketAddress(PORT))
        } catch (error: Exception) {
            Log.w(TAG, "Could not bind audio to the control link address: ${error.javaClass.simpleName}")
            newAudioSocket(InetSocketAddress(PORT))
        }
        boundAddress = if (datagram.localAddress?.isAnyLocalAddress == true) null else localAddress
        Log.i(TAG, "Audio socket bound to ${if (boundAddress == null) "all addresses" else "the control link address"}")
        socket = datagram
        receiveThread = Thread({ receiveLoop(datagram) }, "CallAudio-Receive").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun newAudioSocket(address: InetSocketAddress) = DatagramSocket(null).apply {
        reuseAddress = true
        receiveBufferSize = 64 * 1024
        sendBufferSize = 64 * 1024
        // DSCP EF (46): Wi-Fi access points and hotspots queue these as voice (WMM AC_VO).
        runCatching { trafficClass = 0xB8 }
        bind(address)
    }

    private fun receiveLoop(datagram: DatagramSocket) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        // Scratch owned by this thread only: the packet path used to build five arrays per 20 ms
        // frame, and a collection during a call lands as a late AudioTrack write.
        val header = rxHeader
        val sealedPcm = rxSealedPcm
        val sealedPcmRedundant = rxSealedPcmRedundant
        val sealedKeepalive = rxSealedKeepalive
        while (!datagram.isClosed) {
            try {
                packet.setLength(buffer.size)
                datagram.receive(packet)
            } catch (error: Exception) {
                if (!datagram.isClosed) Log.w(TAG, "Receive failed: ${error.javaClass.simpleName}")
                continue
            }
            val expected = session
            if (expected == 0 || packet.length < HEADER_BYTES) continue
            val packetSession = readInt(buffer, 0)
            if (packetSession != expected) continue
            val allowed = expectedClientAddress
            if (allowed != null && packet.address != allowed) continue
            val key = mediaKey
            val type: Byte
            val frame: ByteArray?
            val repeatedFrame: ByteArray?
            if (key != null) {
                if (packet.length < SEALED_HEADER_BYTES + BridgeCrypto.TAG_BYTES) continue
                val counter = readCounter(buffer)
                if (counter <= lastRxCounter) continue
                type = buffer[8]
                val ciphertext = packet.length - SEALED_HEADER_BYTES
                val sealed = when (ciphertext) {
                    FRAME_BYTES + BridgeCrypto.TAG_BYTES -> sealedPcm
                    2 * FRAME_BYTES + BridgeCrypto.TAG_BYTES -> sealedPcmRedundant
                    BridgeCrypto.TAG_BYTES -> sealedKeepalive
                    else -> ByteArray(BridgeCrypto.NONCE_BYTES + ciphertext)
                }
                writeNonce(sealed, UPLINK_TAG, counter, expected)
                System.arraycopy(buffer, SEALED_HEADER_BYTES, sealed, BridgeCrypto.NONCE_BYTES, ciphertext)
                System.arraycopy(buffer, 0, header, 0, SEALED_HEADER_BYTES)
                val plain = BridgeCrypto.open(key, sealed, header)
                if (plain == null) {
                    rejectedPackets.incrementAndGet()
                    continue
                }
                lastRxCounter = counter
                if (buffer[9].toInt() and FLAG_IPHONE_HEARING != 0) lastIPhoneHearingAt = SystemClock.elapsedRealtime()
                when {
                    type == PAYLOAD_PCM16 && plain.size == FRAME_BYTES -> {
                        frame = plain
                        repeatedFrame = null
                        lastPcmCounter = counter
                    }
                    type == PAYLOAD_PCM16_REDUNDANT && plain.size == 2 * FRAME_BYTES -> {
                        // The copy is the frame the previous packet carried. It is only new when
                        // that packet never arrived; a keepalive carries no frame, so the sender
                        // omits the copy after one and the counter gap alone means a lost frame.
                        repeatedFrame = if (RedundantFrames.copyIsNew(counter, lastPcmCounter))
                            plain.copyOfRange(0, FRAME_BYTES) else null
                        frame = plain.copyOfRange(FRAME_BYTES, 2 * FRAME_BYTES)
                        lastPcmCounter = counter
                    }
                    else -> {
                        frame = null
                        repeatedFrame = null
                    }
                }
            } else {
                type = buffer[6]
                frame = if (type == PAYLOAD_PCM16 && packet.length - HEADER_BYTES == FRAME_BYTES)
                    buffer.copyOfRange(HEADER_BYTES, HEADER_BYTES + FRAME_BYTES) else null
                repeatedFrame = null
            }
            if (type != PAYLOAD_KEEPALIVE && type != PAYLOAD_PCM16 && type != PAYLOAD_PCM16_REDUNDANT) continue
            peer = packet.socketAddress
            if (frame == null && repeatedFrame == null) continue
            val receivedAt = SystemClock.elapsedRealtime()
            acceptFrame(repeatedFrame, receivedAt, authenticated = key != null)
            acceptFrame(frame, receivedAt, authenticated = key != null)
        }
    }

    /**
     * Counts an arrived frame and queues it for playback. A null frame is a redundant copy whose
     * original already played, so it is not counted and not queued.
     */
    /**
     * Frames per second over the gate's own short window, refreshed once the window has filled.
     *
     * The origin is written when the first frame of an arm arrives, so a zero here can only mean
     * "no frame yet" - the trap that kept the previous windowed attempt from ever opening the gate
     * (AGENTS 6.6) is structurally impossible: this function never assigns the origin.
     */
    private fun gateWindowRate(framesNow: Int, now: Long): Int {
        val origin = gateRateWindowAt
        if (origin == 0L) return 0
        val elapsed = now - origin
        if (elapsed <= 0L) return 0
        val frames = framesNow - gateRateWindowFrames
        if (frames < 0) return 0
        val rate = (frames * 1000L / elapsed).toInt()
        if (elapsed >= GATE_RATE_WINDOW_MS) {
            gateRateWindowAt = now
            gateRateWindowFrames = framesNow
        }
        return rate
    }

    private fun acceptFrame(frame: ByteArray?, receivedAt: Long, authenticated: Boolean) {
        if (frame == null) return
        lastIPhonePcmAt = receivedAt
        if (firstPcmAt == 0L) {
            firstPcmAt = receivedAt
            // The gate's fast path measures from the first frame of this arm; see gateWindowRate.
            gateRateWindowAt = receivedAt
            gateRateWindowFrames = 0
        }
        iphoneFrames.incrementAndGet()
        val frames = pcmSinceArm.incrementAndGet()
        if (frames == ARRIVAL_WINDOW_MIN_FRAMES && owner == Owner.PREPARING_IPHONE && !mediaRunning) {
            // The reserve has just arrived: decide on this frame, not at the next monitor tick.
            handler.removeCallbacks(decideNow)
            handler.post(decideNow)
        }
        // The gate is also event driven: the frame that makes the recent rate healthy is the moment
        // the audio path may open, not the next 150 ms monitor tick. Throttled so a healthy stream
        // does not re-run the tick fifty times a second.
        if (owner == Owner.PREPARING_IPHONE && !mediaRunning &&
            receivedAt - lastGateKickAt >= GATE_KICK_MS
        ) {
            lastGateKickAt = receivedAt
            handler.removeCallbacks(decideNow)
            handler.post(decideNow)
        }
        if (!mediaRunning) return
        uplinkQueue.offer(frame, receivedAt, authenticated = authenticated)
    }

    /** Nonce layout shared with the iPhone: 4 byte direction tag, counter, session id (big endian). */
    private fun writeNonce(target: ByteArray, tag: ByteArray, counter: Long, sessionId: Int) {
        System.arraycopy(tag, 0, target, 0, 4)
        target[4] = (counter ushr 24).toByte()
        target[5] = (counter ushr 16).toByte()
        target[6] = (counter ushr 8).toByte()
        target[7] = counter.toByte()
        target[8] = (sessionId ushr 24).toByte()
        target[9] = (sessionId ushr 16).toByte()
        target[10] = (sessionId ushr 8).toByte()
        target[11] = sessionId.toByte()
    }

    /** Big endian, matching ByteBuffer with order BIG_ENDIAN. */
    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)

    private fun readCounter(bytes: ByteArray): Long = readInt(bytes, 4).toLong() and 0xFFFFFFFFL

    private fun sameKey(a: ByteArray?, b: ByteArray?): Boolean =
        if (a == null || b == null) a === b else a.contentEquals(b)

    private fun startMedia() {
        val context = appContext ?: return
        val audioManager = context.getSystemService(AudioManager::class.java)
        val record = openDownlink(audioManager)
        val track = openUplink(audioManager)
        if (record == null || track == null) {
            record?.release()
            track?.release()
            releaseMediaAndKeepSession(if (track == null) "uplink_path_unavailable" else "downlink_unavailable")
            return
        }
        downlink = record
        uplink = track
        lastIPhoneHearingAt = 0L
        plcConsecutiveFrames = 0
        hasValidPcmHistory = false
        uplinkQueue.reset(remoteLink, if (redundancyCapability() == 1) REDUNDANCY_MIN_DEPTH_FRAMES else 0)
        upsampler.reset()
        maxWriteMs = 0
        mediaRunning = true
        cutoverStartedAt = SystemClock.elapsedRealtime()
        // Only a real cutover clears these: [resetWindow] also runs when a session is re-armed, and
        // a duplicate audio_start during the verification window must not blank the measured rate.
        cutoverWrites.set(0)
        cutoverReads.set(0)
        firstCutoverWriteAt = 0L
        firstCutoverReadAt = 0L
        lastCutoverReadAt = 0L
        verificationScheduled = false
        badWindows = 0
        resetWindow()
        track.play()
        record.startRecording()
        writerThread = Thread({ writeLoop(track) }, "CallAudio-Uplink").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        senderThread = Thread({ sendLoop(record) }, "CallAudio-Downlink").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        CallManager.log("Call audio media started; verifying before handing over " +
            "(gate=${if (firstPcmAt == 0L) -1 else cutoverStartedAt - firstPcmAt}ms " +
            "frames=${pcmSinceArm.get()} rate=$lastGateRate/s)")
    }

    private fun writeLoop(track: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val out = ByteArray(UPLINK_FRAME_BYTES)
        plcConsecutiveFrames = 0
        hasValidPcmHistory = false
        // Absolute 20 ms deadlines keep this loop at the PCM rate instead of at the arrival rate.
        // A burst leaves the queue deeper instead of flooding the track, so the blocking write
        // returns at once, the uplink latency stays at (queue depth + one cushion) and a stalled
        // writer catches up by refilling the cushion instead of by sending audio early.
        var nextWriteAt = 0L
        var primeFramesRemaining = UPLINK_PRIME_FRAMES - 1

        while (mediaRunning && uplink === track) {
            // A missing frame becomes concealed audio/silence rather than a stall, and after an
            // underrun the bounded queue primes again before speech resumes.
            val frame = uplinkQueue.poll(SystemClock.elapsedRealtime())

            val playbackPcm: ByteArray
            if (frame != null && frame.size == FRAME_BYTES) {
                // If returning from full silence, smooth beginning with RAMP_UP to eliminate phase discontinuity clicking
                if (plcConsecutiveFrames >= 3) {
                    for (i in 0 until RAMP_SAMPLES) {
                        val offset = 2 * i
                        val lo = frame[offset].toInt() and 0xFF
                        val hi = frame[offset + 1].toInt()
                        val sample = ((hi shl 8) or lo).toShort()
                        val ramped = (sample * RAMP_UP[i]).toInt().coerceIn(-32768, 32767)
                        frame[offset] = (ramped and 0xFF).toByte()
                        frame[offset + 1] = ((ramped shr 8) and 0xFF).toByte()
                    }
                }
                System.arraycopy(frame, 0, lastValidPcm, 0, FRAME_BYTES)
                hasValidPcmHistory = true
                plcConsecutiveFrames = 0
                playbackPcm = frame
            } else {
                plcConsecutiveFrames++
                if (hasValidPcmHistory) {
                    when (plcConsecutiveFrames) {
                        1 -> {
                            // Frame 1: repeat lastValidPcm at 0.75f decay
                            for (i in 0 until FRAME_SAMPLES) {
                                val offset = 2 * i
                                val lo = lastValidPcm[offset].toInt() and 0xFF
                                val hi = lastValidPcm[offset + 1].toInt()
                                val sample = ((hi shl 8) or lo).toShort()
                                val decayed = (sample * 0.75f).toInt().coerceIn(-32768, 32767)
                                plcBuf[offset] = (decayed and 0xFF).toByte()
                                plcBuf[offset + 1] = ((decayed shr 8) and 0xFF).toByte()
                            }
                        }
                        2 -> {
                            // Frame 2: repeat lastValidPcm at 0.40f decay
                            for (i in 0 until FRAME_SAMPLES) {
                                val offset = 2 * i
                                val lo = lastValidPcm[offset].toInt() and 0xFF
                                val hi = lastValidPcm[offset + 1].toInt()
                                val sample = ((hi shl 8) or lo).toShort()
                                val decayed = (sample * 0.40f).toInt().coerceIn(-32768, 32767)
                                plcBuf[offset] = (decayed and 0xFF).toByte()
                                plcBuf[offset + 1] = ((decayed shr 8) and 0xFF).toByte()
                            }
                        }
                        3 -> {
                            // Frame 3: linear fadeout from 0.20f to 0 across the frame
                            for (i in 0 until FRAME_SAMPLES) {
                                val offset = 2 * i
                                val lo = lastValidPcm[offset].toInt() and 0xFF
                                val hi = lastValidPcm[offset + 1].toInt()
                                val sample = ((hi shl 8) or lo).toShort()
                                val factor = 0.20f * (1.0f - i.toFloat() / FRAME_SAMPLES)
                                val decayed = (sample * factor).toInt().coerceIn(-32768, 32767)
                                plcBuf[offset] = (decayed and 0xFF).toByte()
                                plcBuf[offset + 1] = ((decayed shr 8) and 0xFF).toByte()
                            }
                        }
                        else -> {
                            // Frame 4+: fill plcBuf with zero bytes (silence)
                            java.util.Arrays.fill(plcBuf, 0.toByte())
                        }
                    }
                } else {
                    java.util.Arrays.fill(plcBuf, 0.toByte())
                }
                playbackPcm = plcBuf
            }

            val bytes = upsampler.process(playbackPcm, 0, FRAME_SAMPLES, out)
            if (nextWriteAt != 0L) {
                if (primeFramesRemaining > 0) {
                    primeFramesRemaining--
                } else {
                    val wait = nextWriteAt - SystemClock.elapsedRealtime()
                    if (wait > 0) SystemClock.sleep(wait)
                }
            }
            val started = SystemClock.elapsedRealtime()
            val written = track.write(out, 0, bytes, AudioTrack.WRITE_BLOCKING)
            val finished = SystemClock.elapsedRealtime()
            nextWriteAt = if (nextWriteAt == 0L) started + FRAME_MS else nextWriteAt + FRAME_MS
            val ms = finished - started
            if (written < 0) {
                Log.w(TAG, "Uplink write failed: $written")
                SystemClock.sleep(FRAME_MS)
                nextWriteAt = SystemClock.elapsedRealtime() + FRAME_MS
                continue
            }
            if (ms > maxWriteMs) maxWriteMs = ms
            if (ms > windowMaxWriteMs) windowMaxWriteMs = ms
            if (written == bytes) {
                // Ownership still measures a working sink, while diagnostics distinguish real
                // received PCM (including naturally silent samples) from substituted silence.
                uplinkWrites.incrementAndGet()
                cutoverWrites.incrementAndGet()
                if (firstCutoverWriteAt == 0L) {
                    firstCutoverWriteAt = finished
                    scheduleVerificationDecision()
                }
                if (frame == null) insertedSilenceWrites.incrementAndGet() else uplinkPcmWrites.incrementAndGet()
            } else {
                // A positive short write did not deliver a full 20 ms frame.
                partialUplinkWrites.incrementAndGet()
            }
        }
    }

    private fun sendLoop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val pcm = ByteArray(FRAME_BYTES)
        // The extraction record can hand over a backlog (cutover, HAL re-route) in one burst.
        // Forwarding that burst unchanged filled the iPhone's bounded playback queue and dropped
        // the tail as an audible hole. Pace the downlink at the same 20 ms cadence the iPhone
        // consumes; a real stall starts a new phase instead of a catch-up burst.
        var nextSendAt = 0L
        while (mediaRunning && downlink === record) {
            val read = record.read(pcm, 0, FRAME_BYTES)
            if (read <= 0) {
                if (read < 0) SystemClock.sleep(10)
                continue
            }
            downlinkReads.incrementAndGet()
            cutoverReads.incrementAndGet()
            lastCutoverReadAt = SystemClock.elapsedRealtime()
            if (firstCutoverReadAt == 0L) {
                firstCutoverReadAt = lastCutoverReadAt
                scheduleVerificationDecision()
            }
            val target = peer ?: continue
            val datagram = socket ?: break
            val now = SystemClock.elapsedRealtime()
            if (nextSendAt == 0L || now - nextSendAt > 200L) nextSendAt = now
            val wait = nextSendAt - now
            if (wait > 0) SystemClock.sleep(wait)
            // Session, key and counter are taken together so a re-arm cannot mix them.
            val bytes = synchronized(this) { packDownlink(pcm, read) }
            try {
                datagram.send(DatagramPacket(bytes, bytes.size, target))
            } catch (error: Exception) {
                if (!datagram.isClosed) Log.w(TAG, "Downlink send failed: ${error.javaClass.simpleName}")
            }
            nextSendAt += FRAME_MS
        }
    }

    private fun packDownlink(pcm: ByteArray, read: Int): ByteArray {
        val sessionId = session
        val counter = txCounter.incrementAndGet()
        val key = mediaKey ?: return ByteBuffer.allocate(HEADER_BYTES + read).order(ByteOrder.BIG_ENDIAN)
            .putInt(sessionId).putShort(counter.toShort()).put(PAYLOAD_PCM16).put(0)
            .put(pcm, 0, read).array()
        val header = txHeader
        writeInt(header, 0, sessionId)
        writeInt(header, 4, counter.toInt())
        header[8] = PAYLOAD_PCM16
        header[9] = 0
        writeNonce(txNonce, DOWNLINK_TAG, counter, sessionId)
        // seal() only reads its plaintext, so a short read is the one case that needs a copy.
        val plaintext = if (read == pcm.size) pcm else pcm.copyOf(read)
        val sealed = BridgeCrypto.seal(key, plaintext, header, txNonce)
        return header + sealed.copyOfRange(BridgeCrypto.NONCE_BYTES, sealed.size)
    }

    private fun pcmFormat(rate: Int, channelMask: Int) = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate)
        .setChannelMask(channelMask)
        .build()

    private fun openDownlink(audioManager: AudioManager): AudioRecord? = try {
        val recorder = AudioManager::class.java
            .getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
            .invoke(audioManager, pcmFormat(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO)) as AudioRecord
        if (recorder.state == AudioRecord.STATE_INITIALIZED) recorder else {
            recorder.release()
            null
        }
    } catch (error: Exception) {
        Log.w(TAG, "Downlink extraction unavailable: ${describe(error)}")
        null
    }

    /** INCALL_MUSIC output: explicit Telephony Tx routing of a media track during a call. */
    private fun openUplink(audioManager: AudioManager): AudioTrack? {
        val telephonyTx = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        if (telephonyTx == null) {
            Log.w(TAG, "No Telephony Tx output device")
            return null
        }
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(pcmFormat(UPLINK_RATE, AudioFormat.CHANNEL_OUT_STEREO))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(UPLINK_FRAME_BYTES * UPLINK_BUFFER_FRAMES)
                .build()
            if (!track.setPreferredDevice(telephonyTx) || track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                null
            } else {
                track.setVolume(1.0f)
                track
            }
        } catch (error: Exception) {
            Log.w(TAG, "Uplink track unavailable: ${describe(error)}")
            null
        }
    }

    /** Mutes the Redmi's own microphone and earpiece devices, not the call streams. */
    private fun applyIsolation(): Boolean {
        val audioManager = appContext?.getSystemService(AudioManager::class.java) ?: return false
        return try {
            audioManager.setParameters("device_mute=true;direction=tx")
            audioManager.setParameters("device_mute=true;direction=rx")
            isolationApplied = true
            true
        } catch (error: Exception) {
            Log.w(TAG, "Could not isolate the Redmi handset: ${describe(error)}")
            clearIsolation()
            false
        }
    }

    private fun clearIsolation() {
        val audioManager = appContext?.getSystemService(AudioManager::class.java) ?: return
        runCatching { audioManager.setParameters("device_mute=false;direction=tx") }
        runCatching { audioManager.setParameters("device_mute=false;direction=rx") }
        isolationApplied = false
    }

    /** Hands the call back to the Redmi's microphone and earpiece; keeps the session for a retry. */
    private fun releaseMediaAndKeepSession(reason: String) {
        // Restore the Redmi first so the call is never silent on both devices.
        clearIsolation()
        stopMedia()
        setOwner(Owner.REDMI, reason)
    }

    private fun stopMedia() {
        if (!mediaRunning && downlink == null && uplink == null) return
        mediaRunning = false
        writerThread?.interrupt()
        writerThread?.join(300)
        writerThread = null
        downlink?.let { runCatching { it.stop() } }
        senderThread?.join(300)
        senderThread = null
        downlink?.release()
        downlink = null
        uplink?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
        uplink = null
        uplinkQueue.clear()
    }

    private fun releaseAll(reason: String) {
        handler.removeCallbacks(armTimeout)
        handler.removeCallbacks(monitor)
        val hadSession = session != 0
        callSeenLive = false
        releaseMediaAndKeepSession(reason)
        synchronized(this) {
            session = 0
            mediaKey = null
            peer = null
            expectedClientAddress = null
            socket?.close()
            socket = null
            boundAddress = null
        }
        receiveThread?.join(300)
        receiveThread = null
        if (listeningToCalls) {
            listeningToCalls = false
            CallManager.removeListener(this)
        }
        if (hadSession) CallManager.log("Call audio session closed: $reason")
    }

    private fun describe(error: Throwable): String {
        val cause = if (error is InvocationTargetException) error.targetException ?: error else error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }
}
