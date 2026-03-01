package com.gwc.steeringwheel

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.BluetoothLeScanner
import android.content.*
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

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
        scanner = adapter.bluetoothLeScanner

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            devices.clear()
            scanner.startScan(scanCallback)
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val selected = devices[spinner.selectedItemPosition]
            val intent = Intent(this, BleService::class.java)
            intent.putExtra("deviceAddress", selected.device.address)
            ContextCompat.startForegroundService(this, intent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                updateReceiver,
                IntentFilter("BLE_UPDATE"),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(updateReceiver, IntentFilter("BLE_UPDATE"))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!devices.any { it.device.address == result.device.address }) {
                devices.add(result)
                spinner.adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    devices.map { "${it.device.name ?: "Unknown"}\n${it.device.address}" }
                )
            }
        }
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra("status")
            data.text = intent?.getStringExtra("data")
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
    }
}