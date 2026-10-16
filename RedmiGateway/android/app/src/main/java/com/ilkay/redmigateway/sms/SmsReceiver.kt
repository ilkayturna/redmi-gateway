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

            val fullMessage = reassembleSmsParts(messages.map { it.displayMessageBody.orEmpty() })

            Log.i(TAG, "SMS received (${fullMessage.length} characters)")
            CallManager.log("SMS received (${fullMessage.length} characters)")

            // The durable journal mirrors the SMS provider; this also covers messages no SMS
            // app stores. SmsStore stays only for protocol 1 iPhone builds.
            com.ilkay.redmigateway.journal.SmsJournal.get(context).noteReceived(sender, fullMessage, timestamp)
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

/**
 * Joins the parts of a text message back into one string.
 *
 * A message longer than one hundred and sixty characters arrives as several parts, and this is
 * where they are put back together. Two things about it matter and both would be silent. The
 * parts must stay in the order they arrived, and nothing may be inserted between them: a space or
 * a newline added here would corrupt every long message, and the result goes both into the
 * journal and out to the iPhone.
 *
 * A part may legitimately be empty, so an empty list and a list of empty strings are both handled
 * without special casing.
 */
internal fun reassembleSmsParts(parts: List<String>): String = parts.joinToString("")
