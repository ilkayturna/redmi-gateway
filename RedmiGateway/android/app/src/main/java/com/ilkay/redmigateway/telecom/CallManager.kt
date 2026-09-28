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

    @Volatile var activeCall: Call? = null
        private set
    @Volatile var currentCallId: String? = null
        private set
    @Volatile var currentCallState: String = "idle"
        private set
    @Volatile var currentPhoneNumber: String? = null
        private set
    /** "incoming" or "outgoing" for the current call, fixed when Telecom adds it. */
    @Volatile var currentDirection: String = "outgoing"
        private set
    /** Wall-clock time the current call became active, or 0. */
    @Volatile var activeSinceMillis: Long = 0L
        private set
    /** Wall-clock creation time of the current call; the CallLog entry carries the same time. */
    @Volatile var addedAtMillis: Long = 0L
        private set
    private var activeCallCallback: Call.Callback? = null

    /**
     * Call waiting: a second incoming call while one is in progress. It is tracked apart from the
     * current call, so every existing listener (audio relay, Bluetooth, notifications) keeps
     * seeing only the current call. It can be declined, or taken by ending the current call; it
     * then becomes the current call under the same id, so the iPhone's CallKit call continues.
     */
    interface WaitingListener {
        fun onWaitingCallChanged(callId: String?, number: String?)
    }

    @Volatile var waitingCallId: String? = null
        private set
    @Volatile var waitingNumber: String? = null
        private set
    private var waitingCall: Call? = null
    private var waitingCallback: Call.Callback? = null
    private var answerWaitingWhenFree = false
    private val waitingListeners = CopyOnWriteArrayList<WaitingListener>()

    fun addWaitingListener(listener: WaitingListener) {
        waitingListeners.addIfAbsent(listener)
        listener.onWaitingCallChanged(waitingCallId, waitingNumber)
    }

    fun removeWaitingListener(listener: WaitingListener) {
        waitingListeners.remove(listener)
    }

    private fun notifyWaitingChanged() {
        val id = waitingCallId
        val number = waitingNumber
        for (l in waitingListeners) runCatching { l.onWaitingCallChanged(id, number) }
    }

    private fun setWaiting(call: Call) {
        waitingCall = call
        waitingCallId = UUID.randomUUID().toString()
        waitingNumber = extractPhoneNumber(call)
        answerWaitingWhenFree = false
        val callback = object : Call.Callback() {
            override fun onDetailsChanged(c: Call, details: Call.Details) {
                if (waitingCall !== c) return
                val num = extractPhoneNumber(c)
                if (!num.isNullOrBlank() && num != waitingNumber) {
                    waitingNumber = num
                    notifyWaitingChanged()
                }
            }
        }
        waitingCallback = callback
        call.registerCallback(callback)
        log("Call waiting: ID=$waitingCallId")
        notifyWaitingChanged()
    }

    /** Clears the waiting call; returns it and its id when it should become the current call. */
    private fun clearWaiting(): Pair<Call, String>? {
        val call = waitingCall ?: return null
        val id = waitingCallId ?: return null
        waitingCallback?.let { call.unregisterCallback(it) }
        waitingCallback = null
        waitingCall = null
        waitingCallId = null
        waitingNumber = null
        notifyWaitingChanged()
        return call to id
    }

    fun addListener(listener: CallListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
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
        onCallAdded(call, null)
    }

    private fun onCallAdded(call: Call, reuseId: String?) {
        if (activeCall != null && activeCall !== call) {
            if (waitingCall == null && call.state == Call.STATE_RINGING) {
                setWaiting(call)
            } else {
                log("Additional call ignored: a call is already waiting")
            }
            return
        }
        activeCall = call
        currentCallId = reuseId ?: UUID.randomUUID().toString()
        currentPhoneNumber = extractPhoneNumber(call)
        val stateStr = mapCallState(call.state)
        currentCallState = stateStr
        currentDirection = if (call.state == Call.STATE_RINGING ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                call.details?.callDirection == Call.Details.DIRECTION_INCOMING)) "incoming" else "outgoing"
        activeSinceMillis = if (stateStr == "active") System.currentTimeMillis() else 0L
        addedAtMillis = call.details?.creationTimeMillis?.takeIf { it > 0 } ?: System.currentTimeMillis()

        log("Call Added: ID=$currentCallId, State=$stateStr")
        notifyStateChanged()

        val callback = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                if (activeCall !== c) return
                val mapped = mapCallState(state)
                currentCallState = mapped
                if (mapped == "active" && activeSinceMillis == 0L) activeSinceMillis = System.currentTimeMillis()
                val num = extractPhoneNumber(c)
                if (!num.isNullOrBlank() && currentPhoneNumber.isNullOrBlank()) {
                    currentPhoneNumber = num
                }
                log("Call State Changed: ID=$currentCallId, State=$mapped")
                notifyStateChanged()
            }

            override fun onDetailsChanged(c: Call, details: Call.Details) {
                if (activeCall !== c) return
                val num = extractPhoneNumber(c)
                if (!num.isNullOrBlank() && num != currentPhoneNumber) {
                    currentPhoneNumber = num
                    log("Call Details Changed")
                    notifyStateChanged()
                }
            }
        }
        activeCallCallback = callback
        call.registerCallback(callback)
    }

    fun onCallRemoved(call: Call) {
        if (waitingCall === call) {
            log("Waiting call ended")
            clearWaiting()
            answerWaitingWhenFree = false
            return
        }
        if (activeCall === call) {
            activeCallCallback?.let { call.unregisterCallback(it) }
            activeCallCallback = null
            val finalId = currentCallId ?: return
            log("Call Removed / Terminated: ID=$finalId")
            currentCallState = "disconnected"
            notifyStateChanged()

            IncomingCallPresenter.onCallEnded(finalId)
            if (activeSinceMillis != 0L) {
                // Recents on both phones show which one carried the conversation.
                val device = if (com.ilkay.redmigateway.audio.CallAudioRelay.carriedCallId == finalId) "iphone" else "redmi"
                com.ilkay.redmigateway.journal.CallJournal.get(com.ilkay.redmigateway.RedmiGatewayApp.instance)
                    .noteAnsweredOn(addedAtMillis, device)
            }
            // Reset after informing listeners
            activeCall = null
            currentCallState = "idle"
            currentCallId = null
            currentPhoneNumber = null
            activeSinceMillis = 0L
            addedAtMillis = 0L
            notifyStateChanged()
            promoteWaiting()
        }
    }

    /** The current call ended while another was waiting: the waiting call becomes current. */
    private fun promoteWaiting() {
        val (call, id) = clearWaiting() ?: return
        val answerNow = answerWaitingWhenFree
        answerWaitingWhenFree = false
        if (call.state == Call.STATE_DISCONNECTED) return
        log("Waiting call becomes the current call: ID=$id")
        onCallAdded(call, id)
        if (answerNow && call.state == Call.STATE_RINGING) answer(id)
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

    fun answer(expectedCallId: String? = null): Boolean {
        if (expectedCallId != null && expectedCallId == waitingCallId && waitingCall != null) {
            // "End & Accept": end the current call; the waiting one is answered once it is current.
            log("Answering the waiting call after ending the current one")
            answerWaitingWhenFree = true
            val current = activeCall
            if (current == null) {
                promoteWaiting()
            } else if (current.state != Call.STATE_DISCONNECTED && current.state != Call.STATE_DISCONNECTING) {
                runCatching { current.disconnect() }
            }
            return true
        }
        val call = activeCall
        if (call != null &&
            (expectedCallId == null || expectedCallId == currentCallId) &&
            call.state == Call.STATE_RINGING) {
            log("Executing ANSWER on call: $currentCallId")
            return try {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                true
            } catch (e: Exception) {
                log("Error answering call: ${e.message}")
                false
            }
        } else {
            log("Cannot answer: call ID or state does not match a ringing call")
            return false
        }
    }

    fun reject(expectedCallId: String? = null): Boolean {
        if (expectedCallId != null && expectedCallId == waitingCallId) return rejectWaiting()
        val call = activeCall
        if (call != null &&
            (expectedCallId == null || expectedCallId == currentCallId) &&
            call.state == Call.STATE_RINGING) {
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
            log("Cannot reject: call ID or state does not match a ringing call")
            return false
        }
    }

    fun rejectWaiting(): Boolean {
        val call = waitingCall ?: return false
        log("Rejecting the waiting call")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                call.reject(Call.REJECT_REASON_DECLINED)
            } else {
                @Suppress("DEPRECATION")
                call.reject(false, null)
            }
            true
        } catch (e: Exception) {
            log("Error rejecting the waiting call: ${e.message}")
            false
        }
    }

    fun hangup(expectedCallId: String? = null): Boolean {
        if (expectedCallId != null && expectedCallId == waitingCallId) return rejectWaiting()
        val call = activeCall
        val matchesCallId = expectedCallId == null ||
            expectedCallId == currentCallId ||
            (expectedCallId.startsWith("outgoing_") && call != null)
        if (call != null &&
            matchesCallId &&
            call.state != Call.STATE_DISCONNECTED &&
            call.state != Call.STATE_DISCONNECTING) {
            log("Executing HANGUP / DISCONNECT on call: $currentCallId (expected: $expectedCallId)")
            return try {
                call.disconnect()
                true
            } catch (e: Exception) {
                log("Error hanging up call: ${e.message}")
                false
            }
        } else {
            log("Cannot hangup: call ID or state does not match a current call")
            return false
        }
    }

    fun playDtmf(digit: Char): Boolean {
        val call = activeCall
        if (call != null && (currentCallState == "active" || currentCallState == "dialing" || currentCallState == "connecting")) {
            return try {
                call.playDtmfTone(digit)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { call.stopDtmfTone() }
                }, 200L)
                log("Played DTMF tone")
                true
            } catch (e: Exception) {
                log("Error playing DTMF tone: ${e.message}")
                false
            }
        } else {
            log("Cannot play DTMF: no active call")
            return false
        }
    }

    fun maskPhoneNumber(number: String?): String {
        if (number.isNullOrBlank()) return "Unknown"
        val clean = number.trim()
        if (clean.length <= 4) return "***"
        val prefixLen = (clean.length / 3).coerceIn(1, 3)
        val suffixLen = (clean.length / 3).coerceIn(1, 2)
        val prefix = clean.substring(0, prefixLen)
        val suffix = clean.substring(clean.length - suffixLen)
        return "$prefix***$suffix"
    }

    fun dial(context: Context, number: String): Boolean {
        // Contacts commonly format numbers with spaces, parentheses and hyphens.
        val cleanNumber = number.trim().replace(Regex("[\\s().-]"), "")
        if (!cleanNumber.matches(Regex("^[0-9*#+]{3,20}$"))) {
            log("Cannot dial: invalid number format")
            return false
        }
        if (activeCall != null) {
            log("Cannot dial: gateway is already handling a call")
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

        log("Initiating outgoing GSM call for: ${maskPhoneNumber(cleanNumber)}")
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
