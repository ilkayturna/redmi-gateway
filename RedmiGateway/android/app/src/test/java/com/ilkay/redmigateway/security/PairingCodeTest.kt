package com.ilkay.redmigateway.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Pins the pairing code, which is what bootstraps the entire pairing.
 *
 * The code is read off the Redmi's Settings screen and typed into the iPhone, and it is the only
 * thing standing between a stranger on the same network and a paired session. Two properties keep
 * it honest and both are easy to break without noticing: the alphabet must contain no repeated
 * character, or that character becomes twice as likely and the code is weaker than its length
 * suggests, and the length must stay at six, because six characters over a thirty two character
 * alphabet is thirty bits and a shorter code is simply fewer guesses.
 *
 * The alphabet is also chosen to leave out I, O, 0 and 1, which are the pairs people confuse when
 * copying a code from one screen into another.
 */
class PairingCodeTest {

    private val random = SecureRandom()
    private val alphabet = PairingStore.alphabet

    @Test
    fun theAlphabetHasNoRepeatedCharacter() {
        // A duplicate would make one character more likely than the others.
        assertEquals(
            "every character must be distinct",
            alphabet.length,
            alphabet.toSet().size
        )
    }

    @Test
    fun theAlphabetLeavesOutTheCharactersPeopleConfuse() {
        for (confusing in listOf('I', 'O', '0', '1')) {
            assertFalse("$confusing must not be in the alphabet", alphabet.contains(confusing))
        }
    }

    @Test
    fun theAlphabetIsLargeEnoughToBeWorthCallingRandom() {
        // Thirty two gives exactly five bits per character, so six characters is thirty bits.
        assertTrue("alphabet is ${alphabet.length}", alphabet.length >= 32)
    }

    @Test
    fun aCodeIsSixCharactersLong() {
        for (attempt in 1..200) {
            assertEquals(PAIRING_CODE_LENGTH, newPairingCode(alphabet, random).length)
        }
        assertEquals(6, PAIRING_CODE_LENGTH)
    }

    @Test
    fun everyCharacterComesFromTheAlphabet() {
        for (attempt in 1..200) {
            val code = newPairingCode(alphabet, random)
            assertTrue("code=$code", code.all { alphabet.contains(it) })
        }
    }

    @Test
    fun codesDifferBetweenCalls() {
        // Two pairings must not produce the same code, or rotating after pairing would be useless.
        val codes = (1..200).map { newPairingCode(alphabet, random) }.toSet()
        assertTrue("only ${codes.size} distinct codes in 200", codes.size > 150)
    }

    @Test
    fun everyCharacterInTheAlphabetCanActuallyAppear() {
        // A code that could never contain some character would be shorter than it looks.
        val seen = mutableSetOf<Char>()
        for (attempt in 1..4_000) {
            seen += newPairingCode(alphabet, random).toList()
        }
        assertEquals("unreachable characters: ${alphabet.toSet() - seen}", alphabet.toSet(), seen)
    }

    @Test
    fun theCodeIsNotAllOneCharacter() {
        // A stuck generator would still pass the length and alphabet checks.
        val suspicious = (1..200).map { newPairingCode(alphabet, random) }.count { it.toSet().size == 1 }
        assertTrue("$suspicious codes used a single character", suspicious == 0)
    }

    @Test
    fun anEmptyAlphabetIsNotUsedAnywhere() {
        // The production alphabet is a constant, and this records why it must stay non-empty:
        // drawing from an empty string would throw rather than produce a code.
        assertTrue(alphabet.isNotEmpty())
    }
}
