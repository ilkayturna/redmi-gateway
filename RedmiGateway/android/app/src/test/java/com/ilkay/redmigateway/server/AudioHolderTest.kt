package com.ilkay.redmigateway.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins who may take the call audio from a socket that still looks open.
 *
 * The iPhone drops a stalled socket after 3 s (build 170) and reconnects; the Redmi keeps seeing
 * the old one as open until its keepalive gives up. Refusing the new socket there left the
 * conversation on the Redmi; letting an old socket win would hand the audio to a dead connection.
 */
class AudioHolderTest {

    @Test
    fun aNewerSocketOfThePairedIphoneTakesTheAudio() {
        assertTrue(supersedesAudioHolder(requesterProtocol = 2, requesterAuthenticatedAt = 2_000, holderAuthenticatedAt = 1_000))
    }

    @Test
    fun anOlderSocketNeverTakesTheAudioFromANewerOne() {
        // A stale audio_start delivered late on the stalled connection.
        assertFalse(supersedesAudioHolder(requesterProtocol = 2, requesterAuthenticatedAt = 1_000, holderAuthenticatedAt = 2_000))
    }

    @Test
    fun theSameMomentIsNotNewer() {
        assertFalse(supersedesAudioHolder(requesterProtocol = 2, requesterAuthenticatedAt = 1_000, holderAuthenticatedAt = 1_000))
    }

    @Test
    fun aProtocolOneSocketCannotTakeAnOpenHoldersAudio() {
        assertFalse(supersedesAudioHolder(requesterProtocol = 1, requesterAuthenticatedAt = 2_000, holderAuthenticatedAt = 1_000))
    }

    @Test
    fun aHolderThatIsNoLongerKnownCanBeReplaced() {
        assertTrue(supersedesAudioHolder(requesterProtocol = 2, requesterAuthenticatedAt = 1_000, holderAuthenticatedAt = null))
    }
}
