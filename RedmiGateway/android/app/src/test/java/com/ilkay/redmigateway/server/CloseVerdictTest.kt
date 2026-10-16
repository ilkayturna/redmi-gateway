package com.ilkay.redmigateway.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins how a closed gateway connection is described.
 *
 * This exists because of what the device showed on 16.10.2026: the real iPhone reconnected five
 * times in forty six seconds and the only thing logged was that it had connected each time. With
 * no close line at all, a network change, a keepalive timeout, the iPhone going to the background
 * and the server dropping the socket are indistinguishable, so the user's complaint that the app
 * says the iPhone is not connected could not be traced to anything.
 *
 * The classification is the part worth testing, because getting it wrong is worse than having
 * nothing: it would send the next person looking in the wrong layer with confidence.
 */
class CloseVerdictTest {

    @Test
    fun aConnectionThatNeverAuthenticatedIsNamedAsSuch() {
        assertEquals(
            "auth_never_completed",
            classifyClose(authenticated = false, code = 1006, remote = true, sinceLastMessageMs = 10)
        )
    }

    @Test
    fun neverAuthenticatedWinsOverEveryTimingSignal() {
        // Even a pristine close code must not hide that authentication never happened.
        assertEquals(
            "auth_never_completed",
            classifyClose(authenticated = false, code = 1000, remote = true, sinceLastMessageMs = 60_000)
        )
    }

    @Test
    fun aCleanRemoteCloseIsADeliberateReconnect() {
        assertEquals(
            "remote_clean",
            classifyClose(authenticated = true, code = 1000, remote = true, sinceLastMessageMs = 500)
        )
    }

    @Test
    fun aLocalCloseWithCode1000IsNotCountedAsRemote() {
        // This side closed it, so it is not the iPhone reconnecting on purpose.
        assertEquals(
            "dropped_while_active",
            classifyClose(authenticated = true, code = 1000, remote = false, sinceLastMessageMs = 500)
        )
    }

    @Test
    fun goingSilentMidConversationIsADrop() {
        assertEquals(
            "dropped_while_active",
            classifyClose(authenticated = true, code = 1006, remote = true, sinceLastMessageMs = 0)
        )
        assertEquals(
            "dropped_while_active",
            classifyClose(authenticated = true, code = 1006, remote = true, sinceLastMessageMs = 2_999)
        )
    }

    @Test
    fun aLongSilenceBeforeClosingPointsAtAKeepalive() {
        assertEquals(
            "idle_then_closed",
            classifyClose(authenticated = true, code = 1006, remote = true, sinceLastMessageMs = 30_001)
        )
    }

    @Test
    fun theBoundariesLandWhereTheCommentsSayTheyDo() {
        // Exactly 3 seconds is no longer "mid conversation", exactly 30 is not yet "idle".
        assertEquals(
            "closed_quiet",
            classifyClose(authenticated = true, code = 1006, remote = true, sinceLastMessageMs = 3_000)
        )
        assertEquals(
            "closed_quiet",
            classifyClose(authenticated = true, code = 1006, remote = true, sinceLastMessageMs = 30_000)
        )
    }

    @Test
    fun everyBranchIsReachable() {
        val seen = mutableSetOf<String>()
        for (auth in listOf(true, false)) {
            for (code in listOf(1000, 1006, 1011)) {
                for (remote in listOf(true, false)) {
                    for (gap in listOf(0L, 1_500L, 10_000L, 45_000L)) {
                        seen += classifyClose(auth, code, remote, gap)
                    }
                }
            }
        }
        assertEquals(
            "all five readings should come out of the matrix",
            setOf("auth_never_completed", "remote_clean", "dropped_while_active", "idle_then_closed", "closed_quiet"),
            seen
        )
    }
}
