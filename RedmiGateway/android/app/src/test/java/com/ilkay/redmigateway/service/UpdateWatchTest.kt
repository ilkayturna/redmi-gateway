package com.ilkay.redmigateway.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins when the app is allowed to say "the ROM changed under you".
 *
 * The first run after this feature ships has no previous build to compare with, and a fresh
 * install has none either; treating that as a change would greet every new user with an update
 * warning that never happened. Every later difference is a real ROM update — the vendor audio
 * fix and the priv-app install live in partitions an update replaces.
 */
class UpdateWatchTest {

    @Test
    fun theFirstRunIsNotAChange() {
        assertFalse(UpdateWatch.changed(previous = null, current = "4.4.1-a16-20261022695941-community-ginkgo"))
    }

    @Test
    fun anEmptyStoredBuildIsNotAChange() {
        assertFalse(UpdateWatch.changed(previous = "", current = "build-1"))
        assertFalse(UpdateWatch.changed(previous = "   ", current = "build-1"))
    }

    @Test
    fun theSameBuildIsNotAChange() {
        assertFalse(UpdateWatch.changed("4.4.1-a16-20261022695941-community-ginkgo",
            "4.4.1-a16-20261022695941-community-ginkgo"))
    }

    @Test
    fun aDifferentBuildIsAChange() {
        assertTrue(UpdateWatch.changed("4.4.1-a16-20261022695941-community-ginkgo",
            "4.4.1-a16-20261125120000-community-ginkgo"))
    }

    @Test
    fun aChangeIsATrueOnlyOnceTheNewBuildIsRecorded() {
        // Guards the order the service uses: check first, record after. If the record happened
        // first, every comparison would be against the new build and the update would go unseen.
        val previous = "build-a"
        val current = "build-b"
        val first = UpdateWatch.changed(previous, current)
        val second = UpdateWatch.changed(current, current)
        assertTrue(first)
        assertFalse(second)
    }
}
