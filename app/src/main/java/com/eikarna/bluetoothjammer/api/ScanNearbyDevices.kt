package api

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class ScanNearbyDevices {

    private var isScanning = false
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    companion object {
        private var instance: ScanNearbyDevices? = null

        // Singleton pattern to ensure only one instance exists
        fun getInstance(): ScanNearbyDevices {
            if (instance == null) {
                instance = ScanNearbyDevices()
            }
            return instance!!
        }

        val devicesList = mutableListOf<BluetoothDeviceInfo>()
    }

    // Function to start scanning for nearby Bluetooth devices
    fun startScanning(context: Context, callback: (List<BluetoothDeviceInfo>) -> Unit) {
        val bluetoothManager: BluetoothManager? =
            context.getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

        if (bluetoothAdapter?.isEnabled == true && !isScanning) {
            isScanning = true

            // Create a runnable to scan every second
            val scanRunnable = object : Runnable {
                @SuppressLint("MissingPermission")
                override fun run() {
                    Log.d("ScanNearbyDevices", "Scanning for nearby devices...")
                    println("Scanning nearby devices...")
                    devicesList.clear() // Clear previous results

                    val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
                    pairedDevices?.forEach { device ->
                        device.fetchUuidsWithSdp()
                        val deviceInfo = BluetoothDeviceInfo(
                            name = device.name ?: "Unknown Device",
                            address = device.address
                        )
                        devicesList.add(deviceInfo)
                    }

                    // Return the list of devices to the callback function
                    callback(devicesList)

                    // Schedule the next scan after 1 second
                    handler.postDelayed(this, 1000)
                }
            }
            runnable = scanRunnable

            // Start the periodic scanning
            handler.post(scanRunnable)
        } else {
            Log.e("ScanNearbyDevices", "Bluetooth is disabled or already scanning.")
        }
    }

    // Function to stop scanning
    fun stopScanning() {
        if (isScanning) {
            isScanning = false
            runnable?.let { handler.removeCallbacks(it) } // Stop the periodic scanning
        }
    }

    fun resumeScanning() {
        // Only resume if a scan was previously configured; otherwise there is nothing to post.
        val pending = runnable
        if (!isScanning && pending != null) {
            isScanning = true
            handler.post(pending)
        }
    }
}

data class BluetoothDeviceInfo(
    val name: String,
    val address: String
)
