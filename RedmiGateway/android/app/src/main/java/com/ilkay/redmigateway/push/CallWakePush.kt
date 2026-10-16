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
        // Deliberately does NOT reset the idle backoff. Measured on the device: the iPhone opens
        // the socket, authenticates, and iOS suspends it again about fifty milliseconds later, so
        // resetting here turned the idle wake into a push every fifteen seconds. The backoff is
        // only cleared once a socket has actually stayed up, which noteSocketHealthy does.
    }

    /**
     * Called once a control socket has stayed up long enough to count as recovered, so the idle
     * backoff can start from scratch again.
     */
    fun noteSocketHealthy() {
        if (socketWakeCount > 0) Log.i(TAG, "Socket stayed up after $socketWakeCount idle wake(s)")
        socketWakeCount = 0
        socketWakeNotBefore = 0L
    }

    // ---- Waking a phone that has a Bluetooth link but no socket ----
    //
    // Measured on the device on 16.10.2026: the iPhone held its Bluetooth link for minutes while
    // its control socket was gone, and nothing brought it back. A Bluetooth call-state nudge does
    // not work either, because iOS only opens the socket for a live call state, so an idle nudge
    // is read and discarded. The silent push does work: its handler calls wakeFromBeacon()
    // whatever the call state is, and then waits up to twelve seconds for authentication.
    //
    // This does change the data promise at the top of the file, so it is deliberately narrow.
    // It only runs when the socket is genuinely missing, it only runs when the phone is reachable
    // over Bluetooth (so this is a sleeping phone rather than one that is away), the body is the
    // same ninety odd bytes with no number and no name, and the wait grows each time: a minute,
    // then two, then five, then fifteen. A phone that stays asleep costs a couple of kilobytes an
    // hour, and a phone that wakes stops it immediately.
    private var socketWakeCount = 0
    private var socketWakeNotBefore = 0L

    /**
     * Ask a Bluetooth-reachable but socket-less iPhone to open its control socket.
     *
     * @return true when a push actually went out, so the caller can log what happened rather than
     *         assume it did.
     */
    fun wakeMissingSocket(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (GatewayService.authenticatedClientCount() > 0) return false
        if (now < socketWakeNotBefore) return false
        socketWakeCount++
        val payload = wakePayload("socket-$socketWakeCount")
        val started = ApnsSender.sendBackground(payload) { problem ->
            if (problem != null) Log.w(TAG, "Idle wake push: $problem")
        }
        val pause = idleWakePauseMs(socketWakeCount)
        socketWakeNotBefore = now + pause
        if (started) {
            Log.i(TAG, "Idle wake push sent (idle wake $socketWakeCount, next in ${pause / 1000}s)")
        } else {
            Log.w(TAG, "Idle wake push not sent: the iPhone has registered no alert token yet")
        }
        return started
    }

    /**
     * The silent push body. Only what the app needs to react: no number, no name.
     *
     * Extracted so it can be asserted on. The claim that this carries no personal data is part
     * of the data-protection promise to the user, and while the body was built inline nothing
     * stopped someone adding the caller's number to it and no test would have noticed.
     */
    internal fun wakePayload(callId: String): JSONObject =
        JSONObject().put("a", "wake").put("callId", callId.take(64))

    private fun sendIfDue() {
        val callId = pendingCallId ?: return
        if (GatewayService.authenticatedClientCount() > 0) {
            pendingCallId = null
            return
        }
        if (!throttle.onTick(callId, SystemClock.elapsedRealtime())) return
        val payload = wakePayload(callId)
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

/**
 * How long to wait before the next idle wake, given how many have already gone out.
 *
 * Kept out of the class so it can be asserted on directly, because the first version of this
 * cleared its backoff on every socket open instead. Measured on the device, the iPhone opens the
 * socket, authenticates, and iOS suspends it about fifty milliseconds later, so "the socket came
 * back" was true many times a minute and the idle wake became a push every fifteen seconds. The
 * schedule is the thing that has to stay long, so a test pins it rather than a comment.
 *
 * @param sent how many idle wakes have gone out since a socket last stayed up
 * @return milliseconds to wait before the next one
 */
internal fun idleWakePauseMs(sent: Int): Long = when {
    sent < 3 -> 60_000L
    sent < 6 -> 120_000L
    sent < 10 -> 300_000L
    else -> 900_000L
}
