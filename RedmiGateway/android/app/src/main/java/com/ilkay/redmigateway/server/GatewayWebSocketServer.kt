package com.ilkay.redmigateway.server

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ilkay.redmigateway.BuildConfig
import com.ilkay.redmigateway.RedmiGatewayApp
import com.ilkay.redmigateway.audio.CallAudioLab
import com.ilkay.redmigateway.audio.CallAudioRelay
import com.ilkay.redmigateway.journal.CallJournal
import com.ilkay.redmigateway.journal.SmsJournal
import com.ilkay.redmigateway.security.BridgeCrypto
import com.ilkay.redmigateway.security.PairingStore
import com.ilkay.redmigateway.security.SecureChannel
import com.ilkay.redmigateway.sms.SmsReceiver
import com.ilkay.redmigateway.sms.SmsSender
import com.ilkay.redmigateway.sms.SmsStore
import android.telecom.Call
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.telecom.IncomingCallPresenter
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import com.ilkay.redmigateway.push.PushNotifier
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * The iPhone's control channel (protocol 2; see docs/PROTOCOL.md).
 *
 * hello (server nonce) -> pair | auth (HMAC proofs, never the secret) -> sealed frames.
 * Protocol 1 clients (iPhone builds up to 1.0 (33)) may still authenticate with the pairing code
 * in clear text, but only until an iPhone has completed a protocol 2 pairing.
 */
