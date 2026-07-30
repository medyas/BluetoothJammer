package com.eikarna.bluetoothjammer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import util.BluetoothAddress

/**
 * Host-side unit tests for the Bluetooth MAC validation/normalization used by the
 * manual target-entry flow. These run on CI via `./gradlew testDebugUnitTest`.
 */
class BluetoothAddressTest {

    @Test
    fun validCanonicalAddressesAreAccepted() {
        assertTrue(BluetoothAddress.isValid("00:11:22:AA:BB:CC"))
        assertTrue(BluetoothAddress.isValid("aa:bb:cc:dd:ee:ff"))
        assertTrue(BluetoothAddress.isValid("  00:11:22:33:44:55  ")) // surrounding whitespace tolerated
    }

    @Test
    fun invalidAddressesAreRejected() {
        assertFalse(BluetoothAddress.isValid(null))
        assertFalse(BluetoothAddress.isValid(""))
        assertFalse(BluetoothAddress.isValid("00:11:22:33:44"))        // too few octets
        assertFalse(BluetoothAddress.isValid("00:11:22:33:44:55:66"))  // too many octets
        assertFalse(BluetoothAddress.isValid("GG:11:22:33:44:55"))     // non-hex
        assertFalse(BluetoothAddress.isValid("001122334455"))          // missing separators
        assertFalse(BluetoothAddress.isValid("0:1:2:3:4:5"))           // single digits
    }

    @Test
    fun normalizeAcceptsCommonSeparators() {
        assertEquals("00:11:22:AA:BB:CC", BluetoothAddress.normalize("00:11:22:aa:bb:cc"))
        assertEquals("00:11:22:AA:BB:CC", BluetoothAddress.normalize("00-11-22-AA-BB-CC"))
        assertEquals("00:11:22:AA:BB:CC", BluetoothAddress.normalize("0011.22aabbcc"))
        assertEquals("00:11:22:AA:BB:CC", BluetoothAddress.normalize("  00 11 22 aa bb cc "))
    }

    @Test
    fun normalizeRejectsBadInput() {
        assertNull(BluetoothAddress.normalize(null))
        assertNull(BluetoothAddress.normalize(""))
        assertNull(BluetoothAddress.normalize("00:11:22:33:44"))  // 10 hex chars
        assertNull(BluetoothAddress.normalize("zzzzzzzzzzzz"))    // non-hex
    }

    @Test
    fun normalizeOutputIsAlwaysValid() {
        val normalized = BluetoothAddress.normalize("aabbccddeeff")
        assertTrue(BluetoothAddress.isValid(normalized))
    }
}
