package api

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Discovers nearby Bluetooth devices.
 *
 * Previously this only polled the bonded (paired) device set every second and
 * cleared the list on each tick, so devices reported by the ACTION_FOUND
 * broadcast were wiped and true discovery was never started. It now seeds the
 * list with bonded devices, starts an actual discovery scan, keeps results
 * de-duplicated by address, and restarts discovery when it finishes.
 */
class ScanNearbyDevices {

    private var isScanning = false
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var onUpdate: ((List<BluetoothDeviceInfo>) -> Unit)? = null

    companion object {
        private var instance: ScanNearbyDevices? = null

        // Singleton pattern to ensure only one instance exists
        fun getInstance(): ScanNearbyDevices {
            if (instance == null) {
                instance = ScanNearbyDevices()
            }
            return instance!!
        }

        // Ordered and de-duplicated by device address.
        val devicesMap = LinkedHashMap<String, BluetoothDeviceInfo>()
    }

    private val TAG = "ScanNearbyDevices"

    @SuppressLint("MissingPermission")
    fun startScanning(context: Context, callback: (List<BluetoothDeviceInfo>) -> Unit) {
        val bluetoothManager: BluetoothManager? =
            context.getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        bluetoothAdapter = adapter

        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled or unavailable.")
            return
        }

        onUpdate = callback
        isScanning = true

        // Drop results from any previous scan session so the list can't show stale devices.
        devicesMap.clear()

        // Seed the list with bonded (paired) devices.
        adapter.bondedDevices?.forEach { device ->
            addDevice(device.name ?: "Unknown Device", device.address)
        }
        emit()

        beginDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun beginDiscovery() {
        val adapter = bluetoothAdapter ?: return
        if (!isScanning) return
        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        adapter.startDiscovery()
    }

    /** Called from the activity's ACTION_FOUND broadcast receiver. */
    fun onDeviceDiscovered(name: String?, address: String?) {
        if (address.isNullOrEmpty()) return
        if (addDevice(name ?: "Unknown Device", address)) {
            emit()
        }
    }

    /**
     * Called from the activity's ACTION_DISCOVERY_FINISHED broadcast receiver.
     * Discovery stops itself after roughly 12 seconds, so restart it to keep
     * finding devices while the screen is open.
     */
    fun onDiscoveryFinished() {
        if (!isScanning) return
        handler.postDelayed({ beginDiscovery() }, 1000)
    }

    private fun addDevice(name: String, address: String): Boolean {
        val existing = devicesMap[address]
        if (existing != null && existing.name == name) return false
        devicesMap[address] = BluetoothDeviceInfo(name, address)
        return true
    }

    private fun emit() {
        onUpdate?.invoke(devicesMap.values.toList())
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) return
        isScanning = false
        handler.removeCallbacksAndMessages(null)
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to cancel discovery", e)
        }
    }

    fun resumeScanning() {
        // Only resume if a scan was previously configured.
        if (!isScanning && onUpdate != null) {
            isScanning = true
            emit()
            beginDiscovery()
        }
    }
}

data class BluetoothDeviceInfo(
    val name: String,
    val address: String
)
