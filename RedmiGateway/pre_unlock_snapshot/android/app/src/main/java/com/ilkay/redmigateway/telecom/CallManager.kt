package com.ilkay.redmigateway.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object CallManager {
    private const val TAG = "CallManager"

    interface CallListener {
        fun onCallStateChanged(callId: String, state: String, number: String?)
        fun onLogMessage(message: String)
    }

    private val listeners = CopyOnWriteArrayList<CallListener>()

    var activeCall: Call? = null
        private set
    var currentCallId: String? = null
        private set
    var currentCallState: String = "idle"
        private set
    var currentPhoneNumber: String? = null
        private set

    fun addListener(listener: CallListener) {
        listeners.add(listener)
        // Immediately notify current state
        listener.onCallStateChanged(currentCallId ?: "none", currentCallState, currentPhoneNumber)
    }

    fun removeListener(listener: CallListener) {
        listeners.remove(listener)
    }

    fun log(msg: String) {
        Log.d(TAG, msg)
        for (l in listeners) {
            try {
                l.onLogMessage(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Error in log listener", e)
            }
        }
    }

    fun onCallAdded(call: Call) {
        activeCall = call
        currentCallId = UUID.randomUUID().toString()
        currentPhoneNumber = extractPhoneNumber(call)
        val stateStr = mapCallState(call.state)
        currentCallState = stateStr

        log("Call Added: ID=$currentCallId, Number=$currentPhoneNumber, State=$stateStr")
        notifyStateChanged()

        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                val mapped = mapCallState(state)
                currentCallState = mapped
                val num = extractPhoneNumber(c)
                if (!num.isNullOrBlank() && currentPhoneNumber.isNullOrBlank()) {
                    currentPhoneNumber = num
                }
                log("Call State Changed: ID=$currentCallId, State=$mapped, Number=$currentPhoneNumber")
                notifyStateChanged()
            }

            override fun onDetailsChanged(c: Call, details: Call.Details) {
                val num = extractPhoneNumber(c)
                if (!num.isNullOrBlank() && num != currentPhoneNumber) {
                    currentPhoneNumber = num
                    log("Call Details Changed: Number=$num")
                    notifyStateChanged()
                }
            }
        })
    }

    fun onCallRemoved(call: Call) {
        if (activeCall == call || activeCall == null) {
            val finalId = currentCallId ?: UUID.randomUUID().toString()
            log("Call Removed / Terminated: ID=$finalId")
            currentCallState = "disconnected"
            notifyStateChanged()

            // Reset after informing listeners
            activeCall = null
            currentCallState = "idle"
            currentCallId = null
            currentPhoneNumber = null
            notifyStateChanged()
        }
    }

    private fun notifyStateChanged() {
        val id = currentCallId ?: "none"
        val state = currentCallState
        val num = currentPhoneNumber
        for (l in listeners) {
            try {
                l.onCallStateChanged(id, state, num)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }

    fun answer(): Boolean {
        val call = activeCall
        if (call != null) {
            log("Executing ANSWER on call: $currentCallId")
            return try {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                true
            } catch (e: Exception) {
                log("Error answering call: ${e.message}")
                false
            }
        } else {
            log("Cannot answer: No active call")
            return false
        }
    }

    fun reject(): Boolean {
        val call = activeCall
        if (call != null) {
            log("Executing REJECT on call: $currentCallId")
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    call.reject(Call.REJECT_REASON_DECLINED)
                } else {
                    @Suppress("DEPRECATION")
                    call.reject(false, null)
                }
                true
            } catch (e: Exception) {
                log("Error rejecting call: ${e.message}")
                false
            }
        } else {
            log("Cannot reject: No active call")
            return false
        }
    }

    fun hangup(): Boolean {
        val call = activeCall
        if (call != null) {
            log("Executing HANGUP / DISCONNECT on call: $currentCallId")
            return try {
                call.disconnect()
                true
            } catch (e: Exception) {
                log("Error hanging up call: ${e.message}")
                false
            }
        } else {
            log("Cannot hangup: No active call")
            return false
        }
    }

    fun dial(context: Context, number: String): Boolean {
        val cleanNumber = number.trim()
        if (cleanNumber.isBlank()) {
            log("Cannot dial: Blank number")
            return false
        }
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecomManager == null) {
            log("Cannot dial: TelecomManager unavailable")
            return false
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            log("Cannot dial: CALL_PHONE permission not granted")
            return false
        }

        log("Initiating outgoing GSM call to: $cleanNumber")
        return try {
            val uri = Uri.fromParts("tel", cleanNumber, null)
            val extras = Bundle()
            telecomManager.placeCall(uri, extras)
            true
        } catch (e: Exception) {
            log("Exception in placeCall: ${e.message}")
            false
        }
    }

    private fun extractPhoneNumber(call: Call): String? {
        val handle = call.details?.handle
        if (handle != null) {
            val schemeSpecific = handle.schemeSpecificPart
            if (!schemeSpecific.isNullOrBlank()) {
                return schemeSpecific
            }
        }
        val gatewayAddress = call.details?.gatewayInfo?.originalAddress
        if (gatewayAddress != null) {
            val s = gatewayAddress.schemeSpecificPart
            if (!s.isNullOrBlank()) {
                return s
            }
        }
        val callerName = call.details?.callerDisplayName
        if (!callerName.isNullOrBlank()) {
            return callerName
        }
        return null
    }

    private fun mapCallState(state: Int): String {
        return when (state) {
            Call.STATE_RINGING -> "ringing"
            Call.STATE_DIALING -> "dialing"
            Call.STATE_CONNECTING -> "connecting"
            Call.STATE_ACTIVE -> "active"
            Call.STATE_HOLDING -> "holding"
            Call.STATE_DISCONNECTED -> "disconnected"
            Call.STATE_DISCONNECTING -> "disconnecting"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "selecting_account"
            else -> "idle"
        }
    }
}
