package com.eikarna.bluetoothjammer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import api.BluetoothDeviceInfo
import api.ScanNearbyDevices
import util.BluetoothAddress

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var editTextTarget: TextInputEditText
    private lateinit var buttonManualTarget: MaterialButton
    private lateinit var deviceListAdapter: ArrayAdapter<String>
    private val deviceNames = mutableListOf<String>()
    private var currentDevices: List<BluetoothDeviceInfo> = emptyList()
    private val scanner = ScanNearbyDevices.getInstance()
    private var receiverRegistered = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private const val PREFS = "bt_jammer_prefs"
        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
        private const val DEFAULT_THREADS = 8
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.deviceListView)
        editTextTarget = findViewById(R.id.editTextTarget)
        buttonManualTarget = findViewById(R.id.buttonManualTarget)

        setupManualTargetEntry()

        // Require an explicit authorized-use acknowledgement before doing anything with Bluetooth.
        if (hasAcceptedDisclaimer()) {
            startBluetoothFlow()
        } else {
            showDisclaimer()
        }
    }

    private fun setupManualTargetEntry() {
        buttonManualTarget.setOnClickListener { submitManualTarget() }
        editTextTarget.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submitManualTarget()
                true
            } else {
                false
            }
        }
    }

    private fun submitManualTarget() {
        val normalized = BluetoothAddress.normalize(editTextTarget.text?.toString())
        if (normalized == null) {
            Toast.makeText(this, R.string.invalid_address, Toast.LENGTH_SHORT).show()
            return
        }
        launchAttack(getString(R.string.manual_target_button), normalized)
    }

    // ---- Responsible-use gate ---------------------------------------------------------------

    private fun hasAcceptedDisclaimer(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DISCLAIMER_ACCEPTED, false)

    private fun showDisclaimer() {
        AlertDialog.Builder(this)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_message)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_agree) { dialog, _ ->
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
                dialog.dismiss()
                startBluetoothFlow()
            }
            .setNegativeButton(R.string.disclaimer_exit) { _, _ -> finish() }
            .show()
    }

    // ---- Bluetooth setup --------------------------------------------------------------------

    private fun startBluetoothFlow() {
        registerDiscoveryReceiver()
        checkBluetoothStatusAndPermissions()
    }

    private fun registerDiscoveryReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Android 14 (targetSdk 35) requires an explicit export flag; these are system broadcasts.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun checkBluetoothStatusAndPermissions() {
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            showBluetoothDisabledDialog()
        } else {
            checkPermissionsAndStartScanning()
        }
    }

    // Create a BroadcastReceiver for ACTION_FOUND.
    private val receiver = object : BroadcastReceiver() {

        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.bluetoothDevice()
                    Log.d("MainActivity", "Device found: ${device?.address}")
                    scanner.onDeviceDiscovered(device?.name, device?.address)
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d("MainActivity", "Discovery finished; restarting")
                    scanner.onDiscoveryFinished()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDisabledDialog() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, 1)
    }

    private fun checkPermissionsAndStartScanning() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        if (hasPermissions(permissions)) {
            startScanningForDevices()
        } else {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScanningForDevices()
            } else {
                Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startScanningForDevices() {
        // Set up a single stable adapter and update its data as devices arrive.
        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)
        listView.adapter = deviceListAdapter
        listView.setOnItemClickListener { _, _, position, _ ->
            currentDevices.getOrNull(position)?.let { showDeviceInfo(it) }
        }

        scanner.startScanning(this) { discoveredDevices ->
            currentDevices = discoveredDevices
            deviceNames.clear()
            deviceNames.addAll(discoveredDevices.map { "${it.name} (${it.address})" })
            deviceListAdapter.notifyDataSetChanged()
        }
    }

    // Show device details in a dialog
    private fun showDeviceInfo(device: BluetoothDeviceInfo) {
        val message = "Name: ${device.name}\nAddress: ${device.address}"

        AlertDialog.Builder(this)
            .setTitle(R.string.device_info_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_attack) { dialog, _ ->
                dialog.dismiss()
                launchAttack(device.name, device.address)
            }
            .setNegativeButton(R.string.action_close) { dialog, _ -> dialog.dismiss() }
            .setNeutralButton(R.string.action_copy_info) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = android.content.ClipData.newPlainText("Device Info", message)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.info_copied, Toast.LENGTH_SHORT).show()
            }
            .create()
            .show()
    }

    private fun launchAttack(name: String, address: String) {
        scanner.stopScanning()
        val intent = Intent(this, AttackActivity::class.java).apply {
            putExtra("DEVICE_NAME", name)
            putExtra("ADDRESS", address)
            putExtra("THREADS", DEFAULT_THREADS)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.stopScanning()
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered; ignore.
            }
            receiverRegistered = false
        }
    }

    override fun onPause() {
        super.onPause()
        scanner.stopScanning()
    }

    override fun onResume() {
        super.onResume()
        scanner.resumeScanning()
    }
}
