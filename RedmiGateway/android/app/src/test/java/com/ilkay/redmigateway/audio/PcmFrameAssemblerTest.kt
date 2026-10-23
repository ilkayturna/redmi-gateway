package com.ilkay.redmigateway.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmFrameAssemblerTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `short reads are joined into one full frame`() {
        val assembler = PcmFrameAssembler(8)
        val first = bytes(1, 2, 3, 4, 5)
        assertFalse(assembler.feed(first, 0, first.size))
        assertEquals(5, assembler.filled)
        assertEquals(1, assembler.shortReads)

        val second = bytes(6, 7, 8)
        assertTrue(assembler.feed(second, 0, second.size))
        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6, 7, 8), assembler.frame)
        assertEquals(0, assembler.filled)
    }

    @Test
    fun `short reads never make a frame longer than its size`() {
        val assembler = PcmFrameAssembler(640)
        repeat(51) {
            val chunk = ByteArray(12) { index -> (it * 12 + index).toByte() }
            assertFalse(assembler.feed(chunk, 0, chunk.size))
        }
        val tail = ByteArray(28) { index -> (51 * 12 + index).toByte() }
        assertTrue(assembler.feed(tail, 0, tail.size))
        assertEquals(640, assembler.frame.size)
        assertEquals(0, assembler.filled)
        assertEquals(52, assembler.shortReads)
    }

    @Test
    fun `a whole read does not count as a short read`() {
        val assembler = PcmFrameAssembler(4)
        assertTrue(assembler.feed(bytes(9, 8, 7, 6), 0, 4))
        assertArrayEquals(bytes(9, 8, 7, 6), assembler.frame)
        assertEquals(0, assembler.shortReads)
    }

    @Test
    fun `leftover bytes start the next frame`() {
        val assembler = PcmFrameAssembler(6)
        assertFalse(assembler.feed(bytes(1, 2, 3, 4), 0, 4))
        assertTrue(assembler.feed(bytes(5, 6, 7, 8), 0, 4))
        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6), assembler.frame)
        assertEquals(2, assembler.filled)
        assertTrue(assembler.feed(bytes(9, 10, 11, 12), 0, 4))
        assertArrayEquals(bytes(7, 8, 9, 10, 11, 12), assembler.frame)
        assertEquals(0, assembler.filled)
    }

    @Test
    fun `reset drops a partial frame`() {
        val assembler = PcmFrameAssembler(8)
        assertFalse(assembler.feed(bytes(1, 2, 3), 0, 3))
        assembler.reset()
        assertEquals(0, assembler.filled)
        assertFalse(assembler.feed(bytes(4, 5, 6, 7), 0, 4))
        assertTrue(assembler.feed(bytes(8, 9, 10, 11), 0, 4))
        assertArrayEquals(bytes(4, 5, 6, 7, 8, 9, 10, 11), assembler.frame)
    }
}
