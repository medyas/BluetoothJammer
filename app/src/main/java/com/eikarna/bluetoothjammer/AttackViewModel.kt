package com.eikarna.bluetoothjammer

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import api.AttackEngine
import api.AttackEvent
import api.AttackStats
import api.BluetoothRfcommConnectionFactory
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the [AttackEngine] across configuration changes so an in-progress, on-screen run is not
 * lost when the activity is recreated (e.g. rotation). The engine runs on its own coroutine
 * scope, so it is tied to this ViewModel's lifetime, not the Activity's — but note this is
 * *not* a background service: the run still stops when the ViewModel is cleared.
 */
class AttackViewModel(application: Application) : AndroidViewModel(application) {

    private lateinit var engine: AttackEngine
    private lateinit var targetAddress: String
    private var initialized = false

    /** Idempotent: the engine is created once and survives activity recreation. */
    fun initialize(address: String) {
        if (initialized) return
        targetAddress = address
        engine = AttackEngine(address)
        initialized = true
    }

    val stats: StateFlow<AttackStats> get() = engine.stats
    val events: SharedFlow<AttackEvent> get() = engine.events
    val isRunning: Boolean get() = initialized && engine.isRunning

    /** Returns false if the target address can't be resolved to a device. */
    @SuppressLint("MissingPermission")
    fun start(workers: Int): Boolean {
        val device = try {
            adapter()?.getRemoteDevice(targetAddress)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return false

        adapter()?.cancelDiscovery()
        engine.start(BluetoothRfcommConnectionFactory(device), workers)
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        viewModelScope.launch {
            engine.stopAndJoin()
            adapter()?.startDiscovery()
        }
    }

    private fun adapter(): BluetoothAdapter? =
        getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter

    override fun onCleared() {
        super.onCleared()
        if (initialized) engine.cancel()
    }
}
