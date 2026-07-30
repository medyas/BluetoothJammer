package com.eikarna.bluetoothjammer

import api.AttackStats
import org.junit.Assert.assertEquals
import org.junit.Test

/** Host-side tests for the pure throughput math in [AttackStats]. */
class AttackStatsTest {

    @Test
    fun bytesPerSecondIsZeroBeforeAnyTimeElapses() {
        assertEquals(0L, AttackStats(bytesSent = 1_000, elapsedMillis = 0).bytesPerSecond)
    }

    @Test
    fun bytesPerSecondComputesAverageThroughput() {
        assertEquals(1_000L, AttackStats(bytesSent = 1_000, elapsedMillis = 1_000).bytesPerSecond)
        assertEquals(2_000L, AttackStats(bytesSent = 1_000, elapsedMillis = 500).bytesPerSecond)
        assertEquals(500L, AttackStats(bytesSent = 1_000, elapsedMillis = 2_000).bytesPerSecond)
    }

    @Test
    fun defaultStatsAreEmpty() {
        val stats = AttackStats()
        assertEquals(false, stats.running)
        assertEquals(0, stats.activeWorkers)
        assertEquals(0L, stats.bytesSent)
        assertEquals(0L, stats.bytesPerSecond)
    }
}
