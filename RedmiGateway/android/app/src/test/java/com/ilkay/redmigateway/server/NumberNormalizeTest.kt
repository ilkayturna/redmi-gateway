package com.ilkay.redmigateway.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins how a phone number is reduced to one form before a contact is looked up.
 *
 * The Redmi sees the same person written several ways depending on where the number came from: the
 * dialer sends one form, an incoming call another, an SMS another. If they do not reduce to the
 * same string the contact is not found and the call shows a bare number, which is the difference
 * between a phone that looks finished and one that does not.
 *
 * The property that matters most is the first test: every way of writing a Turkish mobile has to
 * come out identical.
 */
class NumberNormalizeTest {

    private fun normalize(number: String) = ContactResolver.normalize(number)

    // ---- The property the whole thing exists for ----

    @Test
    fun everyWayOfWritingTheSameMobileReducesToOneForm() {
        val forms = listOf(
            "5551234567",              // ten digits
            "05551234567",             // with the trunk zero
            "905551234567",            // with the country code
            "00905551234567",          // with the international prefix
            "+90 555 123 45 67",       // as a person writes it
            "+905551234567",
            "0555 123 45 67",
            "(0555) 123-45-67",
        )
        val normalized = forms.map { normalize(it) }.toSet()
        assertEquals("all forms should reduce to 5551234567, got $normalized", setOf("5551234567"), normalized)
    }

    @Test
    fun anAlreadyPlainMobileIsLeftAlone() {
        assertEquals("5551234567", normalize("5551234567"))
    }

    // ---- Each prefix rule ----

    @Test
    fun theInternationalPrefixIsStripped() {
        assertEquals("5551234567", normalize("00905551234567"))
    }

    @Test
    fun theCountryCodeIsStripped() {
        assertEquals("5551234567", normalize("905551234567"))
    }

    @Test
    fun theTrunkZeroIsStripped() {
        assertEquals("5551234567", normalize("05551234567"))
    }

    @Test
    fun punctuationAndSpacingDoNotMatter() {
        assertEquals("5551234567", normalize("+90 (555) 123-45-67"))
        assertEquals("5551234567", normalize("  0555 123 45 67  "))
        assertEquals("5551234567", normalize("555.123.4567"))
    }

    // ---- What must not be mangled ----

    @Test
    fun aLandlineIsNotForcedIntoTheMobileShape() {
        // Only mobiles start with 5, so a landline keeps its own digits rather than losing the
        // first ones to a rule meant for mobiles.
        val landline = normalize("02121234567")
        assertEquals("2121234567", landline)
        assertNotEquals("5551234567", landline)
    }

    @Test
    fun aForeignNumberIsNotTruncated() {
        // A twelve digit number that is not a Turkish one must not lose its first two digits.
        val foreign = normalize("4915112345678")
        assertEquals("4915112345678", foreign)
    }

    @Test
    fun aShortNumberIsLeftAlone() {
        // Service codes and short numbers must survive intact.
        assertEquals("112", normalize("112"))
        assertEquals("532", normalize("532"))
        assertEquals("1234", normalize("1234"))
    }

    @Test
    fun anEmptyOrSymbolOnlyNumberKeepsSomethingToShow() {
        // Returning an empty string would make the call screen show nothing at all.
        assertEquals("", normalize(""))
        assertEquals("+", normalize("+"))
        assertEquals("GIZLI", normalize("gizli"))
    }

    @Test
    fun lettersAreUppercasedSoPrivateNumbersMatch() {
        // "Unknown" and "UNKNOWN" arrive from different layers and must be the same key.
        assertEquals("GIZLI", normalize("gizli"))
        assertEquals("GIZLI", normalize("GIZLI"))
        assertEquals("UNKNOWN", normalize("unknown"))
    }

    @Test
    fun itIsIdempotentSoNormalisingTwiceChangesNothing() {
        // The cached lookup normalises again on the way out; a second pass that changed the value
        // would look up a different key than the one that was stored.
        for (input in listOf("05551234567", "905551234567", "+90 555 123 45 67", "112", "gizli", "")) {
            val once = normalize(input)
            assertEquals("input=$input", once, normalize(once))
        }
    }

    @Test
    fun twoDifferentPeopleDoNotCollide() {
        // The reduction must not be so eager that it merges numbers.
        assertNotEquals(normalize("05551234567"), normalize("05551234568"))
        assertNotEquals(normalize("05551234567"), normalize("05559876543"))
    }
}
