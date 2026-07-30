package com.eikarna.bluetoothjammer

import api.AttackEngine
import api.RfcommConnection
import api.RfcommConnectionFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host-side tests for the engine's worker/retry/stop/stats logic, exercised through the
 * [RfcommConnectionFactory] seam with in-memory fakes (no Android Bluetooth). A constant test
 * clock keeps the engine off `android.os.SystemClock`.
 */
class AttackEngineTest {

    private fun engine(
        connectTimeoutMs: Long = AttackEngine.DEFAULT_CONNECT_TIMEOUT_MS,
        retryBackoffMs: Long = 20L,
    ) = AttackEngine(
        targetAddress = "00:11:22:AA:BB:CC",
        payloadSize = 8,
        connectTimeoutMs = connectTimeoutMs,
        retryBackoffMs = retryBackoffMs,
        clock = { 0L },
    )

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    // --- fakes ------------------------------------------------------------------------------

    /** connect() always fails. */
    private class FailConnection : RfcommConnection {
        override val isConnected = false
        override fun connect() = throw IOException("refused")
        override fun write(bytes: ByteArray) {}
        override fun close() {}
    }

    /** connect() succeeds; write() always accepts, so the worker floods indefinitely. */
    private class FloodConnection : RfcommConnection {
        @Volatile private var connected = false
        override val isConnected get() = connected
        override fun connect() { connected = true }
        override fun write(bytes: ByteArray) {}
        override fun close() { connected = false }
    }

    /** connect() blocks until interrupted — used to exercise the connect-timeout path. */
    private class BlockingConnection : RfcommConnection {
        override val isConnected = false
        override fun connect() { Thread.sleep(10_000) }
        override fun write(bytes: ByteArray) {}
        override fun close() {}
    }

    private class Factory(private val make: () -> RfcommConnection) : RfcommConnectionFactory {
        val creates = AtomicInteger(0)
        override fun create(uuid: UUID): RfcommConnection {
            creates.incrementAndGet()
            return make()
        }
    }

    // --- tests ------------------------------------------------------------------------------

    @Test
    fun allWorkersAttemptConnections() = runBlocking {
        val e = engine()
        e.start(Factory { FailConnection() }, workers = 3)
        awaitUntil { e.stats.value.connectAttempts >= 3 }
        assertTrue(e.stats.value.failedConnections >= 1)
        e.stopAndJoin()
        assertFalse(e.isRunning)
    }

    @Test
    fun successfulConnectionSendsBytes() = runBlocking {
        val e = engine()
        e.start(Factory { FloodConnection() }, workers = 1)
        awaitUntil {
            val s = e.stats.value
            s.successfulConnections >= 1 && s.bytesSent > 0
        }
        e.stopAndJoin()
    }

    @Test
    fun failingConnectionsAreRetried() = runBlocking {
        val factory = Factory { FailConnection() }
        val e = engine()
        e.start(factory, workers = 1)
        awaitUntil { e.stats.value.failedConnections >= 3 }
        assertTrue(factory.creates.get() >= 3)
        e.stopAndJoin()
    }

    @Test
    fun workerCountIsCappedAtMax() = runBlocking {
        val e = engine()
        e.start(Factory { FailConnection() }, workers = 1000)
        awaitUntil { e.stats.value.activeWorkers == AttackEngine.MAX_WORKERS }
        e.stopAndJoin()
    }

    @Test
    fun connectTimeoutIsCountedAsFailure() = runBlocking {
        val e = engine(connectTimeoutMs = 200L)
        e.start(Factory { BlockingConnection() }, workers = 1)
        awaitUntil(timeoutMs = 8_000) { e.stats.value.failedConnections >= 1 }
        e.stopAndJoin()
    }

    @Test
    fun stopHaltsTheRun() = runBlocking {
        val e = engine()
        e.start(Factory { FloodConnection() }, workers = 2)
        awaitUntil { e.stats.value.connectAttempts >= 1 }
        e.stopAndJoin()
        assertFalse(e.isRunning)
        assertFalse(e.stats.value.running)
    }
}
