package com.eikarna.bluetoothjammer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import api.BluetoothDeviceInfo
import api.ScanNearbyDevices
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the device scanner (a plain instance, not a process-global singleton) and exposes the
 * running device list as a [StateFlow] the activity observes.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = ScanNearbyDevices()

    val devices: StateFlow<List<BluetoothDeviceInfo>> get() = scanner.devices

    fun startScanning() = scanner.startScanning(getApplication())
    fun onDeviceDiscovered(name: String?, address: String?) = scanner.onDeviceDiscovered(name, address)
    fun onDiscoveryFinished() = scanner.onDiscoveryFinished()
    fun stopScanning() = scanner.stopScanning()
    fun resumeScanning() = scanner.resumeScanning()

    override fun onCleared() {
        super.onCleared()
        scanner.stopScanning()
    }
}
