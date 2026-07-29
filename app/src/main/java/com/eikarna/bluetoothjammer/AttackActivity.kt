package com.eikarna.bluetoothjammer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.graphics.text.LineBreaker
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.isDigitsOnly
import androidx.core.widget.doAfterTextChanged
import api.L2capFloodAttack
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.textfield.TextInputEditText
import util.Logger

class AttackActivity : AppCompatActivity() {

    // Initialize UI elements
    private lateinit var viewDeviceName: MaterialTextView
    private lateinit var viewDeviceAddress: MaterialTextView
    private lateinit var viewThreads: TextInputEditText
    private lateinit var buttonStartStop: MaterialButton
    private lateinit var logAttack: MaterialTextView
    private lateinit var switchLog: MaterialSwitch

    // Initialize detail info
    private lateinit var deviceName: String
    private lateinit var address: String
    private var threads: Int = 1

    // Single attack instance so start/stop operate on the same coroutines.
    private lateinit var attack: L2capFloodAttack

    companion object {
        @JvmStatic
        @Volatile
        var isAttacking = false
        var FrameworkVersion = 1.0

        @Volatile
        var loggingStatus = true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("AttackActivity", "onCreate called")
        println("AttackActivity onCreate called")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.attack_layout)

        // Get data from Intent
        deviceName = intent.getStringExtra("DEVICE_NAME") ?: "Unknown Device"
        address = intent.getStringExtra("ADDRESS") ?: "Unknown Address"
        threads = intent.getIntExtra("THREADS", 1)
        attack = L2capFloodAttack(address)

        // Get Element ID
        viewDeviceName = findViewById(R.id.textViewDeviceName)
        viewDeviceAddress = findViewById(R.id.textViewAddress)
        viewThreads = findViewById(R.id.editTextThreads)
        buttonStartStop = findViewById(R.id.buttonStartStop)
        logAttack = findViewById(R.id.logTextView)
        switchLog = findViewById(R.id.switchLogView)

        // Set text views
        viewDeviceName.text = "Device Name: $deviceName"
        viewDeviceAddress.text = "Address: $address"
        viewThreads.setText("$threads")
        logAttack.justificationMode = LineBreaker.JUSTIFICATION_MODE_INTER_WORD
        Logger.appendLog(logAttack, "Bluetooth Jammer Framework Version: $FrameworkVersion")



        // Set button listener
        buttonStartStop.setOnClickListener {
            if (isAttacking) {
                stopAttack()
            } else {
                startAttack()
            }
        }

        // Threading Input listener
        viewThreads.doAfterTextChanged { str ->
            if (str != null) {
                if (str.toString() != "" && str.isDigitsOnly()) {
                    threads = str.toString().toInt()
                }
            }
        }

        // Logging Switch listener
        switchLog.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                loggingStatus = true
                Toast.makeText(this@AttackActivity, "Logging Enabled! You may degrade performance issue.", Toast.LENGTH_LONG).show()
            } else {
                loggingStatus = false
                Toast.makeText(this@AttackActivity, "Logging Disabled!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun startAttack() {
        isAttacking = true
        buttonStartStop.text = "Stop"
        bluetoothAdapter()?.cancelDiscovery()
        Logger.appendLog(logAttack, "Attack Started! Address: $address ($deviceName) | Threads: $threads")
        Toast.makeText(this@AttackActivity, "Attack started with $threads thread(s).", Toast.LENGTH_SHORT).show()
        attack.startAttack(this, logAttack, threads)
    }

    @SuppressLint("MissingPermission")
    private fun stopAttack() {
        isAttacking = false
        buttonStartStop.text = "Start"
        attack.stopAttack()
        Logger.appendLog(logAttack, "Attack Stopped!")
        bluetoothAdapter()?.startDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isAttacking) {
            stopAttack() // Ensure the attack stops if the activity is destroyed
        }
    }

    override fun onPause() {
        super.onPause()
        if (isAttacking) {
            stopAttack()
        }
    }
}
