package com.eikarna.bluetoothjammer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.graphics.text.LineBreaker
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.isDigitsOnly
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import api.AttackEngine
import api.AttackEvent
import api.AttackStats
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import util.Format
import util.Logger

class AttackActivity : AppCompatActivity() {

    private lateinit var viewDeviceName: MaterialTextView
    private lateinit var viewDeviceAddress: MaterialTextView
    private lateinit var viewThreads: TextInputEditText
    private lateinit var buttonStartStop: MaterialButton
    private lateinit var viewStats: MaterialTextView
    private lateinit var logAttack: MaterialTextView
    private lateinit var switchLog: MaterialSwitch

    private lateinit var deviceName: String
    private lateinit var address: String
    private var threads: Int = 1

    private lateinit var engine: AttackEngine
    private var attacking = false

    companion object {
        const val FrameworkVersion = "1.1"

        // UI concern: whether per-connection log lines are rendered. The engine always emits
        // them; the switch just decides whether we show them.
        @Volatile
        var loggingStatus = true

        private const val MAX_LOG_LINES = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.attack_layout)

        deviceName = intent.getStringExtra("DEVICE_NAME") ?: "Unknown Device"
        address = intent.getStringExtra("ADDRESS") ?: "Unknown Address"
        threads = intent.getIntExtra("THREADS", 1)
        engine = AttackEngine(address)

        viewDeviceName = findViewById(R.id.textViewDeviceName)
        viewDeviceAddress = findViewById(R.id.textViewAddress)
        viewThreads = findViewById(R.id.editTextThreads)
        buttonStartStop = findViewById(R.id.buttonStartStop)
        viewStats = findViewById(R.id.textViewStats)
        logAttack = findViewById(R.id.logTextView)
        switchLog = findViewById(R.id.switchLogView)

        viewDeviceName.text = "Device Name: $deviceName"
        viewDeviceAddress.text = "Address: $address"
        viewThreads.setText("$threads")
        logAttack.justificationMode = LineBreaker.JUSTIFICATION_MODE_INTER_WORD
        Logger.appendLog(logAttack, "Bluetooth Jammer Framework Version: $FrameworkVersion")
        renderStats(AttackStats())

        buttonStartStop.setOnClickListener {
            if (attacking) stopAttack() else startAttack()
        }

        viewThreads.doAfterTextChanged { str ->
            val text = str?.toString().orEmpty()
            if (text.isNotEmpty() && text.isDigitsOnly()) {
                threads = text.toInt()
            }
        }

        switchLog.setOnCheckedChangeListener { _, isChecked ->
            loggingStatus = isChecked
            Toast.makeText(
                this,
                if (isChecked) R.string.logging_enabled else R.string.logging_disabled,
                Toast.LENGTH_LONG
            ).show()
        }

        observeEngine()
    }

    private fun observeEngine() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { engine.stats.collect { renderStats(it) } }
                launch { engine.events.collect { onEvent(it) } }
            }
        }
    }

    private fun renderStats(stats: AttackStats) {
        viewStats.text = getString(
            R.string.attack_stats_fmt,
            stats.activeWorkers,
            stats.connectAttempts,
            stats.successfulConnections,
            stats.failedConnections,
            Format.humanBytes(stats.bytesSent),
            stats.elapsedMillis / 1000,
            Format.humanBytes(stats.bytesPerSecond),
        )
    }

    private fun onEvent(event: AttackEvent) {
        when (event) {
            is AttackEvent.Started ->
                appendLog(getString(R.string.attack_started_fmt, event.target, event.workers))
            is AttackEvent.Log ->
                if (loggingStatus) appendLog(event.message)
            AttackEvent.Stopped ->
                appendLog(getString(R.string.attack_stopped))
        }
    }

    private fun appendLog(message: String) {
        purgeOldestLinesIfNeeded(logAttack)
        Logger.appendLog(logAttack, message)
    }

    private fun purgeOldestLinesIfNeeded(element: TextView) {
        val lines = element.text.split("\n")
        if (lines.size > MAX_LOG_LINES) {
            element.text = lines.takeLast(MAX_LOG_LINES).joinToString("\n")
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission")
    private fun startAttack() {
        val device = try {
            bluetoothAdapter()?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (device == null) {
            Toast.makeText(this, R.string.invalid_target_device, Toast.LENGTH_SHORT).show()
            return
        }

        attacking = true
        buttonStartStop.text = getString(R.string.stop)
        bluetoothAdapter()?.cancelDiscovery()
        Toast.makeText(this, "Attack started with $threads thread(s).", Toast.LENGTH_SHORT).show()
        engine.start(device, threads)
    }

    @SuppressLint("MissingPermission")
    private fun stopAttack() {
        attacking = false
        buttonStartStop.text = getString(R.string.start)
        lifecycleScope.launch {
            engine.stopAndJoin()
            bluetoothAdapter()?.startDiscovery()
        }
    }

    override fun onPause() {
        super.onPause()
        if (attacking) {
            attacking = false
            buttonStartStop.text = getString(R.string.start)
            engine.cancel()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.cancel()
    }
}
