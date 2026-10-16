package com.ilkay.redmigateway.push

/**
 * How often a call may ask a sleeping iPhone to wake up through a silent push.
 *
 * The beacon is the fast path but iOS decides when it scans for a region, while a silent push is
 * delivered by the system. Neither is free: a push spends the user's data allowance and the
 * iPhone's battery, so a call gets one push when it starts and one retry, and nothing at all while
 * a control socket already exists. Kept free of Android types so the rule is unit tested.
 */
internal class WakePushThrottle(
    private val maxPerCall: Int = MAX_PER_CALL,
    private val retryAfterMs: Long = RETRY_AFTER_MS
) {
    companion object {
        /** One at the start of the call, one retry if the link is still missing. */
        const val MAX_PER_CALL = 2
        const val RETRY_AFTER_MS = 10_000L
    }

    private var callId: String? = null
    private var sentForCall = 0
    private var sentTotal = 0
    private var lastSentAt = 0L

    /** Pushes sent since the app started, for diagnostics. */
    val total: Int get() = sentTotal
    /** When the last push went out, 0 when none has. */
    val lastAt: Long get() = lastSentAt

    /**
     * Whether a silent push should go out for [callId] now. A different call always starts a fresh
     * budget, so a long conversation cannot inherit an old one, and a brief reconnect does not
     * reset it either - the budget belongs to the call, not to the socket.
     */
    fun onTick(callId: String, now: Long): Boolean {
        if (callId != this.callId) {
            this.callId = callId
            sentForCall = 0
            lastSentAt = 0L
        }
        if (sentForCall >= maxPerCall) return false
        if (sentForCall > 0 && now - lastSentAt < retryAfterMs) return false
        sentForCall++
        sentTotal++
        lastSentAt = now
        return true
    }
}
