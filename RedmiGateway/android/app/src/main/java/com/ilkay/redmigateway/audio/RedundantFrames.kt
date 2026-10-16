package com.ilkay.redmigateway.audio

/**
 * Whether the copy inside a redundant packet is a frame the receiver never played.
 *
 * A redundant packet carries [previous frame][current frame], and the sender omits the copy after
 * a keepalive (a keepalive carries no frame, so its counter gap would otherwise look like a lost
 * frame). That leaves one rule: the copy is new exactly when the packet before this one carried a
 * frame that never arrived, which is a counter gap of more than one.
 *
 * Kept free of Android types so the rule is unit tested.
 */
internal object RedundantFrames {
    fun copyIsNew(counter: Long, lastPcmCounter: Long): Boolean = counter > lastPcmCounter + 1
}
