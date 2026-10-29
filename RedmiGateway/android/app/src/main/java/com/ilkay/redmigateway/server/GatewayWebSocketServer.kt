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
import com.ilkay.redmigateway.journal.IosDiagStore
import com.ilkay.redmigateway.journal.IphoneDiagnostics
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
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
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
) : WebSocketServer(
    InetSocketAddress("0.0.0.0", port),
    // The library's default frame limit is Integer.MAX_VALUE, and it allocates a buffer of the
    // announced size before the payload arrives. One header claiming 2 GB from anyone on the same
    // Wi-Fi would run the process out of memory, and a fatal error stops the whole server.
    // onMessage already ignores anything above 16 KB, so 64 KB costs nothing real.
    listOf<Draft>(Draft_6455(emptyList(), MAX_FRAME_BYTES))
), CallManager.CallListener,
    CallAudioRelay.Listener, CallJournal.Listener, SmsJournal.Listener, CallManager.WaitingListener {

    companion object {
        private const val TAG = "GatewayWSServer"
        private const val MAX_FRAME_BYTES = 64 * 1024
        private const val MAX_UNDELIVERED_SMS_RESULTS = 20
        /** Long enough for the iPhone to come back; a result much later only confuses. */
        private const val UNDELIVERED_SMS_RESULT_MS = 30 * 60_000L
        const val PROTOCOL = 2
        const val MIN_PROTOCOL = 1
        private const val MAX_FAILURES = 5
        private const val LOCKOUT_MS = 60_000L
        /**
         * Inbound frames above this many chars are dropped before they are opened; the iPhone
         * sizes client_log batches and client_diag parts to stay below it (docs/PROTOCOL.md).
         */
        internal const val MAX_INBOUND_CHARS = 16_384
        /** A dropped oversized frame is written to the iPhone log at most this often. */
        private const val OVERSIZED_NOTE_INTERVAL_MS = 60_000L
        /**
         * How many client_diag id/error pairs are remembered as already written. The iPhone's
         * outbox holds at most 20 reports, so this covers every report it can be retrying.
         */
        private const val DIAG_PROBLEMS_REMEMBERED = 32

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
        /** The server stopped without [stopServer]: a fatal error or a failed bind. */
        fun onServerStoppedUnexpectedly()
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
        // Which iPhone build is on the other end. Without it a ring that failed cannot be tied to a
        // build, and "the new one is installed" stays a guess made from the TestFlight screen.
        @Volatile var appVersion = ""
        @Volatile var appBuild = ""
        // Guarded by the peer's monitor, the same lock [send] seals under.
        val health = HealthDelivery()
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
        HealthBus.addListener(healthListener)
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
            val verdict = classifyClose(peer.authenticated, code, remote, sinceLastMessageMs)
            Log.i(
                TAG,
                "WebSocket closed code=$code remote=$remote authenticated=${peer.authenticated} " +
                    "protocol=${peer.protocol} sessionMs=$sessionMs " +
                    "sinceLastMessageMs=$sinceLastMessageMs " +
                    "verdict=$verdict " +
                    "reason=${reason?.take(120) ?: "none"}"
            )
            // The same line into the persistent file: the iPhone drops and re-opens this socket
            // constantly, and logcat is gone by the time the day's calls are reviewed.
            IphoneDiagnostics.record(
                "iPhone-link",
                "closed code=$code remote=$remote verdict=$verdict sessionMs=$sessionMs " +
                    "sinceLastMessageMs=$sinceLastMessageMs authenticated=${peer.authenticated} " +
                    "app=${peer.appVersion.ifBlank { "?" }}/${peer.appBuild.ifBlank { "?" }}"
            )
        } else {
            Log.i(TAG, "WebSocket closed code=$code remote=$remote peer=unknown")
        }
        flushOversizedNote()
        if (audioOwner === conn) {
            audioOwner = null
            // The iPhone's control socket blips constantly while its call audio is fine on its own
            // UDP path; releasing here pulled a working call back to the Redmi every time.
            CallAudioRelay.onControlLinkLost("iphone_disconnected")
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
        if (message.length > MAX_INBOUND_CHARS) {
            noteOversizedFrame(message.length)
            return
        }
        val json = try {
            JSONObject(message)
        } catch (_: Exception) {
            Log.w(TAG, "Dropped a frame that is not JSON (chars=${message.length})")
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
            if (json.optString("t") != "s") {
                Log.w(TAG, "Dropped an unsealed frame after authentication")
                return
            }
            val opened = channel.open(json.optLong("c", -1), json.optString("d")) ?: run {
                Log.w(TAG, "Dropped a sealed frame that failed authentication")
                return
            }
            try {
                JSONObject(opened)
            } catch (_: Exception) {
                Log.w(TAG, "Dropped a sealed frame whose content is not JSON")
                return
            }
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
            peer.appVersion = json.optString("appVersion")
            peer.appBuild = json.optString("appBuild")
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
            peer.appVersion = json.optString("appVersion")
            peer.appBuild = json.optString("appBuild")
            sendPlain(conn, result.put("success", true).put("message", "Authenticated successfully"))
            onAuthenticated(peer)
        } else {
            recordFailure(peer)
            sendPlain(conn, result.put("success", false).put("message", "Geçersiz eşleştirme kodu"))
        }
    }

    private fun onAuthenticated(peer: Peer) {
        peer.authenticatedAt = SystemClock.elapsedRealtime()
        // The prefix is kept exactly so existing log greps keep matching; the build is appended
        // inside the parenthesis because "which iPhone is connected" has to be answerable from the
        // device, not from the TestFlight screen.
        val version = if (peer.appVersion.isBlank()) "" else ", app ${peer.appVersion}/${peer.appBuild}"
        CallManager.log("iPhone connected (protocol ${peer.protocol}$version)")
        IphoneDiagnostics.record(
            "iPhone",
            "linked app=${peer.appVersion.ifBlank { "?" }} build=${peer.appBuild.ifBlank { "?" }} " +
                "protocol=${peer.protocol}"
        )
        serverListener?.onClientCountChanged(authenticatedCount())
        val state = CallManager.currentCallState
        if (peer.protocol >= 2) send(peer, callsMessage())
        if (state != "idle") send(peer, callEvent(CallManager.currentCallId ?: "", state, CallManager.currentPhoneNumber))
        if (peer.protocol >= 2) sendAudioState(peer, CallAudioRelay.owner, CallAudioRelay.lastReason)
        val waiting = CallManager.waitingCallId
        if (peer.protocol >= 2 && waiting != null) send(peer, waitingMessage(waiting, CallManager.waitingNumber))
        if (peer.protocol >= 2) flushSmsResults(peer)
        // The Redmi's health, so an iPhone that was away learns about a lost vendor fix or a low
        // battery the moment it is back. Old iPhone builds ignore the type.
        if (wantsHealth(peer.authenticated, peer.protocol) && !sendLatestHealth(peer)) {
            Log.i(TAG, "Health not sent after authentication: no snapshot yet, or the change listener already sent it")
        }
    }

    // ---- Commands ----

    private fun dispatch(peer: Peer, json: JSONObject) {
        val conn = peer.conn
        when (val type = json.optString("type")) {
            // holdCurrent: "Beklet ve yanıtla" for a waiting call (2.0.13); without it the current call ends.
            "answer" -> sendAck(peer, "answer", CallManager.answer(json.optString("callId").ifBlank { null }, json.optBoolean("holdCurrent", false)))
            "reject" -> sendAck(peer, "reject", CallManager.reject(json.optString("callId").ifBlank { null }))
            "hangup" -> sendAck(peer, "hangup", CallManager.hangup(json.optString("callId").ifBlank { null }))
            "hold_call" -> {
                val callId = json.optString("callId").ifBlank { null }
                val hold = json.optBoolean("hold", true)
                val call = if (callId != null) CallManager.getCall(callId) else CallManager.activeCall
                val ok = if (CallManager.isMerging) {
                    // The network merges one active and one held call. CallKit may change the hold
                    // of the calls it groups; passing that on would swap them in the middle of it.
                    CallManager.log("hold_call (${if (hold) "hold" else "resume"}) during a merge; left to the merge")
                    true
                } else if (call != null) {
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
            "audio_start" -> startAudio(peer, json)
            "audio_stop" -> {
                if (claimAudioControl(peer)) {
                    audioOwner = null
                    CallAudioRelay.disarm("iphone_stopped")
                }
                sendAck(peer, "audio_stop", true)
            }
            "audio_to_redmi" -> {
                val owns = claimAudioControl(peer)
                if (owns) CallAudioRelay.transferAudioToRedmi()
                sendAck(peer, "audio_to_redmi", owns)
            }
            "send_sms", "sms_send" -> sendSms(peer, json)
            "sync" -> sync(peer, json.optLong("callsAfter", 0), json.optLong("smsAfter", 0))
            "get_sms_history" -> legacySmsHistory(peer, json.optLong("afterId", 0))
            "diagnostics" -> send(peer, DiagnosticsReport.build(context).put("type", "diagnostics_result"))
            // Telemetry the iPhone pushes about itself. It has no USB debugging, so these frames
            // are the only way its own log and state become readable: they land in
            // iphone-diagnostics.log next to the call log and are pulled with adb. client_log and
            // client_status get no reply on purpose - an ack would double the traffic for every
            // batch. client_diag is acked per part, because the iPhone keeps a report until it is
            // stored here.
            "client_log" -> runCatching {
                val app = json.optString("appVersion").ifBlank { "?" }
                val build = json.optString("appBuild").ifBlank { "?" }
                val label = "iPhone[$app/$build]"
                val dropped = json.optInt("dropped", 0)
                if (dropped > 0) {
                    IphoneDiagnostics.record(label, "telemetry dropped $dropped lines before this batch")
                }
                val lines = json.optJSONArray("lines") ?: JSONArray()
                val limit = minOf(lines.length(), IphoneDiagnostics.MAX_LINES_PER_FRAME)
                for (index in 0 until limit) {
                    val text = lines.optString(index)
                    if (text.isNotBlank()) IphoneDiagnostics.record(label, text)
                }
            }.onFailure { Log.w(TAG, "client_log could not be stored", it) }
            // Every key is kept, not a fixed list, so a new iOS field is readable without a Redmi
            // update; the keys build 155 sends still come first and in the old order.
            "client_status" -> runCatching { IphoneDiagnostics.recordStatus(json) }
                .onFailure { Log.w(TAG, "client_status could not be stored", it) }
            "client_diag" -> runCatching { handleClientDiag(peer, json) }
                .onFailure { Log.w(TAG, "client_diag could not be handled", it) }
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

    /**
     * Whether [peer] may stop or move the call audio.
     *
     * Ownership belongs to the socket that sent audio_start, but iOS replaces that socket all the
     * time while the audio itself keeps flowing on UDP (onClose deliberately keeps the session).
     * The new socket of the same iPhone then had no say: "Redmi'ye al" got a failed ack and
     * audio_stop was ignored, so the Redmi stayed muted until the silence fallback. Only the paired
     * iPhone can authenticate with protocol 2, so such a peer takes over an owner whose socket is
     * gone; a second live socket still cannot take the audio from the one that holds it.
     */
    private fun claimAudioControl(peer: Peer): Boolean {
        val owner = audioOwner
        if (owner === peer.conn) return true
        if (peer.protocol < 2 || (owner != null && owner.isOpen)) return false
        if (owner == null && CallAudioRelay.owner == CallAudioRelay.Owner.REDMI) return false
        audioOwner = peer.conn
        Log.i(TAG, "Call audio control passed to the iPhone's new socket")
        return true
    }

    private fun startAudio(peer: Peer, json: JSONObject) {
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
        // The iPhone advertises that it can rebuild a downlink frame from the copy in the next
        // packet. Only a build that sends this field gets type 2; an older iPhone would ignore the
        // whole packet and lose all downlink audio.
        val wantsDownlinkRedundancy = json.optInt("rxRedundancy", 0) > 0
        val session = CallAudioRelay.arm(context, conn.remoteSocketAddress?.address, peer.mediaKey,
            conn.localSocketAddress?.address, wantsDownlinkRedundancy)
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

    // ---- iPhone diagnostics and Redmi health ----

    /**
     * One part of a large iPhone report (docs/PROTOCOL.md "client_diag"). Validated here, written
     * on IphoneDiagnostics' thread, and acked only after the write: the iPhone deletes its copy
     * when the last part is acked, so a success must mean the bytes are on disk.
     */
    private fun handleClientDiag(peer: Peer, json: JSONObject) {
        // Echoed in the ack as sent (the JSON escapes it); only the log copy is made safe.
        val id = json.optString("id").take(64)
        val part = json.optInt("part", -1)
        when (val parsed = IosDiagStore.parse(json)) {
            is IosDiagStore.Parsed.Invalid -> {
                noteDiagProblem(id, part, parsed.error, null)
                send(peer, clientDiagAck(id, part, parsed.error))
            }
            is IosDiagStore.Parsed.Valid -> IphoneDiagnostics.storeDiagPart(parsed.part) { outcome ->
                val error = (outcome as? IosDiagStore.Outcome.Failed)?.let {
                    noteDiagProblem(id, part, it.error, it.detail)
                    it.error
                }
                send(peer, clientDiagAck(id, part, error))
            }
        }
    }

    private val diagProblemsWritten = FirstSightings(DIAG_PROBLEMS_REMEMBERED)

    /**
     * A refused or failed part goes to logcat every time and to the iPhone log the first time its
     * id and error are seen. The iPhone keeps a report until its last part is acked, so it resends
     * a failing report on every connection, many times an hour; one line per report and error is
     * the evidence for why it never arrived, and more would push the history out of the rotating
     * file (AGENTS.md 8.1). Several reports failing in turn stay one line each.
     */
    private fun noteDiagProblem(id: String, part: Int, error: String, detail: String?) {
        val shownId = diagIdForLog(id)
        val text = "rejected id=$shownId part=$part error=$error" + (detail?.let { " $it" } ?: "")
        Log.w(TAG, "client_diag $text")
        if (!diagProblemsWritten.firstSighting("$shownId/$error")) return
        IphoneDiagnostics.record("iPhone-diag", text)
    }

    private val oversizedNote = RateLimitedNote(OVERSIZED_NOTE_INTERVAL_MS)

    /**
     * Such a frame used to vanish without a trace, so a client_log batch or a report that grew past
     * the gate looked exactly like one that was never sent. One line a minute is enough to show it
     * happens and how big the frames are; the count covers the ones in between.
     */
    private fun noteOversizedFrame(chars: Int) {
        val suppressed = oversizedNote.tryNote(SystemClock.elapsedRealtime())
        if (suppressed == null) {
            Log.w(TAG, "Dropped oversized frame chars=$chars (already noted this minute)")
            return
        }
        val extra = if (suppressed > 0) " suppressed=$suppressed" else ""
        IphoneDiagnostics.record("iPhone-link", "dropped oversized frame chars=$chars$extra")
    }

    /**
     * Writes the drops still held back when a connection closes. Without it the last burst of a
     * sender that stopped would only ever be counted in logcat; with it the file has the full count
     * by the time anyone reads it. Nothing is written when nothing was held back.
     */
    private fun flushOversizedNote() {
        val held = oversizedNote.drain()
        if (held > 0) IphoneDiagnostics.record("iPhone-link", "dropped oversized frames suppressed=$held (not yet noted)")
    }

    /**
     * Forwards each health change to the iPhones that are connected now. The snapshot handed in is
     * not used: [sendLatestHealth] reads the newest one under the connection's lock, so a change
     * racing an authentication can never leave the older snapshot as the last one the iPhone got.
     */
    private val healthListener = HealthBus.Listener { _ ->
        var sent = 0
        for (peer in peers.values) {
            if (wantsHealth(peer.authenticated, peer.protocol) && sendLatestHealth(peer)) sent++
        }
        // HealthBus calls this only when the change key moved, so this is one line per change.
        Log.i(TAG, "Health change sent to $sent iPhone connection(s)")
    }

    /**
     * Sends [HealthBus.latest] to [peer] unless that connection already got the same snapshot.
     * Reading and sending happen under the peer's monitor, which [send] also takes, so whichever
     * of the authentication and the change listener runs last sends the newest snapshot, and the
     * frames leave in that order. Returns whether a frame was sent.
     */
    private fun sendLatestHealth(peer: Peer): Boolean = synchronized(peer) {
        val frame = peer.health.next(HealthBus.latest()) ?: return@synchronized false
        send(peer, frame)
        true
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
            deliverSmsResult(peer, result)
        }
        val immediate = SmsSender.send(context, recipient, json.optString("message")) { ok, error -> reply(ok, error) }
        if (immediate != null) reply(false, immediate)
    }

    /**
     * Results of SMS sends whose socket closed before the radio answered. iOS suspends the app a
     * moment after it sends, so this was the common case: the SMS went out, the result was dropped
     * with the socket, and the iPhone marked the message failed after 90 s. A person then resends a
     * paid message that was already delivered. The iPhone matches a late result by requestId and
     * turns "failed" back into "sent", so the result goes to the next authenticated socket.
     */
    private val undeliveredSmsResults = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, JSONObject>>()

    private fun deliverSmsResult(requester: Peer, result: JSONObject) {
        if (requester.conn.isOpen) {
            send(requester, result)
            return
        }
        val other = peers.values.firstOrNull { it.authenticated && it.protocol >= 2 && it.conn.isOpen }
        if (other != null) {
            send(other, result)
            return
        }
        undeliveredSmsResults.add(SystemClock.elapsedRealtime() to result)
        while (undeliveredSmsResults.size > MAX_UNDELIVERED_SMS_RESULTS) undeliveredSmsResults.poll()
    }

    private fun flushSmsResults(peer: Peer) {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val (queuedAt, result) = undeliveredSmsResults.poll() ?: return
            if (now - queuedAt <= UNDELIVERED_SMS_RESULT_MS) send(peer, result)
        }
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
        // The full list goes first, also when the last call is gone, so the iPhone can close a
        // call on hold or the calls inside a conference that the primary event never names.
        val calls = callsMessage()
        for (peer in peers.values) if (peer.authenticated && peer.protocol >= 2) send(peer, calls)
        if (callId == "none" && state == "idle") return
        broadcast(callEvent(callId, state, number))
    }

    /** Every call on the Redmi (docs/PROTOCOL.md "calls"); old iPhone builds ignore the type. */
    private fun callsMessage(): JSONObject = JSONObject()
        .put("type", "calls")
        .put("primaryId", CallManager.currentCallId ?: "")
        .put("merging", CallManager.isMerging)
        .put("items", JSONArray().apply {
            for (info in CallManager.snapshot()) put(JSONObject()
                .put("callId", info.callId)
                .put("state", info.state)
                .put("number", info.number ?: "")
                .put("direction", info.direction)
                .put("conference", info.conference)
                .put("parentId", info.parentId ?: "")
                .put("waiting", info.waiting))
        })

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
            stoppingOnPurpose = true
            detach()
            audioOwner = null
            CallAudioRelay.disarm("gateway_stopped")
            peers.clear()
            stop(1000)
            serverListener?.onClientCountChanged(0)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    /**
     * Unhooks this server from the rest of the app without touching call audio, for a server that
     * died on its own and is being replaced: the relay runs on its own UDP path and a running call
     * keeps its audio while the control socket comes back.
     */
    fun detach() {
        if (current === this) current = null
        CallManager.removeListener(this)
        CallManager.removeWaitingListener(this)
        CallAudioRelay.removeListener(this)
        CallJournal.get(context).removeListener(this)
        SmsJournal.get(context).removeListener(this)
        HealthBus.removeListener(healthListener)
        handler.removeCallbacks(repeatAudioState)
        if (SmsReceiver.webSocketServer === this) SmsReceiver.webSocketServer = null
    }

    @Volatile private var stoppingOnPurpose = false
    private val reportedStop = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Every stop goes through here, including the library's own after a fatal error in a worker or
     * a failed bind. Before this, such a stop only produced a log line: the service kept running,
     * its notification kept saying all was well, and no audio_start, SMS or sync reached this phone
     * until a reboot.
     */
    override fun stop(timeout: Int, closeMessage: String) {
        try {
            super.stop(timeout, closeMessage)
        } finally {
            if (!stoppingOnPurpose && reportedStop.compareAndSet(false, true)) {
                Log.e(TAG, "WebSocket server stopped unexpectedly: $closeMessage")
                serverListener?.onServerStoppedUnexpectedly()
            }
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

/**
 * The `health` frame for the iPhone: a copy of the HealthBus snapshot with its type set, so the
 * caller's snapshot is never changed. The snapshot's shape is docs/PROTOCOL.md "health".
 */
internal fun healthFrame(snapshot: JSONObject): JSONObject = JSONObject(snapshot.toString()).put("type", "health")

/** Only an authenticated protocol 2 iPhone gets `health`; protocol 1 builds predate it. */
internal fun wantsHealth(authenticated: Boolean, protocol: Int): Boolean = authenticated && protocol >= 2

/**
 * What one iPhone connection was last sent of the Redmi's health. Not thread-safe: the server calls
 * it under the connection's send lock, so the read of the newest snapshot, this check and the send
 * happen as one step.
 */
internal class HealthDelivery {
    private var lastSent: String? = null

    /**
     * The `health` frame for [latest], or null when there is no snapshot yet or this connection
     * already got exactly this one (the authentication and the change listener can both try).
     */
    fun next(latest: JSONObject?): JSONObject? {
        if (latest == null) return null
        val text = latest.toString()
        if (text == lastSent) return null
        lastSent = text
        return healthFrame(latest)
    }
}

/**
 * Remembers the last [capacity] keys it was asked about, most recently seen last, so a note can be
 * written once per key while the keys that keep coming back are the ones that stay remembered.
 */
internal class FirstSightings(private val capacity: Int) {
    private val seen = LinkedHashSet<String>()

    /** True the first time [key] is seen, false while it is still remembered. */
    @Synchronized
    fun firstSighting(key: String): Boolean {
        val known = seen.remove(key)
        seen.add(key)
        if (seen.size > capacity) seen.remove(seen.first())
        return !known
    }
}

private val DIAG_ID_UNSAFE = Regex("[^A-Za-z0-9-]")

/**
 * A `client_diag` id as it may appear on one log line: a refused id can hold anything, and a
 * newline in it would start a line without a timestamp. A valid id is shown unchanged.
 */
internal fun diagIdForLog(id: String): String = id.take(64).replace(DIAG_ID_UNSAFE, "?").ifEmpty { "?" }

/**
 * The ack for one `client_diag` part: `success:true`, or `success:false` with [error] as a stable
 * code. The id and part are echoed so the iPhone can match the ack to the part it is waiting for.
 */
internal fun clientDiagAck(id: String, part: Int, error: String?): JSONObject = JSONObject()
    .put("type", "ack")
    .put("action", "client_diag")
    .put("id", id)
    .put("part", part)
    .put("success", error == null)
    .apply { if (error != null) put("error", error) }

/**
 * Lets one note through per [intervalMs] and counts the ones held back in between, so a line that
 * must not repeat at the rate of its cause (AGENTS.md 8.1) still says how often the cause happened.
 */
internal class RateLimitedNote(private val intervalMs: Long) {
    private var lastAt: Long? = null
    private var suppressed = 0

    /**
     * Returns null when a note was let through less than [intervalMs] ago (this one is counted),
     * otherwise the number held back since the last note, and starts a new interval at [now].
     */
    @Synchronized
    fun tryNote(now: Long): Int? {
        val last = lastAt
        if (last != null && now - last < intervalMs) {
            suppressed++
            return null
        }
        val held = suppressed
        suppressed = 0
        lastAt = now
        return held
    }

    /**
     * Returns the number held back since the last note and forgets it, without starting a new
     * interval: for a final line once the cause has stopped, so the count is not lost.
     */
    @Synchronized
    fun drain(): Int {
        val held = suppressed
        suppressed = 0
        return held
    }
}
