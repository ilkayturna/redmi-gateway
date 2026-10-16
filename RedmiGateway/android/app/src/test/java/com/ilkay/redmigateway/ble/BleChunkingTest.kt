package com.ilkay.redmigateway.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleChunkingTest {
    private fun frame(size: Int) = ByteArray(size) { (it and 0xFF).toByte() }

    // Catches a chunk larger than the notification: the receiver would drop the whole frame.
    @Test fun everyChunkFitsTheRoom() {
        val chunks = BleChunking.split(frame(110), room = 20)!!
        assertTrue(chunks.all { it.size <= 20 })
        assertEquals(7, chunks.size) // six full chunks of 18 plus two bytes
    }

    // Catches the payload being reordered or lost on the way.
    @Test fun theChunksCarryTheFrameInOrder() {
        val original = frame(110)
        val chunks = BleChunking.split(original, room = 20)!!
        val rebuilt = ByteArray(110)
        var offset = 0
        for (chunk in chunks) {
            val payload = chunk.size - BleChunking.HEADER_BYTES
            System.arraycopy(chunk, BleChunking.HEADER_BYTES, rebuilt, offset, payload)
            offset += payload
        }
        assertArrayEquals(original, rebuilt)
        assertEquals(110, offset)
    }

    // Catches a frame whose length is an exact multiple losing its end marker: the receiver would
    // wait for the timeout instead of completing the frame.
    @Test fun anExactMultipleEndsWithAnEmptyFinalChunk() {
        val chunks = BleChunking.split(frame(36), room = 20)!!
        assertEquals(3, chunks.size)
        assertEquals(18, chunks[0].size - BleChunking.HEADER_BYTES)
        assertEquals(18, chunks[1].size - BleChunking.HEADER_BYTES)
        assertEquals(BleChunking.HEADER_BYTES, chunks[2].size)
        assertEquals(BleChunking.FINAL_FLAG, chunks[2][1].toInt() and 0xFF)
    }

    // Catches the final flag landing on more than one chunk, or on the wrong one.
    @Test fun onlyTheLastChunkIsMarkedFinal() {
        val chunks = BleChunking.split(frame(40), room = 20)!!
        chunks.dropLast(1).forEach { assertEquals(0, it[1].toInt() and BleChunking.FINAL_FLAG) }
        assertEquals(BleChunking.FINAL_FLAG, chunks.last()[1].toInt() and 0xFF)
        assertEquals(0, chunks.last()[0].toInt() and 0xFF)
    }

    // Catches a sequence number that does not start at zero or does not advance per chunk.
    @Test fun theSequenceNumberStartsAtZeroAndAdvances() {
        val chunks = BleChunking.split(frame(110), room = 20)!!
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), chunks.map { it[0].toInt() and 0xFF })
    }

    // Catches a room too small to carry a header being used anyway.
    @Test fun aRoomWithoutHeaderSpaceCannotSplit() {
        assertNull(BleChunking.split(frame(110), room = 2))
        assertNull(BleChunking.split(frame(110), room = 0))
    }

    // Catches a frame that already fits being split: it must travel whole, without a header, so an
    // older iPhone that never heard of chunking still reads it.
    @Test fun theCallerSendsASmallFrameWholeButSplitStillCarriesIt() {
        val chunks = BleChunking.split(frame(10), room = 20)!!
        assertEquals(1, chunks.size)
        assertEquals(10, chunks[0].size - BleChunking.HEADER_BYTES)
        assertEquals(BleChunking.FINAL_FLAG, chunks[0][1].toInt() and 0xFF)
    }
}
