package com.ilkay.redmigateway.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

object SmsManagerHelper {
    private const val TAG = "SmsManagerHelper"

    fun sendSms(context: Context, recipient: String, message: String): Pair<Boolean, String?> {
        if (!recipient.matches(Regex("^\\+?[0-9]{3,15}$"))) {
            return Pair(false, "Invalid recipient number")
        }
        if (message.isBlank() || message.length > 480) {
            return Pair(false, "SMS must contain 1 to 480 characters")
        }
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
            Log.i(TAG, "SMS send request accepted (${message.length} chars)")
            Pair(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
            Pair(false, e.message ?: "Unknown SMS sending error")
        }
    }
}
