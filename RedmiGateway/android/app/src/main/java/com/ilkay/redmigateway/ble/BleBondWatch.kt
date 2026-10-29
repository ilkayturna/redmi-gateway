package com.ilkay.redmigateway.ble

/**
 * Tells a Bluetooth link the iPhone drops at once from one that works.
 *
 * From 25.10.2026 23:38 the iPhone connected ten times every half hour and each link ended about
 * 140 ms later: the iPhone still held a pairing for this Redmi and asked to encrypt with it, the
 * Redmi had no such key (`btm_ble_ltk_request_reply: key size=0`, the iPhone's address could not be
 * resolved) and iOS hung up. Neither app said so for four days. Incoming calls fell back to the
 * beacon and push, and the Redmi woke the iPhone every five minutes. Only the iPhone can drop that
 * pairing, so the useful thing here is to notice and say so.
 *
 * A link counts as failed when it ends within [quickMs] without the iPhone reaching the GATT
 * server (no MTU exchange, no subscription). [failures] of those within [windowMs] make the stale
 * pairing suspected; a working link clears it. Not thread-safe on purpose: the GATT callbacks call
 * it under the owner's lock.
 */
internal class BleBondWatch(
    private val failures: Int = 5,
    private val windowMs: Long = 60_000L,
    private val quickMs: Long = 500L,
) {
    private val recent = ArrayDeque<Long>()

    var suspected = false
        private set

    /**
     * A link ended [heldMs] after it connected (null when its start was not seen). Returns true
     * only when this makes the stale pairing suspected, so the caller logs the change once.
     */
    fun linkEnded(now: Long, heldMs: Long?, reachedGatt: Boolean): Boolean {
        if (heldMs == null || reachedGatt || heldMs > quickMs) return false
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > windowMs) recent.removeFirst()
        if (suspected || recent.size < failures) return false
        suspected = true
        return true
    }

    /** The iPhone subscribed to the call state. Returns true only when this clears a suspicion. */
    fun linkWorks(): Boolean {
        recent.clear()
        if (!suspected) return false
        suspected = false
        return true
    }
}
