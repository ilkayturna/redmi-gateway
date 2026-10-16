package com.ilkay.redmigateway.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the parts of a long text message are put back together.
 *
 * A message over one hundred and sixty characters arrives as several parts, and the result of this
 * function goes both into the journal and out to the iPhone. Two failures are possible and both
 * are silent: joining the parts out of order, and inserting anything between them. Either would
 * corrupt every long message while short ones kept working, which is exactly the kind of fault
 * that takes a while to be noticed and longer to explain.
 */
class SmsReassemblyTest {

    @Test
    fun aSinglePartComesBackUnchanged() {
        val one = "Kisa mesaj"
        assertEquals(one, reassembleSmsParts(listOf(one)))
    }

    @Test
    fun partsAreJoinedInTheOrderTheyArrived() {
        // A two hundred character message split across two parts.
        val first = "a".repeat(160)
        val second = "b".repeat(40)
        assertEquals(first + second, reassembleSmsParts(listOf(first, second)))
    }

    @Test
    fun nothingIsInsertedBetweenTheParts() {
        // This is the failure that matters: a separator added here would break every long message.
        val joined = reassembleSmsParts(listOf("Merhaba", "dunya"))
        assertEquals("Merhabadunya", joined)
        assertEquals(-1, joined.indexOf(' '))
        assertEquals(-1, joined.indexOf('\n'))
        assertEquals(-1, joined.indexOf('|'))
    }

    @Test
    fun theJoinedLengthIsTheSumOfTheParts() {
        // A separator, however small, would show up here.
        val parts = listOf("a".repeat(160), "b".repeat(160), "c".repeat(30))
        assertEquals(350, reassembleSmsParts(parts).length)
    }

    @Test
    fun anEmptyPartIsAllowed() {
        // A part carrying only a character that renders as nothing still occupies a slot.
        assertEquals("ab", reassembleSmsParts(listOf("a", "", "b")))
    }

    @Test
    fun anEmptyListGivesAnEmptyMessage() {
        assertEquals("", reassembleSmsParts(emptyList()))
    }

    @Test
    fun aListOfEmptyPartsGivesAnEmptyMessage() {
        assertEquals("", reassembleSmsParts(listOf("", "", "")))
    }

    @Test
    fun manyPartsSurviveIntact() {
        // Ten parts is the practical maximum for a long message.
        val parts = (1..10).map { "part$it " }
        val joined = reassembleSmsParts(parts)
        assertTrue(joined.startsWith("part1 "))
        assertTrue(joined.endsWith("part10 "))
        assertEquals(parts.joinToString(""), joined)
    }

    @Test
    fun turkishCharactersAreNotMangled() {
        // The parts are already decoded strings; joining must not touch their contents.
        val parts = listOf("Görüşürüz ", "şğüöçİ")
        assertEquals("Görüşürüz şğüöçİ", reassembleSmsParts(parts))
    }

    @Test
    fun reorderingThePartsProducesADifferentMessage() {
        // Records why the order matters, so a "sort" added later has to justify itself.
        val first = "birinci"
        val second = "ikinci"
        assertEquals("birinciikinci", reassembleSmsParts(listOf(first, second)))
        assertEquals("ikinci birinci".replace(" ", ""), reassembleSmsParts(listOf(second, first)))
    }
}
