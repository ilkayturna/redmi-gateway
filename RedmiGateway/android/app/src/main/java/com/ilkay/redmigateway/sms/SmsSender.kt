package com.ilkay.redmigateway.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ilkay.redmigateway.journal.SmsJournal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends SMS through the Redmi SIM and reports the radio's result, not just acceptance.
 * Multipart (including Turkish/Unicode text) is split by the platform; the send succeeds only
 * when every part is sent.
 */
object SmsSender {
    private const val TAG = "SmsSender"
    private const val ACTION_SENT = "com.ilkay.redmigateway.SMS_SENT"
    const val MAX_LENGTH = 1600

    fun interface Result {
        fun onResult(success: Boolean, error: String?)
    }

    private class Pending(val parts: Int, val callback: Result) {
        val done = AtomicInteger()
        @Volatile var failed: String? = null
    }

    private val pending = ConcurrentHashMap<Int, Pending>()
    private val nextId = AtomicInteger((System.currentTimeMillis() % 100_000).toInt())
    @Volatile private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra("requestId", -1)
            val entry = pending[id] ?: return
            if (resultCode != Activity.RESULT_OK) {
                entry.failed = when (resultCode) {
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "no_service"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "invalid_message"
                    else -> "send_failed_$resultCode"
                }
            }
            if (entry.done.incrementAndGet() >= entry.parts) {
                pending.remove(id)
                entry.callback.onResult(entry.failed == null, entry.failed)
                SmsJournal.get(context).scheduleImport(500L)
            }
        }
    }

    fun normalizeRecipient(raw: String): String = raw.trim().replace(Regex("[\\s().-]"), "")

    /** Returns an error code immediately for invalid input; otherwise reports through [callback]. */
    fun send(context: Context, recipientRaw: String, message: String, callback: Result): String? {
        val recipient = normalizeRecipient(recipientRaw)
        smsSendProblem(recipient, message)?.let { return it }
        ensureRegistered(context.applicationContext)
        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            val requestId = nextId.incrementAndGet()
            pending[requestId] = Pending(parts.size, callback)
            val intents = ArrayList<PendingIntent>(parts.size)
            for (index in parts.indices) {
                val intent = Intent(ACTION_SENT).setPackage(context.packageName).putExtra("requestId", requestId)
                intents.add(PendingIntent.getBroadcast(
                    context, requestId * 64 + index, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
            }
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(recipient, null, parts, intents, null)
            } else {
                smsManager.sendTextMessage(recipient, null, message, intents[0], null)
            }
            Log.i(TAG, "SMS queued (${parts.size} part(s))")
            null
        } catch (error: Exception) {
            Log.w(TAG, "SMS send failed: ${error.javaClass.simpleName}")
            "send_failed"
        }
    }

    private fun ensureRegistered(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }
}


/**
 * Why this message cannot be sent, or null when it can.
 *
 * The recipient is checked after normalising, so the punctuation an iPhone keyboard produces does
 * not count against it. The shape allowed is an optional plus followed by three to fifteen digits,
 * which is the E.164 limit: a shorter run is not a number, and a longer one cannot be dialled.
 *
 * The length cap is ten ordinary SMS parts. Sending is a paid action on the user's SIM, so the
 * cheap checks happen before anything is handed to the system.
 *
 * @param recipient already normalised by [SmsSender.normalizeRecipient]
 * @param message the text as typed
 */
internal fun smsSendProblem(recipient: String, message: String): String? = when {
    !recipient.matches(Regex("^\\+?[0-9]{3,15}$")) -> "invalid_recipient"
    message.isBlank() -> "empty_message"
    message.length > SmsSender.MAX_LENGTH -> "message_too_long"
    else -> null
}