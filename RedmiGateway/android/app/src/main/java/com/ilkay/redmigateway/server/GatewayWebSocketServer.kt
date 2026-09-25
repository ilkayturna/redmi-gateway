package com.ilkay.redmigateway.server

import android.content.Context
import android.os.Build
import android.util.Log
import com.ilkay.redmigateway.RedmiGatewayApp
import com.ilkay.redmigateway.telecom.CallManager
import com.ilkay.redmigateway.audio.AudioProbeManager
import com.ilkay.redmigateway.sms.SmsReceiver
import com.ilkay.redmigateway.sms.SmsManagerHelper
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
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)), CallManager.CallListener {

    companion object {
        private const val TAG = "GatewayWSServer"
    }

    interface ServerListener {
        fun onClientCountChanged(authenticatedCount: Int)
        fun onServerError(error: String)
    }

    var serverListener: ServerListener? = null
    private val authenticatedClients = ConcurrentHashMap.newKeySet<WebSocket>()
    private val failedAuthAttempts = ConcurrentHashMap<WebSocket, Int>()

    init {
        isReuseAddr = true
        isTcpNoDelay = true
    }

    override fun onStart() {
        Log.i(TAG, "WebSocket server started on port $port")
        CallManager.addListener(this)
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
        failedAuthAttempts.remove(conn)
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
                "get_state" -> {
                    CallManager.log("WebSocket command: GET_STATE")
                    handleGetState(conn)
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
        val token = json.optString("token", "")
        val expectedToken = RedmiGatewayApp.getPairingToken()

        if (token.equals(expectedToken, ignoreCase = true)) {
            failedAuthAttempts.remove(conn)
            authenticatedClients.add(conn)
            CallManager.log("Client authenticated successfully: ${conn.remoteSocketAddress}")

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
            val failures = failedAuthAttempts.merge(conn, 1, Int::plus) ?: 1
            CallManager.log("Client authentication failed")
            val authResult = JSONObject().apply {
                put("type", "auth_result")
                put("success", false)
                put("message", "Invalid pairing token")
            }
            conn.send(authResult.toString())
            if (failures >= 5) {
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

    fun broadcastSmsReceived(sender: String, message: String, timestamp: Long) {
        if (authenticatedClients.isEmpty()) return

        val event = JSONObject().apply {
            put("type", "sms_received")
            put("sender", sender)
            put("message", message)
            put("timestamp", timestamp)
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
            SmsReceiver.webSocketServer = null
            authenticatedClients.clear()
            failedAuthAttempts.clear()
            stop(1000)
            serverListener?.onClientCountChanged(0)
            CallManager.log("WebSocket server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    fun getAuthenticatedClientCount(): Int = authenticatedClients.size
}
