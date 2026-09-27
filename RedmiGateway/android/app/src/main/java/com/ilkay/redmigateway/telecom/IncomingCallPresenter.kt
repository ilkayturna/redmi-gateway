package com.ilkay.redmigateway.telecom

import android.content.Context
import android.telecom.TelecomManager
import android.util.Log

/**
 * Decides where an incoming call rings.
 *
 * The Redmi rings as usual. Once the paired iPhone confirms that its CallKit incoming-call screen
 * is showing this call (ringing_ack over WebSocket or Bluetooth), the Redmi's ringer is silenced
 * so only the iPhone rings. Without that confirmation the Redmi keeps ringing, so a call is never
 * silent on both devices. The call itself stays answerable on the Redmi.
 */
object IncomingCallPresenter {
    private const val TAG = "IncomingCallPresenter"
    private const val PREFS = "redmi_gateway_prefs"
    private const val KEY_QUIET_WHEN_IPHONE = "quiet_ring_when_iphone"

    @Volatile var presentedOnIPhoneCallId: String? = null
        private set
    private var appContext: Context? = null
    /** The call screen redraws when the iPhone starts or stops showing the call. */
    @Volatile var onChanged: (() -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** User setting, default on: let only the iPhone ring when it confirms the call. */
    var quietWhenIPhoneRings: Boolean
        get() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(KEY_QUIET_WHEN_IPHONE, true) ?: true
        set(value) {
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.putBoolean(KEY_QUIET_WHEN_IPHONE, value)?.apply()
        }

    fun onIPhonePresented(callId: String) {
        if (CallManager.currentCallState != "ringing" || callId != CallManager.currentCallId) return
        presentedOnIPhoneCallId = callId
        CallManager.log("Incoming call is showing on the iPhone")
        onChanged?.invoke()
        if (!quietWhenIPhoneRings) return
        val context = appContext ?: return
        runCatching {
            context.getSystemService(TelecomManager::class.java)?.silenceRinger()
        }.onFailure { Log.w(TAG, "Could not silence the Redmi ringer: ${it.javaClass.simpleName}") }
    }

    fun onCallEnded() {
        presentedOnIPhoneCallId = null
        onChanged?.invoke()
    }
}
