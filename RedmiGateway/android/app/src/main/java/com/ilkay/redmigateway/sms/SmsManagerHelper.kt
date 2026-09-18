package com.ilkay.redmigateway.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

object SmsManagerHelper {
    private const val TAG = "SmsManagerHelper"

    fun sendSms(context: Context, recipient: String, message: String): Pair<Boolean, String?> {
        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            if (message.length > 160) {
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(recipient, null, message, null, null)
            }
            Log.i(TAG, "Sent SMS to $recipient (${message.length} chars)")
            Pair(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $recipient", e)
            Pair(false, e.message ?: "Unknown SMS sending error")
        }
    }
}
