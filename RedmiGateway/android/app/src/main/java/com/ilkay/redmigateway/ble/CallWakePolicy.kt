package com.ilkay.redmigateway.ble

/**
 * When the Redmi keeps asking a sleeping iPhone to wake up.
 *
 * The iPhone only learns about a call through the region beacon while its app is suspended, and
 * iOS can take far longer than half a minute to deliver that region entry. A measured outgoing call
 * lit the beacon for 30 seconds and the iPhone connected 58 seconds in - by then the beacon was
 * long dark and the user had already given up. The call itself is the real bound: the beacon only
 * burns while the call is live and no control socket exists, and it goes out the moment the iPhone
 * answers. [WAKE_MAX_MS] is a guard against a stuck call state, not a working window.
 */
internal object CallWakePolicy {
    /** Ten minutes: longer than any call that is still worth waking for, short enough to bound. */
    const val WAKE_MAX_MS = 10 * 60_000L

    /** An outgoing call with no control socket keeps the beacon lit for as long as it lasts. */
    fun outgoingAsksForWake(
        live: Boolean,
        hasControlLink: Boolean,
        addedAtMillis: Long,
        nowMillis: Long
    ): Boolean = live &&
        !hasControlLink &&
        addedAtMillis > 0 &&
        nowMillis - addedAtMillis < WAKE_MAX_MS
}
