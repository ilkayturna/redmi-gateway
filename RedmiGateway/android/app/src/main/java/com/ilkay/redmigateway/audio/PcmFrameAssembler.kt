package com.ilkay.redmigateway.audio

/**
 * Rebuilds exact 20 ms PCM frames from an AudioRecord that returns short reads.
 *
 * The call-downlink extraction record is asked for 640 bytes (20 ms at 16 kHz mono), but the
 * Qualcomm HAL can return slightly less per read. The relay used to seal and send every read as
 * one packet; the iPhone treats every arriving packet as a full 20 ms playback buffer, so 52
 * short packets per second carried only about 1000 ms of audio while its reserve counted 52
 * frames. The 320 ms playback reserve ratcheted down at every network gap and underran every few
 * seconds (DOWNLINK_HEALTH underruns=3,6,9,12 in one 30 s call). Accumulating reads here keeps the
 * wire at exactly 50 full frames per second, so a "frame" means the same 20 ms on both ends.
 */
class PcmFrameAssembler(private val frameBytes: Int) {
    /** The last completed frame. Valid until the next call to [feed]. */
    val frame: ByteArray = ByteArray(frameBytes)

    /** Bytes of the next frame collected so far. */
    var filled: Int = 0
        private set

    /** Reads shorter than a whole frame, for RELAY_HEALTH: a high rate means the HAL is chopping. */
    var shortReads: Int = 0
        private set

    private val pending = ByteArray(frameBytes)

    fun reset() {
        filled = 0
    }

    /**
     * Copies up to [length] bytes from [source] and returns true when [frame] is complete.
     *
     * [length] must not exceed one frame: the send loop reads into a frame-sized buffer, so a
     * single read can finish at most one frame and leave at most a partial next frame.
     */
    fun feed(source: ByteArray, offset: Int, length: Int): Boolean {
        require(offset >= 0 && length >= 0 && offset + length <= source.size) {
            "read outside the source: offset=$offset length=$length size=${source.size}"
        }
        require(length <= frameBytes) { "a read cannot span more than one frame: $length" }
        if (length < frameBytes) shortReads += 1
        val copied = minOf(frameBytes - filled, length)
        System.arraycopy(source, offset, pending, filled, copied)
        filled += copied
        if (filled < frameBytes) return false
        System.arraycopy(pending, 0, frame, 0, frameBytes)
        filled = 0
        val leftover = length - copied
        if (leftover > 0) {
            System.arraycopy(source, offset + copied, pending, 0, leftover)
            filled = leftover
        }
        return true
    }
}
