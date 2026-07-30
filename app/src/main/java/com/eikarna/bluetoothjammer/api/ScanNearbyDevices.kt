package api

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Discovers nearby Bluetooth devices for one owner (a ViewModel), exposing the running,
 * de-duplicated result set as a [StateFlow].
 *
 * This is a plain instance — no singleton, no `static` device map — so its state lives and dies
 * with its owner rather than the process. It seeds the list with bonded (paired) devices, starts
 * a discovery scan, de-duplicates by address, and restarts discovery when a scan finishes.
 */
class ScanNearbyDevices {

    private var isScanning = false
    private var configured = false
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var bluetoothAdapter: BluetoothAdapter? = null

    // Ordered and de-duplicated by device address; instance state, not static.
    private val devicesMap = LinkedHashMap<String, BluetoothDeviceInfo>()

    private val _devices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val devices: StateFlow<List<BluetoothDeviceInfo>> = _devices.asStateFlow()

    private val tag = "ScanNearbyDevices"

    @SuppressLint("MissingPermission")
    fun startScanning(context: Context) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        bluetoothAdapter = adapter

        if (adapter == null || !adapter.isEnabled) {
            Log.e(tag, "Bluetooth is disabled or unavailable.")
            return
        }

        isScanning = true
        configured = true

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
     * Called from the activity's ACTION_DISCOVERY_FINISHED broadcast receiver. Discovery stops
     * itself after ~12 seconds, so restart it to keep finding devices while the screen is open.
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
        _devices.value = devicesMap.values.toList()
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) return
        isScanning = false
        handler.removeCallbacksAndMessages(null)
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.e(tag, "Missing permission to cancel discovery", e)
        }
    }

    fun resumeScanning() {
        // Only resume if a scan was previously configured.
        if (!isScanning && configured) {
            isScanning = true
            emit()
            beginDiscovery()
        }
    }
}

data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
)