class GatewayWebSocketServer(
    private val context: Context,
    port: Int = 8787
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)), CallManager.CallListener,
    CallAudioRelay.Listener, CallJournal.Listener, SmsJournal.Listener, CallManager.WaitingListener {

    companion object {
        private const val TAG = "GatewayWSServer"
        const val PROTOCOL = 2
        const val MIN_PROTOCOL = 1
        private const val MAX_FAILURES = 5
        private const val LOCKOUT_MS = 60_000L

        @Volatile var current: GatewayWebSocketServer? = null
            private set

        fun requestContactResolution(number: String) {
            current?.requestContactResolution(number)
        }
    }

    interface ServerListener {
        fun onServerStarted(port: Int)
        fun onClientCountChanged(authenticatedCount: Int)
        fun onServerError(error: String)
    }

    private class Peer(val conn: WebSocket) {
        val serverNonce: ByteArray = BridgeCrypto.randomBytes(16)
        @Volatile var protocol = 0
        @Volatile var authenticated = false
        @Volatile var channel: SecureChannel? = null
        @Volatile var mediaKey: ByteArray? = null
        @Volatile var lastDialAt = 0L
        // Recorded so a disconnect can be explained rather than merely noticed. On 16.10.2026 the
        // real iPhone reconnected five times in forty six seconds and the log said only that it
        // had connected each time, so the cause could not be told apart: a network change, a
        // keepalive timeout, the iPhone going to the background and the server dropping it all
        // look identical without these two numbers.
        val connectedAt = SystemClock.elapsedRealtime()
        @Volatile var lastMessageAt = connectedAt
        @Volatile var authenticatedAt = 0L
    }

    private data class Failures(var count: Int, var lockedUntil: Long)

    var serverListener: ServerListener? = null
    private val peers = ConcurrentHashMap<WebSocket, Peer>()
    private val failures = ConcurrentHashMap<String, Failures>()
    @Volatile private var audioOwner: WebSocket? = null

    init {
        isReuseAddr = true
        isTcpNoDelay = true
    }

    override fun onStart() {
        current = this
        Log.i(TAG, "WebSocket server started on port $port")
        CallManager.addListener(this)
        CallManager.addWaitingListener(this)
        CallAudioRelay.addListener(this)
        CallJournal.get(context).addListener(this)
        SmsJournal.get(context).addListener(this)
        SmsReceiver.webSocketServer = this
        serverListener?.onServerStarted(port)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        val peer = Peer(conn)
        peers[conn] = peer
        Log.i(TAG, "WebSocket opened from ${conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"}")
        val hello = JSONObject()
            .put("type", "hello")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("protocol", PROTOCOL)
            .put("minProtocol", MIN_PROTOCOL)
            .put("gatewayId", RedmiGatewayApp.getOpaqueDeviceId())
            .put("nonce", BridgeCrypto.b64(peer.serverNonce))
            .put("paired", PairingStore.pairedClient() != null)
            .put("addresses", JSONArray(com.ilkay.redmigateway.ble.NetworkUtils.localAddresses()))
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("appBuild", BuildConfig.VERSION_CODE)
        sendPlain(conn, hello)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        val peer = peers.remove(conn)
        // Everything needed to tell the five-reconnects-in-forty-six-seconds case apart. `remote`
        // says whether the iPhone closed the socket or this side did, the code says why, the
        // session length separates a real drop from a reconnect on purpose, and the gap since the
        // last message says whether the link went quiet first or died mid-conversation.
        if (peer != null) {
            val sessionMs = SystemClock.elapsedRealtime() - peer.connectedAt
            val sinceLastMessageMs = SystemClock.elapsedRealtime() - peer.lastMessageAt
            Log.i(
                TAG,
                "WebSocket closed code=$code remote=$remote authenticated=${peer.authenticated} " +
                    "protocol=${peer.protocol} sessionMs=$sessionMs " +
                    "sinceLastMessageMs=$sinceLastMessageMs " +
                    "verdict=${classifyClose(peer.authenticated, code, remote, sinceLastMessageMs)} " +
                    "reason=${reason?.take(120) ?: "none"}"
            )
        } else {
            Log.i(TAG, "WebSocket closed code=$code remote=$remote peer=unknown")
        }
        if (audioOwner === conn) {
            audioOwner = null
            CallAudioRelay.disarm("iphone_disconnected")
        }
        if (peer?.authenticated == true) serverListener?.onClientCountChanged(authenticatedCount())
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w(TAG, "WebSocket error: ${ex.javaClass.simpleName}")
        if (conn == null) serverListener?.onServerError(ex.javaClass.simpleName)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val peer = peers[conn] ?: return
        peer.lastMessageAt = SystemClock.elapsedRealtime()
        if (message.length > 16_384) return
        val json = try {
            JSONObject(message)
        } catch (_: Exception) {
            return
        }
        if (!peer.authenticated) {
            when (json.optString("type")) {
                "pair" -> handlePair(peer, json)
                "auth" -> handleAuth(peer, json)
                else -> sendPlain(conn, JSONObject().put("type", "error").put("code", "unauthorized"))
            }
            return
        }
        val channel = peer.channel
        val inner = if (channel != null) {
            if (json.optString("t") != "s") return
            val opened = channel.open(json.optLong("c", -1), json.optString("d")) ?: run {
                Log.w(TAG, "Dropped a sealed frame that failed authentication")
                return
            }
            try { JSONObject(opened) } catch (_: Exception) { return }
        } else {
            json
        }
        try {
            dispatch(peer, inner)
        } catch (error: Exception) {
            Log.e(TAG, "Command failed: ${error.javaClass.simpleName}", error)
            send(peer, JSONObject().put("type", "error").put("code", "internal_error"))
        }
    }

    // ---- Pairing and authentication ----

    private fun remoteIp(peer: Peer) = peer.conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"

    private fun lockedOut(peer: Peer): Boolean {
        val record = failures[remoteIp(peer)] ?: return false
        return record.lockedUntil > System.currentTimeMillis()
    }

    private fun recordFailure(peer: Peer) {
        failures.compute(remoteIp(peer)) { _, existing ->
            (existing ?: Failures(0, 0)).apply {
                count += 1
                if (count >= MAX_FAILURES) {
                    lockedUntil = System.currentTimeMillis() + LOCKOUT_MS
                    count = 0
                }
            }
        }
    }

    private fun handlePair(peer: Peer, json: JSONObject) {
        val conn = peer.conn
        val result = JSONObject().put("type", "pair_result")
        if (lockedOut(peer)) {
            sendPlain(conn, result.put("success", false).put("code", "locked_out"))
            return
        }
        val clientNonce = BridgeCrypto.unb64(json.optString("nonce"))
        val proof = BridgeCrypto.unb64(json.optString("proof"))
        val clientId = json.optString("clientId").take(64)
        if (clientNonce == null || clientNonce.size != 16 || proof == null || clientId.isBlank()) {
            sendPlain(conn, result.put("success", false).put("code", "malformed"))
            return
        }
        val code = PairingStore.pairingCode()
        val expected = BridgeCrypto.hmac(code.toByteArray(Charsets.UTF_8), "pair".toByteArray(), peer.serverNonce, clientNonce)
        if (!BridgeCrypto.constantTimeEquals(expected, proof)) {
            recordFailure(peer)
            CallManager.log("Pairing rejected: wrong code")
            sendPlain(conn, result.put("success", false).put("code", "wrong_code"))
            return
        }
        failures.remove(remoteIp(peer))
        val key = BridgeCrypto.randomBytes(BridgeCrypto.KEY_BYTES)
        val wrapKey = BridgeCrypto.pbkdf2(code, peer.serverNonce + clientNonce)
        val sealedKey = BridgeCrypto.seal(wrapKey, key, "pair".toByteArray())
        PairingStore.completePairing(clientId, json.optString("clientName", "iPhone"), key)
        CallManager.log("iPhone paired")
        // Protocol 1 sessions authenticated with the old code end now; keyed auth is required.
        for (other in peers.values) {
            if (other !== peer && other.authenticated && other.protocol < 2) other.conn.close(1008, "paired_elsewhere")
        }
        sendPlain(conn, result.put("success", true)
            .put("key", BridgeCrypto.b64(sealedKey))
            .put("gatewayId", RedmiGatewayApp.getOpaqueDeviceId())
            .put("gatewayProof", BridgeCrypto.b64(BridgeCrypto.hmac(key, "gw".toByteArray(), clientNonce, peer.serverNonce))))
    }

    private fun handleAuth(peer: Peer, json: JSONObject) {
        val conn = peer.conn
        val result = JSONObject().put("type", "auth_result")
        if (lockedOut(peer)) {
            sendPlain(conn, result.put("success", false).put("code", "locked_out")
                .put("message", "Çok fazla hatalı deneme. Bir dakika sonra tekrar dene."))
            conn.close(1008, "locked_out")
            return
        }
        val clientProtocol = json.optInt("protocol", 1)
        if (clientProtocol >= 2) {
            val paired = PairingStore.pairedClient()
            val clientNonce = BridgeCrypto.unb64(json.optString("nonce"))
            val proof = BridgeCrypto.unb64(json.optString("proof"))
            val clientId = json.optString("clientId")
            if (paired == null || paired.id != clientId) {
                sendPlain(conn, result.put("success", false).put("code", "not_paired"))
                return
            }
            if (clientNonce == null || clientNonce.size != 16 || proof == null) {
                sendPlain(conn, result.put("success", false).put("code", "malformed"))
                return
            }
            val expected = BridgeCrypto.hmac(paired.key, "auth".toByteArray(), peer.serverNonce, clientNonce)
            if (!BridgeCrypto.constantTimeEquals(expected, proof)) {
                recordFailure(peer)
                sendPlain(conn, result.put("success", false).put("code", "bad_proof"))
                return
            }
            failures.remove(remoteIp(peer))
            val sessionKey = BridgeCrypto.hmac(paired.key, "session".toByteArray(), peer.serverNonce, clientNonce)
            peer.protocol = PROTOCOL
            peer.mediaKey = BridgeCrypto.derive(sessionKey, "media")
            sendPlain(conn, result.put("success", true).put("protocol", PROTOCOL)
                .put("gatewayProof", BridgeCrypto.b64(BridgeCrypto.hmac(paired.key, "gw".toByteArray(), clientNonce, peer.serverNonce))))
            // Every later frame in both directions is sealed.
            peer.channel = SecureChannel(BridgeCrypto.derive(sessionKey, "ws"), isGateway = true)
            peer.authenticated = true
            onAuthenticated(peer)
            return
        }
        // Protocol 1: the pairing code in clear text, only while no keyed pairing exists.
        if (!PairingStore.legacyAuthAllowed) {
            sendPlain(conn, result.put("success", false).put("code", "update_required")
                .put("message", "Bu iPhone uygulaması eski. TestFlight'tan güncelleyip yeniden eşleştir."))
            return
        }
        if (json.optString("token").equals(PairingStore.pairingCode(), ignoreCase = true)) {
            failures.remove(remoteIp(peer))
            peer.protocol = 1
            peer.authenticated = true
            sendPlain(conn, result.put("success", true).put("message", "Authenticated successfully"))
            onAuthenticated(peer)
        } else {
            recordFailure(peer)
            sendPlain(conn, result.put("success", false).put("message", "Geçersiz eşleştirme kodu"))
        }
    }

    private fun onAuthenticated(peer: Peer) {
        peer.authenticatedAt = SystemClock.elapsedRealtime()
        CallManager.log("iPhone connected (protocol ${peer.protocol})")
        serverListener?.onClientCountChanged(authenticatedCount())
        val state = CallManager.currentCallState
        if (state != "idle") send(peer, callEvent(CallManager.currentCallId ?: "", state, CallManager.currentPhoneNumber))
        if (peer.protocol >= 2) sendAudioState(peer, CallAudioRelay.owner, CallAudioRelay.lastReason)
        val waiting = CallManager.waitingCallId
        if (peer.protocol >= 2 && waiting != null) send(peer, waitingMessage(waiting, CallManager.waitingNumber))
    }

    // ---- Commands ----

    private fun dispatch(peer: Peer, json: JSONObject) {
        val conn = peer.conn
        when (val type = json.optString("type")) {
            "answer" -> sendAck(peer, "answer", CallManager.answer(json.optString("callId").ifBlank { null }))
            "reject" -> sendAck(peer, "reject", CallManager.reject(json.optString("callId").ifBlank { null }))
            "hangup" -> sendAck(peer, "hangup", CallManager.hangup(json.optString("callId").ifBlank { null }))
            "hold_call" -> {
                val callId = json.optString("callId").ifBlank { null }
                val hold = json.optBoolean("hold", true)
                val call = if (callId != null) CallManager.getCall(callId) else CallManager.activeCall
                val ok = if (call != null) {
                    runCatching {
                        if (hold) call.hold() else call.unhold()
                        true
                    }.onFailure { Log.w(TAG, "hold_call failed", it) }.getOrDefault(false)
                } else false
                sendAck(peer, "hold_call", ok)
            }
            "merge_calls" -> sendAck(peer, "merge_calls", CallManager.merge())
            "swap_calls" -> sendAck(peer, "swap_calls", CallManager.swap())
            "dial" -> {
                val ok = CallManager.dial(context, json.optString("number"))
                if (ok) peer.lastDialAt = SystemClock.elapsedRealtime()
                sendAck(peer, "dial", ok)
            }
            "dtmf" -> {
                val digit = json.optString("digit")
                sendAck(peer, "dtmf", digit.length == 1 && CallManager.playDtmf(digit[0]))
            }
            "get_state" -> send(peer, stateMessage())
            "ringing_ack" -> {
                IncomingCallPresenter.onIPhonePresented(json.optString("callId"))
                sendAck(peer, "ringing_ack", true)
            }
            "audio_start" -> startAudio(peer)
            "audio_stop" -> {
                if (audioOwner === conn) {
                    audioOwner = null
                    CallAudioRelay.disarm("iphone_stopped")
                }
                sendAck(peer, "audio_stop", true)
            }
            "audio_to_redmi" -> {
                if (audioOwner === conn) CallAudioRelay.transferAudioToRedmi()
                sendAck(peer, "audio_to_redmi", audioOwner === conn)
            }
            "send_sms", "sms_send" -> sendSms(peer, json)
            "sync" -> sync(peer, json.optLong("callsAfter", 0), json.optLong("smsAfter", 0))
            "get_sms_history" -> legacySmsHistory(peer, json.optLong("afterId", 0))
            "diagnostics" -> send(peer, DiagnosticsReport.build(context).put("type", "diagnostics_result"))
            "push_token" -> {
                // For calls and messages while the iPhone is away (Apple push); protocol 2 only.
                if (peer.protocol >= 2) com.ilkay.redmigateway.push.ApnsSender.setDeviceToken(json.optString("token"))
            }
            "push_alert_token" -> {
                // The app's ordinary notification token, for SMS and missed calls through Apple.
                if (peer.protocol >= 2) com.ilkay.redmigateway.push.ApnsSender.setAlertToken(json.optString("token"))
            }
            "unpair" -> {
                if (peer.protocol >= 2) {
                    PairingStore.forget()
                    CallManager.log("iPhone removed its pairing")
                    sendAck(peer, "unpair", true)
                    conn.close(1000, "unpaired")
                }
            }
            "lab" -> {
                // Measurement hooks for scripts/lab; off unless enabled over adb (LabMode), and
                // they only act while no relay session runs.
                if (!com.ilkay.redmigateway.audio.LabMode.enabled) {
                    send(peer, JSONObject().put("type", "error").put("code", "lab_disabled"))
                    return
                }
                val op = json.optString("op", "status")
                val result = if (op == "stats") {
                    JSONObject().put("op", op).put("status",
                        CallAudioLab.snapshot(context.getSystemService(android.media.AudioManager::class.java)))
                } else CallAudioLab.handle(context, op)
                send(peer, result.put("type", "lab_result"))
            }
            "contact_info", "contact_resolved" -> {
                val number = json.optString("number")
                val name = json.optString("name").ifBlank { null }
                if (number.isNotBlank() && name != null) {
                    ContactResolver.put(number, name)
                }
            }
            else -> send(peer, JSONObject().put("type", "error").put("code", "unknown_command").put("command", type))
        }
    }

    private fun startAudio(peer: Peer) {
        val conn = peer.conn
        // Telecom creates an outgoing call asynchronously, so a request that follows this
        // client's own dial by a few seconds is accepted and waits for the call.
        val dialedRecently = peer.lastDialAt > 0 && SystemClock.elapsedRealtime() - peer.lastDialAt < 10_000L
        if (CallManager.currentCallState !in setOf("ringing", "dialing", "connecting", "active") && !dialedRecently) {
            send(peer, JSONObject().put("type", "error").put("code", "no_call").put("message", "No current cellular call"))
            return
        }
        if (audioOwner != null && audioOwner !== conn && audioOwner?.isOpen == true) {
            send(peer, JSONObject().put("type", "error").put("code", "audio_busy").put("message", "Another client owns call audio"))
            return
        }
        val session = CallAudioRelay.arm(context, conn.remoteSocketAddress?.address, peer.mediaKey,
            conn.localSocketAddress?.address)
        audioOwner = conn
        send(peer, JSONObject()
            .put("type", "audio_ready")
            .put("session", session.toLong() and 0xFFFFFFFFL)
            .put("port", CallAudioRelay.PORT)
            .put("sampleRate", CallAudioRelay.SAMPLE_RATE)
            .put("frameSamples", CallAudioRelay.FRAME_SAMPLES)
            .put("sealed", peer.mediaKey != null)
            // 1 asks the iPhone to repeat the previous frame in the next packet; the receiver drops
            // the copy unless its original was lost. Off until it is proven, and never on a remote
            // link: the copy doubles the audio bitrate and a mobile data link must not pay for it.
            .put("redundancy", CallAudioRelay.redundancyCapability()))
    }

    private fun sendSms(peer: Peer, json: JSONObject) {
        val recipient = json.optString("recipient")
        val requestId = json.optString("requestId")
        val reply = { success: Boolean, error: String? ->
            val result = JSONObject()
                .put("type", "sms_send_result")
                .put("recipient", recipient)
                .put("success", success)
            if (requestId.isNotBlank()) result.put("requestId", requestId)
            if (error != null) result.put("error", error)
            if (peer.conn.isOpen) send(peer, result)
        }
        val immediate = SmsSender.send(context, recipient, json.optString("message")) { ok, error -> reply(ok, error) }
        if (immediate != null) reply(false, immediate)
    }

    private fun sync(peer: Peer, callsAfter: Long, smsAfter: Long) {
        val (calls, moreCalls) = CallJournal.get(context).readAfter(callsAfter)
        send(peer, JSONObject()
            .put("type", "sync_calls")
            .put("items", JSONArray().apply { calls.forEach { put(it.toJson()) } })
            .put("hasMore", moreCalls)
            .put("lastSeq", calls.lastOrNull()?.seq ?: callsAfter))
        calls.lastOrNull()?.let { PushNotifier.deliveredCallSeq = maxOf(PushNotifier.deliveredCallSeq, it.seq) }
        val (sms, moreSms) = SmsJournal.get(context).readAfter(smsAfter)
        send(peer, JSONObject()
            .put("type", "sync_sms")
            .put("items", JSONArray().apply { sms.forEach { put(it.toJson()) } })
            .put("hasMore", moreSms)
            .put("lastSeq", sms.lastOrNull()?.seq ?: smsAfter))
        sms.lastOrNull()?.let { PushNotifier.deliveredSmsSeq = maxOf(PushNotifier.deliveredSmsSeq, it.seq) }
    }

    /** Protocol 1 only: the incoming SMS list older iPhone builds understand. */
    private fun legacySmsHistory(peer: Peer, afterId: Long) {
        val store = SmsStore.get(context)
        store.importFromSystemInbox()
        val page = store.readAfter(afterId)
        send(peer, JSONObject()
            .put("type", "sms_history")
            .put("messages", JSONArray().apply {
                for (sms in page.messages) put(JSONObject().put("id", sms.id).put("sender", sms.sender)
                    .put("message", sms.message).put("timestamp", sms.timestamp))
            })
            .put("hasMore", page.hasMore)
            .put("lastId", page.messages.lastOrNull()?.id ?: afterId))
    }

    private fun stateMessage(): JSONObject {
        val state = CallManager.currentCallState
        return JSONObject().put("type", "state").put("call",
            if (state == "idle" || state == "disconnected") JSONObject.NULL
            else JSONObject().put("callId", CallManager.currentCallId ?: "")
                .put("number", CallManager.currentPhoneNumber ?: "")
                .put("state", state)
                .put("direction", CallManager.currentDirection))
    }

    private fun callEvent(callId: String, state: String, number: String?) = JSONObject().apply {
        put("type", "call")
        put("state", state)
        if (!number.isNullOrBlank()) put("number", number)
        if (callId != "none" && callId.isNotBlank()) put("callId", callId)
        put("direction", CallManager.currentDirection)
    }

    // ---- Broadcasts ----

    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        if (callId == "none" && state == "idle") return
        broadcast(callEvent(callId, state, number))
    }

    override fun onLogMessage(message: String) = Unit

    override fun onAudioOwnerChanged(owner: CallAudioRelay.Owner, reason: String) {
        for (peer in peers.values) if (peer.authenticated) sendAudioState(peer, owner, reason)
        // While the gateway is waiting for the iPhone to offer its uplink, keep telling it whose
        // turn it is. Measured on the device: after the app was reinstalled the iPhone connected,
        // the gateway waited for its audio, and no uplink ever arrived, so the gate never saw a
        // frame and the media never started. The state was announced exactly once, at the moment
        // the owner changed, and the iPhone did not act on it.
        //
        // Rule 6.1 records the same shape from an earlier fault: audio_start arrives once per arm
        // and is never retried, so a phone that misses the one announcement stays silent for the
        // whole call. Repeating an idempotent state message is the cheap half of that lesson, and
        // it needs no iOS build.
        if (owner == CallAudioRelay.Owner.PREPARING_IPHONE) {
            preparingRepeats = 0
            handler.removeCallbacks(repeatAudioState)
            handler.postDelayed(repeatAudioState, AUDIO_STATE_REPEAT_MS)
        } else {
            handler.removeCallbacks(repeatAudioState)
        }
    }

    /**
     * Re-announces the preparing state every second for as long as it lasts, so an iPhone that
     * missed the single announcement gets another chance. Stops on its own when the owner changes,
     * and gives up after the same eight seconds the audio wait allows.
     */
    private val repeatAudioState = object : Runnable {
        override fun run() {
            val owner = CallAudioRelay.owner
            if (owner != CallAudioRelay.Owner.PREPARING_IPHONE) return
            preparingRepeats++
            if (preparingRepeats > AUDIO_STATE_REPEAT_LIMIT) {
                Log.i(TAG, "Stopped repeating the preparing state after $preparingRepeats attempts")
                return
            }
            for (peer in peers.values) {
                if (peer.authenticated) sendAudioState(peer, owner, "waiting_for_iphone_audio_repeat")
            }
            Log.i(TAG, "Repeated the preparing state ($preparingRepeats)")
            handler.postDelayed(this, AUDIO_STATE_REPEAT_MS)
        }
    }

    /** How often the preparing state is re-announced while the iPhone has not answered. */
    private val AUDIO_STATE_REPEAT_MS = 1_000L
    /** How many times before giving up, matching the eight second audio wait. */
    private val AUDIO_STATE_REPEAT_LIMIT = 8
    private var preparingRepeats = 0
    private val handler = Handler(Looper.getMainLooper())

    private fun sendAudioState(peer: Peer, owner: CallAudioRelay.Owner, reason: String) {
        // Protocol 1 iPhones stop their audio engine on any running=false that is not a retry
        // request, so the transient preparing state is only announced to protocol 2 clients.
        if (owner == CallAudioRelay.Owner.PREPARING_IPHONE && peer.protocol < 2) return
        send(peer, JSONObject()
            .put("type", "audio_state")
            .put("running", owner == CallAudioRelay.Owner.IPHONE)
            .put("owner", owner.name.lowercase())
            .put("detail", reason))
    }

    private fun waitingMessage(callId: String?, number: String?) = JSONObject()
        .put("type", "call_waiting")
        .put("state", if (callId != null) "ringing" else "ended")
        .apply {
            if (callId != null) put("callId", callId)
            if (!number.isNullOrBlank()) put("number", number)
        }

    @Volatile private var lastWaitingId: String? = null

    override fun onWaitingCallChanged(callId: String?, number: String?) {
        // "ended" names the call that stopped waiting, so the iPhone can close exactly that one.
        val message = if (callId == null) waitingMessage(null, null).put("callId", lastWaitingId ?: "") else waitingMessage(callId, number)
        if (callId != null) lastWaitingId = callId
        for (peer in peers.values) if (peer.authenticated && peer.protocol >= 2) send(peer, message)
    }

    override fun onCallJournalEntry(entry: CallJournal.Entry) {
        val payload = JSONObject().put("type", "call_log").put("item", entry.toJson())
        var delivered = false
        for (peer in peers.values) if (peer.authenticated && peer.protocol >= 2) {
            send(peer, payload)
            delivered = true
        }
        if (delivered) PushNotifier.deliveredCallSeq = maxOf(PushNotifier.deliveredCallSeq, entry.seq)
    }

    override fun onSmsJournalEntry(entry: SmsJournal.Entry) {
        val payload = JSONObject().put("type", "sms").put("item", entry.toJson())
        var delivered = false
        for (peer in peers.values) if (peer.authenticated && peer.protocol >= 2) {
            send(peer, payload)
            delivered = true
        }
        if (delivered) PushNotifier.deliveredSmsSeq = maxOf(PushNotifier.deliveredSmsSeq, entry.seq)
    }

    /** Protocol 1 only; protocol 2 clients receive the journal entry instead. */
    fun broadcastSmsReceived(sender: String, message: String, timestamp: Long, messageId: Long) {
        val payload = JSONObject().put("type", "sms_received").put("sender", sender)
            .put("message", message).put("timestamp", timestamp)
        if (messageId > 0) payload.put("id", messageId)
        for (peer in peers.values) if (peer.authenticated && peer.protocol < 2) send(peer, payload)
    }

    private fun broadcast(json: JSONObject) {
        for (peer in peers.values) if (peer.authenticated) send(peer, json)
    }

    private fun send(peer: Peer, json: JSONObject) {
        if (!peer.conn.isOpen) return
        val channel = peer.channel
        // Sealing and queueing happen together so frames leave in counter order; the iPhone
        // drops any frame whose counter is not above the last one.
        synchronized(peer) {
            runCatching { peer.conn.send(if (channel != null) channel.seal(json.toString()) else json.toString()) }
        }
    }

    private fun sendPlain(conn: WebSocket, json: JSONObject) {
        runCatching { if (conn.isOpen) conn.send(json.toString()) }
    }

    private fun sendAck(peer: Peer, action: String, success: Boolean) =
        send(peer, JSONObject().put("type", "ack").put("action", action).put("success", success))

    fun authenticatedCount(): Int = peers.values.count { it.authenticated }

    fun requestContactResolution(number: String) {
        if (number.isBlank()) return
        val payload = JSONObject().put("type", "resolve_contact").put("number", number)
        broadcast(payload)
    }

    fun stopServer() {
        try {
            if (current === this) current = null
            CallManager.removeListener(this)
            CallManager.removeWaitingListener(this)
            CallAudioRelay.removeListener(this)
            CallJournal.get(context).removeListener(this)
            SmsJournal.get(context).removeListener(this)
            audioOwner = null
            CallAudioRelay.disarm("gateway_stopped")
            SmsReceiver.webSocketServer = null
            peers.clear()
            stop(1000)
            serverListener?.onClientCountChanged(0)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }
}

