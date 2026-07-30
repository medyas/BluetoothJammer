package com.eikarna.bluetoothjammer

import org.junit.Assert.assertEquals
import org.junit.Test
import util.Format

/** Host-side tests for the pure byte-count formatter. */
class FormatTest {

    @Test
    fun formatsBytesBelowOneKilobyte() {
        assertEquals("0 B", Format.humanBytes(0))
        assertEquals("512 B", Format.humanBytes(512))
        assertEquals("1023 B", Format.humanBytes(1023))
    }

    @Test
    fun formatsKilobytesAndMegabytes() {
        assertEquals("1.0 KB", Format.humanBytes(1024))
        assertEquals("1.5 KB", Format.humanBytes(1536))
        assertEquals("1.0 MB", Format.humanBytes(1024L * 1024))
    }

    @Test
    fun formatsGigabytes() {
        assertEquals("1.0 GB", Format.humanBytes(1024L * 1024 * 1024))
    }
}
