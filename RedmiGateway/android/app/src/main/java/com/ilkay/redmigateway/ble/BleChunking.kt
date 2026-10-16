package com.ilkay.redmigateway.ble

/**
 * Splits a sealed state frame into ATT notification sized chunks, in the format the iPhone
 * reassembles (agreed with the iOS side):
 *
 *   chunk = [seq:1][flags:1][payload:0..N]   total <= room
 *   flags bit 0 set on the final chunk
 *   seq starts at 0 for every frame and increments per chunk (mod 256)
 *
 * A frame that fits in one notification is sent whole, with no header at all, so an iPhone build
 * that does not know about chunking still reads it. That is why the split only covers the frames
 * that do not fit.
 */
internal object BleChunking {
    const val HEADER_BYTES = 2
    const val FINAL_FLAG = 0x01
    /** The smallest room worth splitting into: one byte of payload plus the header. */
    const val MIN_ROOM = HEADER_BYTES + 1
    /** The largest room worth accepting from the peer: one ATT notification cannot exceed this. */
    const val MAX_ROOM = 512
    /**
     * The receiver tells a chunk from a whole frame by its length alone - a sealed frame is at
     * least 29 bytes, a chunk at most this - so a chunk never grows past it, even on a link that
     * allows more. Without this a mid sized MTU (21..109) would produce chunks the receiver reads
     * as whole frames, and the state frame would silently never be reassembled.
     */
    const val MAX_CHUNK_BYTES = 20

    /**
     * The chunks for [frame], or null when [room] cannot even hold a header. An exact multiple of
     * the payload size ends with an empty final chunk, which is how the receiver knows the frame is
     * complete without inferring it from the size.
     */
    fun split(frame: ByteArray, room: Int): List<ByteArray>? {
        if (room < MIN_ROOM) return null
        val payloadPerChunk = minOf(room, MAX_CHUNK_BYTES) - HEADER_BYTES
        val chunks = ArrayList<ByteArray>(frame.size / payloadPerChunk + 1)
        var offset = 0
        var seq = 0
        while (offset < frame.size) {
            val length = minOf(payloadPerChunk, frame.size - offset)
            val chunk = ByteArray(HEADER_BYTES + length)
            chunk[0] = seq.toByte()
            chunk[1] = 0
            System.arraycopy(frame, offset, chunk, HEADER_BYTES, length)
            chunks.add(chunk)
            offset += length
            seq = (seq + 1) and 0xFF
        }
        if (frame.isEmpty() || frame.size % payloadPerChunk == 0) {
            // An empty terminator: the receiver must not have to guess the end from the size.
            chunks.add(byteArrayOf(seq.toByte(), FINAL_FLAG.toByte()))
        } else {
            chunks[chunks.size - 1][1] = FINAL_FLAG.toByte()
        }
        return chunks
    }
}

