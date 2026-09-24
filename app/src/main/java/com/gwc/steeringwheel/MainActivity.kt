package com.gwc.steeringwheel

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var scanner: BluetoothLeScanner

    private val devices = mutableListOf<ScanResult>()

    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private lateinit var data: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinner = findViewById(R.id.deviceSpinner)
        status = findViewById(R.id.txtStatus)
        data = findViewById(R.id.txtData)

        requestPermissions()

        val adapter = BluetoothAdapter.getDefaultAdapter()

        if (adapter == null) {
            status.text = "Bluetooth not supported"
            return
        }

        scanner = adapter.bluetoothLeScanner

        /*
         * MANUAL SCANNING ONLY.
         *
         * Nothing automatically starts scanning.
         */
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            startScan()
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            connectToSelectedDevice()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                updateReceiver,
                IntentFilter("BLE_UPDATE"),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                updateReceiver,
                IntentFilter("BLE_UPDATE")
            )
        }
    }

    /**
     * Performs ONE scan.
     *
     * The scan does not automatically repeat.
     */
    private fun startScan() {

        if (!hasBluetoothPermission()) {
            status.text = "Bluetooth permission required"
            requestPermissions()
            return
        }

        devices.clear()
        updateDeviceList()

        status.text = "Scanning..."

        scanner.startScan(scanCallback)

        /*
         * Stop this scan after 5 seconds.
         *
         * This is NOT a repeating timer.
         * The user has to press Scan again for another scan.
         */
        android.os.Handler(mainLooper).postDelayed({

            if (hasBluetoothPermission()) {
                scanner.stopScan(scanCallback)
            }

            status.text = if (devices.isEmpty()) {
                "No named devices found"
            } else {
                "${devices.size} named device(s) found"
            }

        }, 5000)
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            /*
             * Ignore devices without a usable name.
             */
            val name = result.device.name?.trim()

            if (name.isNullOrEmpty()) {
                return
            }

            /*
             * Ignore duplicate devices.
             */
            if (devices.any {
                    it.device.address == result.device.address
                }) {
                return
            }

            devices.add(result)

            updateDeviceList()
        }

        override fun onScanFailed(errorCode: Int) {
            status.text = "Scan failed ($errorCode)"
        }
    }

    private fun updateDeviceList() {

        val names = devices.map {
            val name = it.device.name?.trim() ?: return@map ""
            "$name\n${it.device.address}"
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            names
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        spinner.adapter = adapter
    }

    private fun connectToSelectedDevice() {

        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                "Scan for a device first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val position = spinner.selectedItemPosition

        if (position < 0 || position >= devices.size) {
            return
        }

        val selected = devices[position]

        val deviceName =
            selected.device.name?.trim() ?: "Unknown Device"

        val deviceAddress =
            selected.device.address

        val intent = Intent(
            this,
            BleService::class.java
        )

        intent.putExtra(
            "deviceAddress",
            deviceAddress
        )

        intent.putExtra(
            "deviceName",
            deviceName
        )

        ContextCompat.startForegroundService(
            this,
            intent
        )

        status.text =
            "Connecting to $deviceName..."
    }

    private val updateReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {
            status.text =
                intent?.getStringExtra("status") ?: ""

            data.text =
                intent?.getStringExtra("data") ?: ""
        }
    }

    private fun hasBluetoothPermission(): Boolean {

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED

        } else {

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {

        val permissions = mutableListOf<String>()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(
                Manifest.permission.BLUETOOTH_SCAN
            )

            permissions.add(
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            permissions.add(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            1
        )
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(updateReceiver)
        } catch (_: Exception) {
        }
    }
}