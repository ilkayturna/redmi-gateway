package com.ilkay.redmigateway.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedundantFramesTest {
    // Catches playing the copy twice: the previous packet arrived, so its frame already played.
    @Test fun consecutivePacketsMakeTheCopyRedundant() {
        assertFalse(RedundantFrames.copyIsNew(counter = 7, lastPcmCounter = 6))
    }

    // Catches dropping the one case the copy exists for: the previous packet never arrived.
    @Test fun aLostPreviousPacketMakesTheCopyNew() {
        assertTrue(RedundantFrames.copyIsNew(counter = 7, lastPcmCounter = 5))
        assertTrue(RedundantFrames.copyIsNew(counter = 9, lastPcmCounter = 5))
    }

    // Catches treating the very first packet as a repeat of a frame that never existed.
    @Test fun theFirstPacketHasNothingToRepeat() {
        assertFalse(RedundantFrames.copyIsNew(counter = 1, lastPcmCounter = 0))
    }

    // Catches an older or replayed packet being accepted after the newer one arrived.
    @Test fun anOutOfOrderPacketIsNeverNew() {
        assertFalse(RedundantFrames.copyIsNew(counter = 6, lastPcmCounter = 9))
    }
}