/**
 * A one word reading of a closed connection, so the log says something useful at the moment
 * someone is looking at a phone that claims the iPhone is not connected.
 *
 * This exists because on 16.10.2026 the real iPhone reconnected five times in forty six seconds
 * and every one of those was logged only as a fresh connection. A network change, a keepalive
 * timeout, the iPhone going to the background and this side dropping the socket all produce the
 * same silence, so none of them could be ruled out afterwards.
 *
 * Kept at file scope, away from the Android dependencies, so it can be asserted on directly.
 *
 * @param authenticated whether the peer got through authentication at all
 * @param code the WebSocket close code
 * @param remote true when the other side closed the socket rather than this one
 * @param sinceLastMessageMs how long before the close the last message arrived
 */
internal fun classifyClose(
    authenticated: Boolean,
    code: Int,
    remote: Boolean,
    sinceLastMessageMs: Long,
): String = when {
    // Never got past the handshake: look at the pairing and the auth path, not the network.
    !authenticated -> "auth_never_completed"
    // The other side said goodbye properly, which is a deliberate reconnect rather than a drop.
    remote && code == 1000 -> "remote_clean"
    // Vanished mid-conversation: the link died rather than being closed.
    sinceLastMessageMs < 3_000 -> "dropped_while_active"
    // Sat silent for a long time first: suspect a keepalive or an idle timeout.
    sinceLastMessageMs > 30_000 -> "idle_then_closed"
    else -> "closed_quiet"
}
