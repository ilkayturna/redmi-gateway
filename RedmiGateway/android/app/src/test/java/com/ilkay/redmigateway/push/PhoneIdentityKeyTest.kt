package com.ilkay.redmigateway.push

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Kotlin port of the iPhone's PhoneIdentity.key (PhoneContactPicker.swift).
 *
 * The pushed SMS banner is grouped under "sms-<key>", and the iPhone app clears and suppresses
 * notifications by the same key. If the two phones compute different keys, pushed banners land in
 * a stack the app never clears (N10, CONS-14). The first six vectors are the shared ones from
 * docs/WAVE1-CONTRACT.md section 7; the iPhone's test checks the same list.
 */
class PhoneIdentityKeyTest {

    private val sharedVectors = listOf(
        "+90 532 123 45 67" to "5321234567",
        "05321234567" to "5321234567",
        "5321234567" to "5321234567",
        "TURKCELL" to "TURKCELL",
        " Garanti BBVA " to "GARANTI BBVA",
        "+44 20 7946 0958" to "442079460958",
    )

    @Test
    fun theSharedContractVectorsMatch() {
        for ((input, expected) in sharedVectors) {
            assertEquals("key of '$input'", expected, phoneIdentityKey(input))
        }
    }

    @Test
    fun theThreadIdIsSmsPlusTheKey() {
        for ((input, expected) in sharedVectors) {
            assertEquals("sms-$expected", smsPushThreadId(input))
        }
    }

    @Test
    fun aTwelveDigitNumberNotStartingWith90KeepsAllDigits() {
        // Only the Turkish country code is stripped; a German or British number stays whole, so it
        // can never collide with a Turkish contact that shares its last ten digits.
        assertEquals("491511234567", phoneIdentityKey("+49 151 1234567"))
    }

    @Test
    fun anElevenDigitNumberNotStartingWithZeroKeepsAllDigits() {
        assertEquals("15551234567", phoneIdentityKey("+1 555 123 4567"))
    }

    @Test
    fun the0090SpellingIsNotShortened() {
        // The iPhone has no 0090 rule (ContactResolver.normalize does); matching the iPhone is the
        // point, so this stays all digits on both phones.
        assertEquals("00905321234567", phoneIdentityKey("0090 532 123 45 67"))
    }

    @Test
    fun shortCodesKeepTheirDigits() {
        assertEquals("4440532", phoneIdentityKey("444 0 532"))
        assertEquals("532", phoneIdentityKey("532"))
    }

    @Test
    fun lettersAroundDigitsAreDropped() {
        assertEquals("1234", phoneIdentityKey("BANK1234"))
    }

    @Test
    fun theUppercaseIsLocaleFree() {
        // A Turkish-locale uppercase would turn "i" into a dotted capital and split the thread.
        assertEquals("E-DEVLET BILGI", phoneIdentityKey("e-Devlet bilgi"))
    }

    @Test
    fun anEmptySenderGivesAnEmptyKey() {
        assertEquals("", phoneIdentityKey(""))
        assertEquals("", phoneIdentityKey("   "))
    }
}
