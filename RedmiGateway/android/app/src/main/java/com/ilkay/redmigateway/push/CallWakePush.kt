package com.ilkay.redmigateway.push

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ilkay.redmigateway.service.GatewayService
import org.json.JSONObject

/**
 * Asks a sleeping iPhone to wake up for a live call.
 *
 * Two paths reach it. The region beacon is the fast one, but iOS alone decides when it scans, and
 * a measured call proved it can take most of a minute; a silent push is handed to the system and
 * wakes the app in the background (no banner, no CallKit screen), after which the app opens the
 * control socket within a couple of seconds. The push carries no number and no name, only that a
 * call is waiting, and a call never spends more than two of them.
 *
 * Nothing is sent while no call is live or while a control socket already exists, so the user's
 * data allowance is only touched when the call audio genuinely needs the iPhone.
 */
object CallWakePush {
    private const val TAG = "CallWakePush"
    private val liveStates = setOf("dialing", "connecting", "active", "ringing")
    private val throttle = WakePushThrottle()
    private val handler = Handler(Looper.getMainLooper())

    private val retry = Runnable { sendIfDue() }
    @Volatile private var pendingCallId: String? = null

    /** Silent pushes sent since the app started. */
    val sent: Int get() = throttle.total

    /** How long ago the last silent push went out, -1 when none has. */
    val lastPushAgoMs: Long
        get() = if (throttle.lastAt == 0L) -1L else SystemClock.elapsedRealtime() - throttle.lastAt

    /**
     * Called for every call state change. Sends the first silent push of the call when the iPhone
     * has no control socket, and schedules the single retry ten seconds later.
     */
    fun onCallState(callId: String, state: String, direction: String?) {
        handler.removeCallbacks(retry)
        if (callId.isBlank()) return
        if (state !in liveStates) {
            pendingCallId = null
            return
        }
        if (GatewayService.authenticatedClientCount() > 0) {
            // The iPhone is already reachable: the beacon and the socket are enough.
            pendingCallId = null
            return
        }
        pendingCallId = callId
        sendIfDue()
        handler.postDelayed(retry, WakePushThrottle.RETRY_AFTER_MS)
    }

    /** The control socket opened, so nothing needs waking any more. */
    fun onControlLinkUp() {
        handler.removeCallbacks(retry)
        pendingCallId = null
    }

    private fun sendIfDue() {
        val callId = pendingCallId ?: return
        if (GatewayService.authenticatedClientCount() > 0) {
            pendingCallId = null
            return
        }
        if (!throttle.onTick(callId, SystemClock.elapsedRealtime())) return
        // Only what the app needs to react: no number, no name.
        val payload = JSONObject().put("a", "wake").put("callId", callId.take(64))
        val started = ApnsSender.sendBackground(payload) { problem ->
            if (problem != null) Log.w(TAG, "Silent wake push: $problem")
        }
        if (started) {
            Log.i(TAG, "Silent wake push sent (${throttle.total} this session)")
        } else {
            // The iPhone registers the alert token when the app is opened; without it the beacon
            // is the only way through, which is why the beacon window was widened.
            Log.w(TAG, "Silent wake push not sent: the iPhone has registered no alert token yet")
        }
    }
}
