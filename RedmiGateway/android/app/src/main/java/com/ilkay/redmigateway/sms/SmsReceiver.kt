package com.ilkay.redmigateway.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.ilkay.redmigateway.server.GatewayWebSocketServer
import com.ilkay.redmigateway.telecom.CallManager

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        var webSocketServer: GatewayWebSocketServer? = null
        var onSmsReceivedListener: ((sender: String, message: String, timestamp: Long) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val sender = messages[0].displayOriginatingAddress ?: "Unknown"
            val timestamp = messages[0].timestampMillis

            val bodyBuilder = StringBuilder()
            for (msg in messages) {
                bodyBuilder.append(msg.displayMessageBody)
            }
            val fullMessage = bodyBuilder.toString()

            Log.i(TAG, "SMS received (${fullMessage.length} characters)")
            CallManager.log("SMS received (${fullMessage.length} characters)")

            val messageId = try {
                SmsStore.get(context).saveIncoming(sender, fullMessage, timestamp)
            } catch (e: Exception) {
                Log.e(TAG, "Could not store incoming SMS for reconnect sync", e)
                -1L
            }

            // Broadcast to connected WebSocket clients (e.g. iOS client)
            webSocketServer?.broadcastSmsReceived(sender, fullMessage, timestamp, messageId)

            // Notify in-app UI if registered
            onSmsReceivedListener?.invoke(sender, fullMessage, timestamp)
        }
    }
}
