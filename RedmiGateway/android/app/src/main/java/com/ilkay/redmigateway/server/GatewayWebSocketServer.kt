package com.ilkay.redmigateway.server

import android.content.Context
import android.os.Build
import android.util.Log
import com.ilkay.redmigateway.RedmiGatewayApp
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.audio.AudioProbeManager
import com.ilkay.redmigateway.audio.CallAudioRelay
import com.ilkay.redmigateway.sms.SmsReceiver
import com.ilkay.redmigateway.sms.SmsManagerHelper
import com.ilkay.redmigateway.sms.SmsStore
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class GatewayWebSocketServer(
    private val context: Context,
    port: Int = 8787
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)), CallManager.CallListener,
    CallAudioRelay.Listener {

    companion object {
        private const val TAG = "GatewayWSServer"
    }

    interface ServerListener {
        fun onClientCountChanged(authenticatedCount: Int)
        fun onServerError(error: String)
    }

    var serverListener: ServerListener? = null
    private data class AuthFailureRecord(var count: Int, var lockedUntil: Long)
    private val authenticatedClients = ConcurrentHashMap.newKeySet<WebSocket>()
    private val ipAuthFailures = ConcurrentHashMap<String, AuthFailureRecord>()
    @Volatile private var audioOwner: WebSocket? = null

    init {
        isReuseAddr = true
        isTcpNoDelay = true
    }

    override fun onStart() {
        Log.i(TAG, "WebSocket server started on port $port")
        CallManager.addListener(this)
        CallAudioRelay.addListener(this)
        SmsReceiver.webSocketServer = this
        CallManager.log("WebSocket server listening on 0.0.0.0:$port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        val remote = conn.remoteSocketAddress?.toString() ?: "unknown"
        Log.i(TAG, "New incoming client connection from: $remote")
        CallManager.log("Client connected: $remote")

        // Send hello handshake
        val hello = JSONObject().apply {
            put("type", "hello")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("protocol", 1)
        }
        conn.send(hello.toString())
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        val wasAuth = authenticatedClients.remove(conn)
        if (audioOwner === conn) {
            audioOwner = null
            CallAudioRelay.disarm("iPhone connection lost")
        }
        val remoteAddr = conn.remoteSocketAddress?.toString() ?: "unknown"
        Log.i(TAG, "Client disconnected: $remoteAddr (Auth: $wasAuth, Reason: $reason)")
        CallManager.log("Client disconnected: $remoteAddr")
        serverListener?.onClientCountChanged(authenticatedClients.size)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val remoteAddr = conn.remoteSocketAddress?.toString() ?: "unknown"

        if (message.length > 8192) {
            sendError(conn, "Message exceeds 8192 characters")
            return
        }

        try {
            val json = JSONObject(message)
            val type = json.optString("type", "")
            Log.d(TAG, "Received command type=$type from $remoteAddr")

            if (type == "auth") {
                handleAuth(conn, json)
                return
            }

            // All other commands require authentication
            if (!authenticatedClients.contains(conn)) {
                sendError(conn, "Unauthorized. Must authenticate first with pairing token.")
                return
            }

            when (type) {
                "answer" -> {
                    val callId = json.optString("callId")
                    if (callId.isBlank()) {
                        sendError(conn, "answer requires callId")
                        return
                    }
                    CallManager.log("WebSocket command: ANSWER (callId=$callId)")
                    val success = CallManager.answer(callId)
                    sendAck(conn, "answer", success)
                }
                "reject" -> {
                    val callId = json.optString("callId")
                    if (callId.isBlank()) {
                        sendError(conn, "reject requires callId")
                        return
                    }
                    CallManager.log("WebSocket command: REJECT (callId=$callId)")
                    val success = CallManager.reject(callId)
                    sendAck(conn, "reject", success)
                }
                "hangup" -> {
                    val callId = json.optString("callId")
                    if (callId.isBlank()) {
                        sendError(conn, "hangup requires callId")
                        return
                    }
                    CallManager.log("WebSocket command: HANGUP (callId=$callId)")
                    val success = CallManager.hangup(callId)
                    sendAck(conn, "hangup", success)
                }
                "dial" -> {
                    val number = json.optString("number", "")
                    CallManager.log("WebSocket command: DIAL")
                    val success = CallManager.dial(context, number)
                    sendAck(conn, "dial", success)
                }
                "dtmf" -> {
                    val digit = json.optString("digit", "")
                    if (digit.isNotEmpty()) {
                        CallManager.log("WebSocket command: DTMF tone")
                        val success = CallManager.playDtmf(digit[0])
                        sendAck(conn, "dtmf", success)
                    } else {
                        sendError(conn, "dtmf requires digit")
                    }
                }
                "get_state" -> {
                    CallManager.log("WebSocket command: GET_STATE")
                    handleGetState(conn)
                }
                "get_sms_history" -> {
                    handleGetSmsHistory(conn, json.optLong("afterId", 0L))
                }
                "audio_start" -> {
                    if (CallManager.currentCallState !in setOf("ringing", "dialing", "connecting", "active")) {
                        sendError(conn, "No current cellular call")
                        return
                    }
                    if (audioOwner != null && audioOwner !== conn) {
                        sendError(conn, "Another client owns call audio")
                        return
                    }
                    val clientAddress = conn.remoteSocketAddress?.address
                    val session = CallAudioRelay.arm(context, clientAddress)
                    audioOwner = conn
                    conn.send(JSONObject().apply {
                        put("type", "audio_ready")
                        put("session", session.toLong() and 0xFFFFFFFFL)
                        put("port", CallAudioRelay.PORT)
                        put("sampleRate", CallAudioRelay.SAMPLE_RATE)
                        put("frameSamples", CallAudioRelay.FRAME_SAMPLES)
                    }.toString())
                }
                "audio_stop" -> {
                    if (audioOwner !== conn) {
                        sendError(conn, "This client does not own call audio")
                        return
                    }
                    audioOwner = null
                    CallAudioRelay.disarm("iPhone ended the audio session")
                    sendAck(conn, "audio_stop", true)
                }
                "audio_selftest" -> {
                    val muteMic = json.optBoolean("muteLocalMic", false)
                    val seconds = json.optInt("seconds", 5).coerceIn(1, 15)
                    Thread {
                        val result = CallAudioRelay.selfTest(context, muteMic, seconds)
                        result.put("type", "audio_selftest_result")
                        if (conn.isOpen) conn.send(result.toString())
                    }.start()
                }
                "probe_audio" -> {
                    val source = json.optString("source", "ALL")
                    CallManager.log("WebSocket command: PROBE_AUDIO (source=$source)")
                    handleProbeAudio(conn, source)
                }
                "send_sms", "sms_send" -> {
                    val recipient = json.optString("recipient", "")
                    val smsText = json.optString("message", "")
                    CallManager.log("WebSocket command: SEND_SMS")
                    val (success, error) = SmsManagerHelper.sendSms(context, recipient, smsText)
                    val result = JSONObject().apply {
                        put("type", "sms_send_result")
                        put("recipient", recipient)
                        put("success", success)
                        if (error != null) put("error", error)
                    }
                    conn.send(result.toString())
                }
                else -> {
                    sendError(conn, "Unknown command type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process client message", e)
            sendError(conn, "Malformed message: ${e.message}")
        }
    }

    private fun handleGetState(conn: WebSocket) {
        val state = CallManager.currentCallState
        val response = JSONObject().apply {
            put("type", "state")
            if (state != "idle" && state != "disconnected") {
                val callObj = JSONObject().apply {
                    put("callId", CallManager.currentCallId ?: "")
                    put("number", CallManager.currentPhoneNumber ?: "")
                    put("state", state)
                }
                put("call", callObj)
            } else {
                put("call", JSONObject.NULL)
            }
        }
        conn.send(response.toString())
    }

    private fun handleGetSmsHistory(conn: WebSocket, afterId: Long) {
        val store = SmsStore.get(context)
        store.importFromSystemInbox()
        val page = store.readAfter(afterId)
        val items = JSONArray()
        for (sms in page.messages) {
            items.put(JSONObject().apply {
                put("id", sms.id)
                put("sender", sms.sender)
                put("message", sms.message)
                put("timestamp", sms.timestamp)
            })
        }
        conn.send(JSONObject().apply {
            put("type", "sms_history")
            put("messages", items)
            put("hasMore", page.hasMore)
            put("lastId", page.messages.lastOrNull()?.id ?: afterId)
        }.toString())
    }

    private fun handleProbeAudio(conn: WebSocket, targetSource: String) {
        Thread {
            try {
                val results = if (targetSource.equals("ALL", ignoreCase = true) || targetSource.isBlank()) {
                    AudioProbeManager.runAllProbes()
                } else {
                    listOf(AudioProbeManager.runProbeForSource(targetSource))
                }

                val arr = JSONArray()
                for (r in results) {
                    arr.put(r.toJson())
                }

                val resJson = JSONObject().apply {
                    put("type", "probe_result")
                    put("results", arr)
                }
                conn.send(resJson.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error during audio probe execution", e)
                sendError(conn, "Probe execution failed: ${e.message}")
            }
        }.start()
    }

    private fun handleAuth(conn: WebSocket, json: JSONObject) {
        val remoteIp = conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"
        val now = System.currentTimeMillis()
        val failureRecord = ipAuthFailures[remoteIp]
        if (failureRecord != null && failureRecord.lockedUntil > now) {
            val waitSec = ((failureRecord.lockedUntil - now) / 1000).coerceAtLeast(1)
            CallManager.log("Auth rejected: IP $remoteIp is locked for ${waitSec}s")
            val authResult = JSONObject().apply {
                put("type", "auth_result")
                put("success", false)
                put("message", "Çok fazla başarısız deneme. Lütfen ${waitSec} saniye bekleyin.")
            }
            conn.send(authResult.toString())
            conn.close(1008, "Throttled")
            return
        }

        val token = json.optString("token", "")
        val expectedToken = RedmiGatewayApp.getPairingToken()

        if (token.equals(expectedToken, ignoreCase = true)) {
            ipAuthFailures.remove(remoteIp)
            authenticatedClients.add(conn)
            CallManager.log("Client authenticated successfully from: $remoteIp")

            val authResult = JSONObject().apply {
                put("type", "auth_result")
                put("success", true)
                put("message", "Authenticated successfully")
            }
            conn.send(authResult.toString())
            serverListener?.onClientCountChanged(authenticatedClients.size)

            // If a call is currently active or ringing, immediately push its state to this client
            val activeState = CallManager.currentCallState
            if (activeState != "idle") {
                val callEvent = JSONObject().apply {
                    put("type", "call")
                    put("state", activeState)
                    put("number", CallManager.currentPhoneNumber ?: "")
                    put("callId", CallManager.currentCallId ?: "")
                }
                conn.send(callEvent.toString())
            }
        } else {
            val rec = ipAuthFailures.compute(remoteIp) { _, existing ->
                val r = existing ?: AuthFailureRecord(0, 0L)
                r.count += 1
                if (r.count >= 5) {
                    r.lockedUntil = System.currentTimeMillis() + 60_000L
                }
                r
            }
            val count = rec?.count ?: 1
            val remaining = (5 - count).coerceAtLeast(0)
            CallManager.log("Client authentication failed from IP $remoteIp (attempt $count/5)")
            val isLocked = rec != null && rec.lockedUntil > now
            val authResult = JSONObject().apply {
                put("type", "auth_result")
                put("success", false)
                put("message", if (isLocked) "Çok fazla başarısız deneme. 60 saniye bekleyin." else "Geçersiz eşleştirme kodu ($remaining deneme kaldı)")
            }
            conn.send(authResult.toString())
            if (isLocked) {
                conn.close(1008, "Too many authentication failures")
            }
        }
    }

    private fun sendAck(conn: WebSocket, action: String, success: Boolean) {
        val ack = JSONObject().apply {
            put("type", "ack")
            put("action", action)
            put("success", success)
        }
        conn.send(ack.toString())
    }

    private fun sendError(conn: WebSocket, msg: String) {
        val err = JSONObject().apply {
            put("type", "error")
            put("message", msg)
        }
        conn.send(err.toString())
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        val connStr = conn?.remoteSocketAddress?.toString() ?: "server"
        Log.e(TAG, "WebSocket error on $connStr", ex)
        serverListener?.onServerError(ex.message ?: "Unknown socket error")
    }

    // CallManager.CallListener implementation
    override fun onCallStateChanged(callId: String, state: String, number: String?) {
        broadcastCallState(callId, state, number)
    }

    override fun onLogMessage(message: String) {
        // We do not broadcast internal logs over websocket to keep protocol lean
    }

    private fun broadcastCallState(callId: String, state: String, number: String?) {
        if (authenticatedClients.isEmpty()) return

        val event = JSONObject().apply {
            put("type", "call")
            put("state", state)
            if (!number.isNullOrBlank()) {
                put("number", number)
            }
            if (callId != "none") {
                put("callId", callId)
            }
        }
        val payload = event.toString()
        Log.d(TAG, "Broadcasting call state=$state to ${authenticatedClients.size} client(s)")

        for (client in authenticatedClients) {
            try {
                if (client.isOpen) {
                    client.send(payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send event to client", e)
            }
        }
    }

    override fun onAudioRelayState(running: Boolean, detail: String) {
        val payload = JSONObject().apply {
            put("type", "audio_state")
            put("running", running)
            put("detail", detail)
        }.toString()
        for (client in authenticatedClients) {
            runCatching { if (client.isOpen) client.send(payload) }
        }
    }

    fun broadcastSmsReceived(sender: String, message: String, timestamp: Long, messageId: Long) {
        if (authenticatedClients.isEmpty()) return

        val event = JSONObject().apply {
            put("type", "sms_received")
            put("sender", sender)
            put("message", message)
            put("timestamp", timestamp)
            if (messageId > 0) put("id", messageId)
        }
        val payload = event.toString()
        Log.d(TAG, "Broadcasting SMS received event to ${authenticatedClients.size} authenticated client(s)")

        for (client in authenticatedClients) {
            try {
                if (client.isOpen) {
                    client.send(payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SMS event to client", e)
            }
        }
    }

    fun stopServer() {
        try {
            CallManager.removeListener(this)
            CallAudioRelay.removeListener(this)
            audioOwner = null
            CallAudioRelay.disarm("gateway stopped")
            SmsReceiver.webSocketServer = null
            authenticatedClients.clear()
            ipAuthFailures.clear()
            stop(1000)
            serverListener?.onClientCountChanged(0)
            CallManager.log("WebSocket server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    fun getAuthenticatedClientCount(): Int = authenticatedClients.size
}
