package com.ilkay.redmigateway.server

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HealthBusTest {
    @Before
    fun setUp() = HealthBus.resetForTest()

    @After
    fun tearDown() = HealthBus.resetForTest()

    @Test
    fun latestIsNullBeforeTheFirstPublish() {
        assertNull(HealthBus.latest())
    }

    @Test
    fun sameKeyUpdatesTheSnapshotWithoutNotifying() {
        val seen = mutableListOf<Int>()
        HealthBus.addListener { seen += it.getInt("battery") }

        assertTrue(HealthBus.publish(JSONObject().put("battery", 80), "ok"))
        assertFalse(HealthBus.publish(JSONObject().put("battery", 79), "ok"))

        assertEquals(listOf(80), seen)
        assertEquals(79, HealthBus.latest()!!.getInt("battery"))
    }

    @Test
    fun newKeyNotifiesEveryListenerEvenWhenOneThrows() {
        val seen = mutableListOf<String>()
        HealthBus.addListener { throw IllegalStateException("broken listener") }
        HealthBus.addListener { seen += it.getString("state") }

        HealthBus.publish(JSONObject().put("state", "ok"), "ok")
        HealthBus.publish(JSONObject().put("state", "vendorFix"), "vendorFix")

        assertEquals(listOf("ok", "vendorFix"), seen)
    }

    @Test
    fun listenersGetCopiesTheyCannotUseToChangeTheLatestSnapshot() {
        HealthBus.addListener { it.put("state", "changed by listener") }

        HealthBus.publish(JSONObject().put("state", "ok"), "ok")

        assertEquals("ok", HealthBus.latest()!!.getString("state"))
    }
}
